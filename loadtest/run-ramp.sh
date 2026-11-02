#!/usr/bin/env bash
# Ramp load test, end to end, everything in Docker:
#   fresh images → stack up → seed → booking simulator + Locust ramp 0 → 100 RPS
#   → post-run probes → logs collected → analysis.md (potential bugs).
#
#   loadtest/run-ramp.sh                       # ramp 300 s, hold 180 s at 100 RPS
#   RAMP_UP_SECONDS=120 RAMP_HOLD_SECONDS=60 loadtest/run-ramp.sh
#   LOADTEST_PROFILE=book-ramp loadtest/run-ramp.sh   # same ramp on POST /appointments (Spec07)
#   LOADTEST_PROFILE=book-burst loadtest/run-ramp.sh  # same-slot waves at 150 RPS: 30 s ramp, 240 s hold
#
# Profile 'ramp' reads availability while the simulator writes bookings into PostgreSQL.
# Profile 'book-ramp' books through the API itself, so the simulator is off (SIMULATOR=0).
# Profile 'book-burst' too; it adds verify/book-burst.sql (bookings per slot vs capacity).
#
# Output: loadtest/reports/<profile>-<timestamp>/ (git-ignored). Exit code 1 when the analysis
# reports a high-severity finding or Locust misses a budget.
set -euo pipefail
cd "$(dirname "$0")/.."

export LOADTEST_PROFILE=${LOADTEST_PROFILE:-ramp}
case "$LOADTEST_PROFILE" in
  ramp) SIMULATOR=${SIMULATOR:-1} ;;
  book-ramp) SIMULATOR=${SIMULATOR:-0} ;;
  book-burst) SIMULATOR=${SIMULATOR:-0}
    RAMP_MAX_RPS=${RAMP_MAX_RPS:-150} RAMP_UP_SECONDS=${RAMP_UP_SECONDS:-30} RAMP_HOLD_SECONDS=${RAMP_HOLD_SECONDS:-240} ;;
  *) echo "run-ramp.sh: profile '$LOADTEST_PROFILE' has no load shape (use ramp, book-ramp or book-burst)" >&2; exit 2 ;;
esac
export RAMP_MAX_RPS=${RAMP_MAX_RPS:-100}
export RAMP_UP_SECONDS=${RAMP_UP_SECONDS:-300}
export RAMP_HOLD_SECONDS=${RAMP_HOLD_SECONDS:-180}
export SIM_DURATION=$((RAMP_UP_SECONDS + RAMP_HOLD_SECONDS))
# Shape stops the run; this is only a safety net
export LOCUST_RUN_TIME="$((SIM_DURATION + 60))s"

TS=$(date +%Y%m%d-%H%M%S)
RUN=loadtest/reports/$LOADTEST_PROFILE-$TS
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.loadtest.yml)
# Writable nodes come from .env (PG_PRIMARY / REDIS_MASTER), not from fixed service names
PG_PRIMARY=${PG_PRIMARY:-$(sed -n 's/^PG_PRIMARY=//p' .env 2>/dev/null | tail -1)}
export PG_PRIMARY=${PG_PRIMARY:-postgres-1}
REDIS_MASTER=${REDIS_MASTER:-$(sed -n 's/^REDIS_MASTER=//p' .env 2>/dev/null | tail -1)}
export REDIS_MASTER=${REDIS_MASTER:-redis-1}
psql() { docker compose exec -T "$PG_PRIMARY" psql -U "${POSTGRES_USER:-scheduler}" -d "${POSTGRES_DB:-scheduler}" "$@"; }
step() { printf '\n=== %s  %s\n' "$(date +%T)" "$*"; }

mkdir -p "$RUN/logs"

step "archive old app logs → logs/archive/before-$LOADTEST_PROFILE-$TS (this run gets clean files)"
mkdir -p "logs/archive/before-$LOADTEST_PROFILE-$TS"
find logs -maxdepth 1 -name '*.log' -exec mv {} "logs/archive/before-$LOADTEST_PROFILE-$TS/" \;

step "build images from the working tree and start the stack"
"${COMPOSE[@]}" --profile loadtest build server-api scheduler-batch locust simulator
docker compose up -d --force-recreate --wait

step "seed (wipes domain tables) and empty Redis: every run starts cold"
seeding/seed.sh > "$RUN/seed.txt"
docker compose exec -T "$REDIS_MASTER" redis-cli FLUSHALL > /dev/null
docker compose exec -T "$REDIS_MASTER" redis-cli CONFIG RESETSTAT > /dev/null
START=$(psql -Atc "SELECT now()")
# Log timestamps are local to the app containers (TZ in docker-compose.yml): the analysis
# skips lines before this, e.g. shutdown lines of the containers --force-recreate replaced
TZ=${TZ:-Asia/Ho_Chi_Minh} date '+%Y-%m-%d %H:%M:%S' > "$RUN/start.txt"

step "sample docker stats every 5 s"
echo "ts,name,cpu,mem" > "$RUN/docker_stats.csv"
(
  while true; do
    docker stats --no-stream --format "$(date +%T),{{.Name}},{{.CPUPerc}},{{.MemUsage}}" >> "$RUN/docker_stats.csv" 2>/dev/null || true
    sleep 5
  done
) &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true; docker rm -f keyloop-ramp-simulator >/dev/null 2>&1 || true' EXIT

if [ "$SIMULATOR" = 1 ]; then
  step "booking simulator: ${SIM_RATE:-2} writes/s for ${SIM_DURATION}s"
  "${COMPOSE[@]}" run -d --name keyloop-ramp-simulator simulator > /dev/null
fi

step "Locust $LOADTEST_PROFILE 0 → ${RAMP_MAX_RPS} RPS over ${RAMP_UP_SECONDS}s, hold ${RAMP_HOLD_SECONDS}s"
LOCUST_EXIT=0
"${COMPOSE[@]}" run --rm locust > "$RUN/locust.txt" 2>&1 || LOCUST_EXIT=$?
tail -40 "$RUN/locust.txt"

SIM_EXIT=0
if [ "$SIMULATOR" = 1 ]; then
  step "wait for the simulator"
  SIM_EXIT=$(docker wait keyloop-ramp-simulator)
  docker logs keyloop-ramp-simulator > "$RUN/simulator.log" 2>&1
  docker rm keyloop-ramp-simulator > /dev/null
fi
step "give the relay 15 s to drain"
sleep 15
kill $SAMPLER 2>/dev/null || true

step "cache freshness: cached answer (Redis) must equal a fresh answer (PostgreSQL) once the relay drained"
python3 - "$RUN/cache_freshness.txt" <<'PY'
import json, os, subprocess, sys, urllib.request
from datetime import datetime, timedelta
from urllib.parse import urlencode
from zoneinfo import ZoneInfo
refs = json.load(open("loadtest/data/seed-refs.json"))
today = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
d = lambda n: (today + timedelta(days=n)).isoformat()
queries = {"RP-01 oil change": ("OIL_CHANGE", d(1), d(7)), "RP-02 tyre rotation": ("TYRE_ROTATION", d(1), d(3))}
def get(service, frm, to):
    q = urlencode({"dealershipId": refs["dealerships"]["Q7"], "serviceTypeId": refs["serviceTypes"][service],
                   "vehicleTypeId": refs["vehicleTypes"]["GASOLINE_CAR"], "from": frm, "to": to})
    with urllib.request.urlopen(f"http://localhost:8080/api/v1/availability?{q}") as r:
        return json.load(r)["days"]
cached = {k: get(*v) for k, v in queries.items()}
subprocess.run(["docker", "compose", "exec", "-T", os.environ["REDIS_MASTER"], "redis-cli", "FLUSHALL"], check=True, capture_output=True)
lines = []
for k, v in queries.items():
    for c, f in zip(cached[k], get(*v)):
        cs, fs = {s["start"] for s in c["slots"]}, {s["start"] for s in f["slots"]}
        status = "OK" if cs == fs else "MISMATCH"
        lines.append(f"{status} {k} {c['date']}: cached {len(cs)} slots, fresh {len(fs)}"
                     + (f"; only cached {sorted(cs - fs)[:5]} only fresh {sorted(fs - cs)[:5]}" if cs != fs else ""))
open(sys.argv[1], "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY

step "post-run probes"
psql -v since="'1 hour'" < loadtest/verify/invariants.sql > "$RUN/invariants.txt" 2>&1 || true
if [ "$LOADTEST_PROFILE" = book-burst ]; then
  psql -v start="$START" < loadtest/verify/book-burst.sql > "$RUN/burst_check.txt" 2>&1 || true
fi
{
  echo "total|pending|failed|lag_p50_ms|lag_p95_ms|lag_max_ms   (outbox events of this run: created → processed)"
  psql -At -F '|' -c "
    SELECT count(*),
           count(*) FILTER (WHERE processed_at IS NULL AND failed_at IS NULL),
           count(*) FILTER (WHERE failed_at IS NOT NULL),
           coalesce(round(percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - created_at) * 1000)::numeric, 1), 0),
           coalesce(round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - created_at) * 1000)::numeric, 1), 0),
           coalesce(round(max(extract(epoch FROM processed_at - created_at) * 1000)::numeric, 1), 0)
    FROM outbox_events WHERE created_at >= '$START'"
  echo; echo "max attempts / last errors:"
  psql -At -c "SELECT attempts, count(*), max(last_error) FROM outbox_events WHERE created_at >= '$START' GROUP BY 1 ORDER BY 1"
} > "$RUN/relay_lag.txt"
docker compose exec -T "$REDIS_MASTER" redis-cli INFO stats | grep -E 'keyspace_(hits|misses)|expired_keys|evicted_keys|total_commands' > "$RUN/redis_info.txt"
docker compose exec -T "$REDIS_MASTER" redis-cli INFO memory | grep -E 'used_memory_human|maxmemory_human' >> "$RUN/redis_info.txt"
psql -c "SELECT numbackends, xact_commit, xact_rollback, deadlocks, conflicts, temp_files, blks_hit, blks_read
         FROM pg_stat_database WHERE datname = current_database()" > "$RUN/pg_stats.txt"
psql -c "SELECT state, count(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY 1" >> "$RUN/pg_stats.txt"

step "collect logs and Locust reports"
# Log4j2 rolls a file at 20 MB into logs/archive/<name>-<date>-<i>.log.gz: at 100 RPS that
# happens mid-run, so rebuild one file per container from its chunks rolled during the run
# (oldest first) + the live file. Otherwise the analysis misses the start of the ramp.
for cur in logs/*.log; do
  base=$(basename "$cur" .log)
  {
    for gz in $(ls -tr logs/archive/"$base"-*.log.gz 2>/dev/null); do
      if [ "$gz" -nt "$RUN/start.txt" ]; then gzip -dc "$gz"; fi
    done
    cat "$cur"
  } > "$RUN/logs/$base.log"
done
# The simulator always writes ramp_simulator.json, whatever the profile
for f in "loadtest/reports/$LOADTEST_PROFILE.html" "loadtest/reports/${LOADTEST_PROFILE}"_*.csv \
         loadtest/reports/ramp_simulator.json "loadtest/reports/verdict-$LOADTEST_PROFILE.json"; do
  [ -e "$f" ] && mv "$f" "$RUN/"
done

step "analysis → $RUN/analysis.md"
ANALYSIS_EXIT=0
python3 loadtest/tools/analyze_logs.py "$RUN" "$LOADTEST_PROFILE" > /dev/null || ANALYSIS_EXIT=$?
echo "locust exit=$LOCUST_EXIT simulator exit=$SIM_EXIT analysis exit=$ANALYSIS_EXIT"
echo "report: $RUN/analysis.md"
[ "$LOCUST_EXIT" -eq 0 ] && [ "$ANALYSIS_EXIT" -eq 0 ]
