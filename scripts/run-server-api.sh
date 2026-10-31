#!/usr/bin/env bash
# Run server-api on the host (not in Docker), against the Docker Postgres/Redis.
#
#   scripts/run-server-api.sh                 # http://localhost:8090
#   scripts/run-server-api.sh --debug         # + JDWP on 5005 → IntelliJ "Attach server-api"
#   scripts/run-server-api.sh --debug --suspend
#
# Port 8090, not 8080: 8080 is nginx. nginx does not route to this instance; call it directly.
# Flyway runs on start, like the container (safe next to running replicas: Flyway locks).
APP=server-api
APP_PORT_VAR=API_PORT
APP_PORT=8090
DEBUG_PORT=${DEBUG_PORT:-5005}

source "$(dirname "$0")/lib/run-app.sh"
run_app "$@"
