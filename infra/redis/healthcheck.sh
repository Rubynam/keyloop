#!/bin/sh
# Role-aware healthcheck. Healthy only when the node plays the role .env gives it:
#   NODE_NAME == REDIS_MASTER → role:master
#   otherwise                 → role:slave with master_link_status:up
info=$(redis-cli INFO replication | tr -d '\r') || exit 1
if [ "$NODE_NAME" = "$REDIS_MASTER" ]; then
    echo "$info" | grep -qx 'role:master'
else
    echo "$info" | grep -qx 'role:slave' && echo "$info" | grep -qx 'master_link_status:up'
fi
