#!/bin/sh
# Role-aware healthcheck (spine AD-8). Healthy only when the node plays the role .env gives it:
#   NODE_NAME == PG_PRIMARY → pg_isready + pg_is_in_recovery() = f
#   otherwise               → pg_isready + pg_is_in_recovery() = t + WAL receiver streaming
# TCP on 127.0.0.1, not the socket: the init-time server listens on the socket only,
# so the node is not reported healthy while init scripts still run.
export PGPASSWORD="$POSTGRES_PASSWORD"
export PGCONNECT_TIMEOUT=2
pg_isready -q -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" || exit 1
in_recovery=$(psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT pg_is_in_recovery()") || exit 1
if [ "$NODE_NAME" = "$PG_PRIMARY" ]; then
    [ "$in_recovery" = f ]
else
    [ "$in_recovery" = t ] || exit 1
    receiver=$(psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT status FROM pg_stat_wal_receiver") || exit 1
    [ "$receiver" = streaming ]
fi
