#!/usr/bin/env bash
# Run scheduler-batch on the host (not in Docker), against the Docker Postgres/Redis.
#
#   scripts/run-scheduler-batch.sh            # actuator on http://localhost:8091
#   scripts/run-scheduler-batch.sh --debug    # + JDWP on 5006 → IntelliJ "Attach scheduler-batch"
#
# Port 8091, not 8081: 8081 is the batch container. Both may run at once: ShedLock lets only
# one of them execute each job tick. Stop the container to be sure your breakpoint is hit:
#   docker compose stop scheduler-batch
APP=scheduler-batch
APP_PORT_VAR=BATCH_PORT
APP_PORT=8091
DEBUG_PORT=${DEBUG_PORT:-5006}

# The batch reads domain tables but does not own them: the schema comes from server-api's Flyway
before_start() {
    if ! docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
        "SELECT 1 FROM flyway_schema_history LIMIT 1" >/dev/null 2>&1; then
        echo "No Flyway schema yet: start server-api once first (scripts/run-server-api.sh)" >&2
        exit 1
    fi
}

source "$(dirname "$0")/lib/run-app.sh"
run_app "$@"
