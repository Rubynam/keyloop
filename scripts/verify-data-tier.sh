#!/usr/bin/env bash
# Checks the data-tier replication matrix (story 1, spec-data-replication) on the compose stack.
#
#   scripts/verify-data-tier.sh                 # non-destructive, on a running stack
#   scripts/verify-data-tier.sh --destructive   # + replica restart, re-seed, active-slot refusal
#
# Default mode: streaming replica on its slot, replica rejects writes, Redis replicates and the
# replica says READONLY, every node's healthcheck fails when run with the opposite role.
# --destructive restarts the Postgres replica and deletes its volume (the primary is untouched).
# Prints PASS/FAIL per row; exit code 1 when any row fails.
set -euo pipefail
cd "$(dirname "$0")/.."

DESTRUCTIVE=false
case "${1:-}" in
    "") ;;
    --destructive) DESTRUCTIVE=true ;;
    *) echo "usage: $(basename "$0") [--destructive]" >&2; exit 2 ;;
esac

# Roles come from the environment, else .env, else the defaults (same as seed.sh)
PG_PRIMARY=${PG_PRIMARY:-$(sed -n 's/^PG_PRIMARY=//p' .env 2>/dev/null | tail -1)}
PG_PRIMARY=${PG_PRIMARY:-postgres-1}
REDIS_MASTER=${REDIS_MASTER:-$(sed -n 's/^REDIS_MASTER=//p' .env 2>/dev/null | tail -1)}
REDIS_MASTER=${REDIS_MASTER:-redis-1}
PG_DB=${POSTGRES_DB:-$(sed -n 's/^POSTGRES_DB=//p' .env 2>/dev/null | tail -1)}
PG_DB=${PG_DB:-scheduler}
PG_USER=${POSTGRES_USER:-$(sed -n 's/^POSTGRES_USER=//p' .env 2>/dev/null | tail -1)}
PG_USER=${PG_USER:-scheduler}
PG_REPL_USER=${PG_REPL_USER:-$(sed -n 's/^PG_REPL_USER=//p' .env 2>/dev/null | tail -1)}
PG_REPL_USER=${PG_REPL_USER:-replicator}
PG_REPL_PASSWORD=${PG_REPL_PASSWORD:-$(sed -n 's/^PG_REPL_PASSWORD=//p' .env 2>/dev/null | tail -1)}
PG_REPL_PASSWORD=${PG_REPL_PASSWORD:-replicator}

other() { case "$1" in *-1) echo "${1%-1}-2" ;; *-2) echo "${1%-2}-1" ;; *) echo "unknown node $1" >&2; exit 2 ;; esac; }
PG_REPLICA=$(other "$PG_PRIMARY")
REDIS_REPLICA=$(other "$REDIS_MASTER")
PROJECT=$(docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')
NETWORK="${PROJECT}_data-net"
SLOT=replica_slot

FAILS=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s%s\n' "$1" "${2:+  ($2)}"; FAILS=$((FAILS + 1)); }

sql() { local node=$1; shift; docker compose exec -T "$node" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -tA "$@"; }
rcli() { local node=$1; shift; docker compose exec -T "$node" redis-cli "$@" | tr -d '\r'; }
container() { docker compose ps -q "$1"; }

replication_row() {
    sql "$PG_PRIMARY" -c "SELECT state || '|' || slot_name FROM pg_stat_replication
                          JOIN pg_replication_slots ON active_pid = pid" 2>/dev/null || true
}

# Waits until the replica streams again from the slot (after a restart or re-seed)
wait_streaming() {
    local i
    for i in $(seq 60); do
        [ "$(replication_row)" = "streaming|$SLOT" ] && return 0
        sleep 2
    done
    return 1
}

# Writes a probe row on the primary and polls the replica for it
replicated_probe() {
    local value=$1 i
    for i in $(seq 20); do
        [ "$(sql "$PG_REPLICA" -c "SELECT v FROM verify_data_tier WHERE v = '$value'" 2>/dev/null)" = "$value" ] && return 0
        sleep 0.5
    done
    return 1
}

echo "roles: postgres primary=$PG_PRIMARY replica=$PG_REPLICA · redis master=$REDIS_MASTER replica=$REDIS_REPLICA"
echo "mode: $($DESTRUCTIVE && echo destructive || echo non-destructive)"
echo

# ── Non-destructive rows ────────────────────────────────────────────────────────────────
row="pg: $PG_PRIMARY shows one streaming replica on $SLOT"
got=$(replication_row)
[ "$got" = "streaming|$SLOT" ] && pass "$row" || fail "$row" "got '${got//$'\n'/; }'"

# Probe writes never abort the run: a failed write is reported as a FAIL row
probe_write() { sql "$PG_PRIMARY" -q -c "CREATE TABLE IF NOT EXISTS verify_data_tier (v text);
                                         INSERT INTO verify_data_tier VALUES ('$1')" >/dev/null 2>&1; }
PROBE="probe-$(date +%s)-$$"
row="pg: row inserted on $PG_PRIMARY is readable on $PG_REPLICA"
if ! probe_write "$PROBE"; then fail "$row" "insert on $PG_PRIMARY failed"
elif replicated_probe "$PROBE"; then pass "$row"; else fail "$row"; fi

row="pg: write on $PG_REPLICA fails read-only"
# Needs no probe table: any write on a standby is rejected
out=$(sql "$PG_REPLICA" -c "CREATE TABLE verify_ro_probe (i int)" 2>&1 || true)
[[ "$out" == *"read-only transaction"* ]] && pass "$row" || fail "$row" "got '$out'"

KEY="verify-data-tier:$$"
rcli "$REDIS_MASTER" SET "$KEY" v EX 60 >/dev/null 2>&1 || true
row="redis: SET on $REDIS_MASTER is readable on $REDIS_REPLICA"
got=""
for _ in $(seq 20); do got=$(rcli "$REDIS_REPLICA" GET "$KEY"); [ "$got" = v ] && break; sleep 0.25; done
[ "$got" = v ] && pass "$row" || fail "$row" "got '$got'"

row="redis: SET on $REDIS_REPLICA returns READONLY"
out=$(rcli "$REDIS_REPLICA" SET "$KEY" other 2>&1 || true)
[[ "$out" == READONLY* ]] && pass "$row" || fail "$row" "got '$out'"
rcli "$REDIS_MASTER" DEL "$KEY" >/dev/null 2>&1 || true

for node in "$PG_PRIMARY" "$PG_REPLICA"; do
    row="health: $node healthy with PG_PRIMARY=$PG_PRIMARY"
    docker compose exec -T "$node" /usr/local/bin/healthcheck.sh && pass "$row" || fail "$row"
    row="health: $node unhealthy with the opposite role (PG_PRIMARY=$(other "$PG_PRIMARY"))"
    docker compose exec -T -e PG_PRIMARY="$(other "$PG_PRIMARY")" "$node" /usr/local/bin/healthcheck.sh \
        && fail "$row" "healthcheck passed" || pass "$row"
done
for node in "$REDIS_MASTER" "$REDIS_REPLICA"; do
    row="health: $node healthy with REDIS_MASTER=$REDIS_MASTER"
    docker compose exec -T "$node" /usr/local/bin/healthcheck.sh && pass "$row" || fail "$row"
    row="health: $node unhealthy with the opposite role (REDIS_MASTER=$(other "$REDIS_MASTER"))"
    docker compose exec -T -e REDIS_MASTER="$(other "$REDIS_MASTER")" "$node" /usr/local/bin/healthcheck.sh \
        && fail "$row" "healthcheck passed" || pass "$row"
done

# ── Destructive rows (Postgres replica only) ────────────────────────────────────────────
if $DESTRUCTIVE; then
    echo
    # Replica restart: data kept, no new base backup
    PROBE="restart-$(date +%s)-$$"
    probe_write "$PROBE" && replicated_probe "$PROBE" || true
    since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    docker compose restart "$PG_REPLICA" >/dev/null 2>&1 \
        || fail "restart: docker compose restart $PG_REPLICA" "command failed"
    docker compose up -d --wait "$PG_REPLICA" >/dev/null 2>&1 || true
    row="restart: $PG_REPLICA streams again after a restart"
    wait_streaming && pass "$row" || fail "$row" "got '$(replication_row)'"
    row="restart: $PG_REPLICA kept its data (no new base backup)"
    logs=$(docker logs --since "$since" "$(container "$PG_REPLICA")" 2>&1 || true)
    if [[ "$logs" == *"base backup done"* ]]; then
        fail "$row" "base backup ran again"
    elif [ "$(sql "$PG_REPLICA" -c "SELECT v FROM verify_data_tier WHERE v = '$PROBE'" 2>/dev/null)" != "$PROBE" ]; then
        fail "$row" "probe row missing"
    else
        pass "$row"
    fi

    # Re-seed: replica volume deleted, its slot is left inactive on the primary
    docker compose rm -sf "$PG_REPLICA" >/dev/null 2>&1 \
        || fail "re-seed: docker compose rm -sf $PG_REPLICA" "command failed"
    docker volume rm "${PROJECT}_pg-data-${PG_REPLICA##*-}" >/dev/null 2>&1 \
        || fail "re-seed: docker volume rm ${PROJECT}_pg-data-${PG_REPLICA##*-}" "command failed"
    row="re-seed: $SLOT is stale (inactive) on $PG_PRIMARY after the replica is gone"
    got=$(sql "$PG_PRIMARY" -c "SELECT active FROM pg_replication_slots WHERE slot_name = '$SLOT'" 2>/dev/null || true)
    [ "$got" = f ] && pass "$row" || fail "$row" "active='$got'"
    docker compose up -d --wait "$PG_REPLICA" >/dev/null 2>&1 || true
    logs=$(docker logs "$(container "$PG_REPLICA")" 2>&1 || true)
    row="re-seed: stale $SLOT dropped and the replica re-seeded by base backup"
    [[ "$logs" == *"dropping stale inactive slot $SLOT"* && "$logs" == *"base backup done"* ]] \
        && pass "$row" || fail "$row" "entrypoint log: $(grep -c node-entrypoint <<<"$logs") lines"
    row="re-seed: $PG_REPLICA streams on $SLOT again"
    wait_streaming && pass "$row" || fail "$row" "got '$(replication_row)'"
    PROBE="reseed-$(date +%s)-$$"
    row="re-seed: new rows replicate to the re-seeded $PG_REPLICA"
    probe_write "$PROBE" && replicated_probe "$PROBE" && pass "$row" || fail "$row"

    # A second empty standby while the slot is active must refuse to re-seed
    row="re-seed: second empty standby is refused while $SLOT is active"
    rc=0
    out=$(docker run --rm --network "$NETWORK" \
        -e NODE_NAME=verify-standby -e PG_PRIMARY="$PG_PRIMARY" -e PG_PRIMARY_WAIT_SECONDS=20 \
        -e PG_REPL_USER="$PG_REPL_USER" -e PG_REPL_PASSWORD="$PG_REPL_PASSWORD" \
        -e POSTGRES_DB="$PG_DB" -e POSTGRES_PASSWORD=unused \
        -v "$PWD/infra/postgres/node-entrypoint.sh:/usr/local/bin/node-entrypoint.sh:ro" \
        --entrypoint /usr/local/bin/node-entrypoint.sh postgres:18-alpine postgres 2>&1) || rc=$?
    if [ "$rc" -ne 0 ] && [[ "$out" == *"is in use by another standby"* ]]; then
        pass "$row"
    else
        fail "$row" "exit=$rc"
    fi
    row="re-seed: refused standby left $PG_REPLICA streaming"
    [ "$(replication_row)" = "streaming|$SLOT" ] && pass "$row" || fail "$row"
fi

sql "$PG_PRIMARY" -q -c "DROP TABLE IF EXISTS verify_data_tier" >/dev/null 2>&1 || true

echo
if [ "$FAILS" -eq 0 ]; then
    echo "all rows passed"
else
    echo "$FAILS row(s) failed"
    exit 1
fi
