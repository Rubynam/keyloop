#!/bin/sh
# Runs once, on the first start of an empty primary volume (spine AD-8).
# Creates the replication role and lets it connect for streaming. A replica copies both
# with pg_basebackup, so a promoted node already has them.
# The slot is created by the replica (pg_basebackup -C), not here: slots do not replicate.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v repl_user="$PG_REPL_USER" -v repl_password="$PG_REPL_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I WITH REPLICATION LOGIN PASSWORD %L', :'repl_user', :'repl_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'repl_user')
\gexec
SQL

# The image's "host all all all" line does not match replication connections
line="host replication $PG_REPL_USER all scram-sha-256"
grep -qxF "$line" "$PGDATA/pg_hba.conf" || echo "$line" >> "$PGDATA/pg_hba.conf"
