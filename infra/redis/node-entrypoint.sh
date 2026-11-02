#!/bin/sh
# Entrypoint of every Redis node (redis-1, redis-2). The role comes from .env:
# NODE_NAME == REDIS_MASTER → master, else replica of REDIS_MASTER (spine AD-1).
# A replica refuses reads while its master link is down (no stale cache served).
set -eu

: "${NODE_NAME:?NODE_NAME is required}"
: "${REDIS_MASTER:?REDIS_MASTER is required}"

if [ "$NODE_NAME" != "$REDIS_MASTER" ]; then
    set -- "$@" --replicaof "$REDIS_MASTER" 6379 --replica-serve-stale-data no
fi

exec docker-entrypoint.sh "$@"
