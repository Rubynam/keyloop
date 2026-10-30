-- Runs once, on the first start of an empty data volume.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Spring Batch metadata + ShedLock live here, away from domain tables.
CREATE SCHEMA IF NOT EXISTS batch AUTHORIZATION CURRENT_USER;
