#!/bin/sh
# Entrypoint of every Postgres node (postgres-1, postgres-2). The role comes from .env,
# never from the service name: NODE_NAME == PG_PRIMARY → primary, else replica (spine AD-1/AD-8).
#
#   primary, any volume       → stock entrypoint (initdb + init scripts on an empty volume)
#   replica, data present     → stock entrypoint, starts as standby (standby.signal is there)
#   replica, empty volume     → wait for primary → drop stale inactive replica_slot
#                               → pg_basebackup -R -C -S replica_slot → stock entrypoint
#   replica, seeding marker   → an earlier base backup was cut off: wipe PGDATA, seed again
#   replica, data, no standby.signal → refuse (old primary after a PG_PRIMARY flip)
#   primary, standby.signal   → warn: still a standby until promoted (story 5 failover)
set -eu

: "${NODE_NAME:?NODE_NAME is required}"
: "${PG_PRIMARY:?PG_PRIMARY is required}"

SLOT=replica_slot
WAIT_SECONDS=${PG_PRIMARY_WAIT_SECONDS:-120}

# Outside PGDATA: pg_basebackup needs an empty target directory
SEEDING_MARKER="$(dirname "$PGDATA")/.seeding-$NODE_NAME"

log() { echo "node-entrypoint[$NODE_NAME]: $*" >&2; }

if [ "$NODE_NAME" != "$PG_PRIMARY" ] && [ -e "$SEEDING_MARKER" ]; then
    log "an earlier base backup did not finish, emptying $PGDATA to seed again"
    if [ -d "$PGDATA" ]; then find "$PGDATA" -mindepth 1 -delete; fi
fi

if [ "$NODE_NAME" != "$PG_PRIMARY" ] && [ ! -s "$PGDATA/PG_VERSION" ]; then
    : "${PG_REPL_USER:?PG_REPL_USER is required}"
    : "${PG_REPL_PASSWORD:?PG_REPL_PASSWORD is required}"
    export PGPASSWORD="$PG_REPL_PASSWORD"

    log "replica with an empty volume: seeding from $PG_PRIMARY"
    waited=0
    until pg_isready -q -h "$PG_PRIMARY" -p 5432; do
        if [ "$waited" -ge "$WAIT_SECONDS" ]; then
            log "primary $PG_PRIMARY not ready after ${WAIT_SECONDS}s, giving up"
            exit 1
        fi
        sleep 2
        waited=$((waited + 2))
    done

    # Slots do not replicate. A slot left over from an earlier replica keeps WAL forever
    # and makes "pg_basebackup -C" fail, so drop it, but only when nobody streams from it.
    q() { psql -h "$PG_PRIMARY" -U "$PG_REPL_USER" -d "${POSTGRES_DB:-postgres}" -v ON_ERROR_STOP=1 -tAc "$1"; }
    active=$(q "SELECT active FROM pg_replication_slots WHERE slot_name = '$SLOT'")
    case "$active" in
        t) log "slot $SLOT on $PG_PRIMARY is in use by another standby, refusing to re-seed"; exit 1 ;;
        f) log "dropping stale inactive slot $SLOT on $PG_PRIMARY"
           q "SELECT pg_drop_replication_slot('$SLOT')" >/dev/null ;;
    esac

    mkdir -p "$PGDATA"
    chown postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    touch "$SEEDING_MARKER"
    # -R writes standby.signal + primary_conninfo/primary_slot_name; -C creates the slot
    if ! gosu postgres pg_basebackup -h "$PG_PRIMARY" -p 5432 -U "$PG_REPL_USER" \
            -D "$PGDATA" -R -X stream -C -S "$SLOT" --checkpoint=fast; then
        # A half-copied PGDATA would start as a broken standby next time: leave it empty
        log "pg_basebackup failed, emptying $PGDATA"
        find "$PGDATA" -mindepth 1 -delete
        exit 1
    fi
    rm -f "$SEEDING_MARKER"
    unset PGPASSWORD
    log "base backup done, starting as standby of $PG_PRIMARY"
fi

if [ -s "$PGDATA/PG_VERSION" ]; then
    if [ "$NODE_NAME" != "$PG_PRIMARY" ] && [ ! -e "$PGDATA/standby.signal" ]; then
        # Starting would give a second writable node (split-brain)
        log "has data but no standby.signal while PG_PRIMARY=$PG_PRIMARY: refusing to start writable;" \
            "delete this node's volume so it re-seeds as a replica (story 5 failover)"
        exit 1
    fi
    if [ "$NODE_NAME" = "$PG_PRIMARY" ] && [ -e "$PGDATA/standby.signal" ]; then
        log "WARNING: designated primary is still a standby: promote it (story 5 failover)"
    fi
fi

exec docker-entrypoint.sh "$@"
