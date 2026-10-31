#!/usr/bin/env bash
# Shared part of scripts/run-server-api.sh and scripts/run-scheduler-batch.sh.
# Builds one module's boot jar and runs it on the host JVM against the Docker
# Postgres/Redis, optionally with a JDWP debug port for IntelliJ "Remote JVM Debug".
#
# Caller sets: APP (gradle module), APP_PORT_VAR (env var the app reads its port from),
# APP_PORT, DEBUG_PORT. Then: run_app "$@"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<EOF
Usage: $(basename "$0") [--debug] [--suspend] [--no-build] [--no-infra]

  --debug     open a JDWP port (${DEBUG_PORT}) for IntelliJ "Remote JVM Debug"
  --suspend   with --debug: wait for the debugger before starting (debug start-up code)
  --no-build  run the last built jar (skip ./gradlew :${APP}:bootJar)
  --no-infra  do not start postgres/redis with docker compose

Env overrides: LOCAL_PORT (default ${APP_PORT}), DEBUG_PORT (default ${DEBUG_PORT}), JAVA_OPTS
EOF
}

# JDK 25 (toolchain of the build): JAVA_HOME if it is 25, else the one macOS knows about
find_java() {
    if [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"25'; then
        echo "$JAVA_HOME/bin/java"
    elif [[ -x /usr/libexec/java_home ]] && JH=$(/usr/libexec/java_home -v 25 2>/dev/null); then
        echo "$JH/bin/java"
    elif java -version 2>&1 | grep -q '"25'; then
        command -v java
    else
        echo "JDK 25 not found: set JAVA_HOME to a JDK 25" >&2
        exit 1
    fi
}

run_app() {
    local debug=false suspend=n build=true infra=true
    for arg in "$@"; do
        case "$arg" in
            --debug) debug=true ;;
            --suspend) suspend=y ;;
            --no-build) build=false ;;
            --no-infra) infra=false ;;
            -h|--help) usage; exit 0 ;;
            *) echo "unknown option: $arg" >&2; usage; exit 2 ;;
        esac
    done
    cd "$ROOT"

    # DB credentials etc. from .env (same values as docker compose)
    [[ -f .env ]] || cp .env.example .env
    set -a; source .env; set +a

    # .env's API_PORT / BATCH_PORT are the *host* ports of nginx and the batch container;
    # the local app needs its own port next to them
    local port="${LOCAL_PORT:-$APP_PORT}"
    export "$APP_PORT_VAR=$port"
    export DB_HOST=localhost DB_PORT="${POSTGRES_PORT:-5432}"
    export REDIS_HOST=localhost REDIS_PORT="${REDIS_PORT:-6379}"
    export LOG_PATH="$ROOT/logs"

    if $infra; then
        echo "▶ postgres + redis (docker compose)"
        docker compose up -d --wait postgres redis
    fi

    if declare -F before_start >/dev/null; then
        before_start
    fi

    if $build; then
        echo "▶ ./gradlew :${APP}:bootJar"
        ./gradlew -q ":${APP}:bootJar" -x test
    fi

    local jar="$ROOT/$APP/build/libs/$APP.jar"
    [[ -f "$jar" ]] || { echo "$jar missing: run without --no-build" >&2; exit 1; }

    local java_bin; java_bin=$(find_java)
    local jdwp=()
    if $debug; then
        jdwp=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=${suspend},address=*:${DEBUG_PORT}")
        echo "▶ debug: attach IntelliJ to localhost:${DEBUG_PORT}$([[ $suspend == y ]] && echo ' (JVM waits for it)')"
    fi

    echo "▶ ${APP} on http://localhost:${port}  (java: ${java_bin})"
    # exec: the JVM replaces the shell, so Ctrl+C / IntelliJ Stop reach Spring's graceful shutdown
    # shellcheck disable=SC2086
    # ${jdwp[@]+...}: an empty array is "unbound" under set -u in macOS bash 3.2
    exec "$java_bin" ${jdwp[@]+"${jdwp[@]}"} ${JAVA_OPTS:-} -jar "$jar"
}
