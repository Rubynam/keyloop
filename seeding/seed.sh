#!/usr/bin/env bash
# Loads seeding/sql/*.sql into the compose Postgres in one transaction (Spec03),
# then runs verify.sql. Wipes the domain tables first (00_reset.sql).
# The schema must exist: start server-api once so Flyway applies V2.
#
#   seeding/seed.sh              # database from POSTGRES_DB, default "scheduler"
#   seeding/seed.sh other_db
set -euo pipefail
cd "$(dirname "$0")/.."

DB=${1:-${POSTGRES_DB:-scheduler}}
PG_USER=${POSTGRES_USER:-scheduler}
psql() { docker compose exec -T postgres psql -U "$PG_USER" -d "$DB" -v ON_ERROR_STOP=1 -q "$@"; }

cat seeding/sql/*.sql | psql --single-transaction
psql < seeding/verify.sql
