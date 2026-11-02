# Review: verification lens, ARCHITECTURE-SPINE.md (keyloop-replication)

- Date: 2026-11-01
- Lens: is each committed decision web-researched or checked against reality, or only asserted from memory?
- Method: resolved library versions from the repo and the local Gradle cache, checked class and enum names with `javap` against the actual jars, and read the official docs and image sources (URLs below).

## Verdict

**Mostly sound, with one disproven assumption and two missing details that block a build.** All API names the spine uses exist in the repo's versions. But the open ASSUMPTION about Lettuce is **false**: a static master/replica connection does not detect roles again after a promote. Also, the official Postgres image's default `pg_hba.conf` does **not** allow replication connections from another container. AD-1 says nothing about this.

## Resolved versions (reality check)

| Component | Version in repo | Source |
|---|---|---|
| Spring Boot | 4.1.2 | `gradle/libs.versions.toml` |
| Spring Data Redis | 4.1.2 | `~/.gradle/caches/.../org.springframework.data/spring-data-redis/4.1.2` |
| Lettuce | 7.5.2.RELEASE | `~/.gradle/caches/.../io.lettuce/lettuce-core/7.5.2.RELEASE` |
| pgjdbc | 42.7.14 | `~/.gradle/caches/.../org.postgresql/postgresql/42.7.14` |
| Postgres image | `postgres:18-alpine` (now 18.6, alpine3.24) | Docker Hub |
| Redis image | `redis:8-alpine` (already in `docker-compose.yml`) | repo |

`libs.versions.toml` pins only the Boot BOM. Lettuce, pgjdbc and Spring Data Redis come through the BOM. The spine should name these resolved versions in its `sources`, so the claims below can be checked again after a Boot upgrade.

## Findings

### F1 — [HIGH] Open ASSUMPTION is false: Lettuce static master/replica does not detect roles again (AD-2, AD-6, Open Questions)

- **Docs:** Lettuce says a standalone master/replica setup does "a one-time topology lookup which remains static afterward". It "provides no built-in failover", and after a failover or topology change the connection "must be re-established outside Lettuce". Only the Sentinel provider handles failover. Spring Data Redis also describes master/replica as "without automatic failover".
- **Jar check (Lettuce 7.5.2):** `StaticMasterReplicaConnector` builds a `MasterReplicaTopologyRefresh`. It calls `getNodes` once inside `connectAsync()` and never schedules a refresh. In contrast, `SentinelConnector` has `getTopologyRefreshRunnable` and `SentinelTopologyRefresh.bind`. When a node reconnects, its old role stays cached.
- **Impact:** after `REPLICAOF NO ONE` on redis-replica (with redis-master stopped), the `UPSTREAM` factory has no known upstream. So version `GET`, `SET NX`, `INCR` all fail with "Cannot determine a node ..." until the connection is created again. REL-05 fail-open hides this as permanent cache misses rather than errors, so it can go unnoticed.
- **Fix:** turn the ASSUMPTION into a decision in AD-2. The runbook step after a Redis promote is one of these:
  1. restart both apps, or
  2. call `LettuceConnectionFactory.resetConnection()` (with the shared native connection) or recreate the factory through an ops endpoint.

  With a pool, new connections run `ROLE` again, but connections already in the pool keep the old roles. Do not rely on that. Remove the line "re-detects roles on reconnect".

### F2 — [HIGH] AD-1 leaves out what the official Postgres image needs for `pg_basebackup -R`

From the official image's `docker-entrypoint.sh` and the PG 18 docs:

- On first init, the entrypoint appends only `host all all all <scram-sha-256>` to `pg_hba.conf`. The `all` keyword in the database field does **not** match physical replication connections. Only the `replication` keyword matches them (pg_hba docs). The `initdb` defaults allow replication from localhost only. **Result: `pg_basebackup` from the replica container is rejected unless the primary adds a line like `host replication <repl_role> all scram-sha-256`.** Put it in an init script in `infra/postgres/init/` (for example `echo ... >> "$PGDATA/pg_hba.conf"`) along with `CREATE ROLE <repl_role> WITH REPLICATION LOGIN PASSWORD ...`.
- Init scripts run **only when the data directory is empty**. The existing `pg-data` volume already holds data, so the new role and the new hba line will not apply until the volume is recreated (`docker compose down -v`). The spine or the story should say this.
- On the replica, the entrypoint skips `initdb` when `$PGDATA/PG_VERSION` exists. So the confirmed pattern is a wrapper entrypoint. If PGDATA (`/var/lib/postgresql/18/docker` for PG 18+) is empty, it runs `pg_basebackup -h postgres-primary -U <repl_role> -D "$PGDATA" -R -X stream` as the `postgres` user and then `exec docker-entrypoint.sh postgres`. The target directory must be empty or missing. `-R` writes `standby.signal` and adds the connection settings to `postgresql.auto.conf`. The volume must mount at `/var/lib/postgresql` (the same as the current primary).
- The defaults are already fine (`wal_level=replica`, `max_wal_senders=10`, `hot_standby=on`). The spine is correct not to add them. Suggest a named slot (`-C -S replica1`) so WAL is kept while the replica is down. Otherwise the temporary slot ends when the backup ends. This is a choice, not a blocker.

### F3 — [MEDIUM] Spring Boot 4.1 has properties for static master/replica that change how AD-3 and AD-6 are wired

- **Jar check (`spring-boot-data-redis-4.1.2.jar`, configuration metadata):** `spring.data.redis.masterreplica.nodes` (List of `host:port`) and `spring.data.redis.lettuce.read-from` (String, parsed into `io.lettuce.core.ReadFrom`). Boot's `LettuceConnectionConfiguration` also uses a user `RedisStaticMasterReplicaConfiguration` bean through `ObjectProvider`. This was added around Boot 4.0 (PR #47401, milestone 4.0.0-RC1).
- So one of the two factories (the master one, `read-from: upstream`) can be **auto-configured from YAML**. Then only the replica factory is declared by hand. The spine should either use this or say why it hand-builds both. If both are hand-built, Boot's `redisConnectionFactory` backs off (`@ConditionalOnMissingBean`). The repo's current `spring.data.redis.host/port`, `timeout: 1s` (REL-05) and the Redis health indicator then stop applying unless the code copies them.
- The same applies to Postgres (AD-3). The repo now uses Boot's auto-configured DataSource with JPA, Flyway, Batch and ShedLock on it. Boot docs ("Configure Two DataSources") say to declare the extra one with `@Bean(defaultCandidate = false)` + `@Qualifier`, so the auto-configured primary DataSource does **not** back off. AD-3 says "`@Primary` on `primaryJdbcClient`". That does not cover Flyway, JPA, Batch or ShedLock, which bind to the `DataSource`, not to `JdbcClient`. Suggest: the primary DataSource stays auto-configured, the replica DataSource is `defaultCandidate=false` with a qualifier, and the qualifier string goes in a constants class (as the spine already says). I did not test with a jar whether Boot's `@ConditionalOnMissingBean` also ignores a `defaultCandidate=false` `RedisConnectionFactory`. Check this during the build before you use the same pattern for Redis.

### F4 — [MEDIUM] `preferSecondary` fallback is decided once per connection: connections stay on the primary after the replica comes back (AD-5, AD-7)

- pgjdbc docs: the host is chosen when the connection opens. Hosts are tried in order, primary or secondary is decided by "if the server allows writes", and host status is cached for `hostRecheckSeconds` (default 10 s).
- With Hikari, a connection opened on the primary while the replica was down stays on the primary until `maxLifetime` (Hikari default 30 min). Reads then do not go back to the replica. That is safe, but the spine's "degrades" story should say so, and the replica pool should get a short `maxLifetime` if going back to the replica matters.
- AD-5 edge case (worth one line): if the gate's replica pool has fallen back to the primary, the gate passes at once and INCR runs. Availability readers whose pool went back to a **lagging** replica can then rebuild under the new version from stale data. This is the exact failure AD-5 is meant to prevent. It is rare (the replica must be down and then come back lagging), but the gate should check that its connection really is a standby (`pg_is_in_recovery()`). Otherwise it should say it accepts this risk.

### F5 — [LOW] Names confirmed. Two small corrections

- **pgjdbc 42.7.14:** `HostRequirement` enum has `any, master, primary, secondary, preferSecondary, preferPrimary`. Both `targetServerType=primary` and `targetServerType=preferSecondary` are valid. `master`/`slave` are deprecated aliases. The spine uses the current names. Confirmed.
- **Lettuce 7.5.2:** `ReadFrom.UPSTREAM` and `ReadFrom.REPLICA_PREFERRED` exist (`MASTER`/`SLAVE_*` are older aliases). **Spring Data Redis 4.1.2:** `RedisStaticMasterReplicaConfiguration` and `StaticMasterReplicaConnectionProvider` exist. Confirmed.
- Correction to AD-6: in a master/replica connection, **write commands always go to the upstream, whatever the ReadFrom**. `ReadFrom` only routes reads. So `SET NX`, `SET slots`, `INCR`, `EXPIRE` would hit the master even on the replica-preferred factory. The real reason for a separate `UPSTREAM` factory is the **version `GET`** (and any read-before-write). The rule is still correct. Its stated reason ("taking a rebuild lock on a read-only node") is not the real risk. Note also that Spring Data docs say `RedisStaticMasterReplicaConfiguration` does not support Pub/Sub. The app does not use it now, but say so so nobody adds it later.
- **Redis:** `replicaof <host> <port>` and `REPLICAOF NO ONE` are correct, and `replica-read-only` is **already `yes` by default** (since 2.6). The flag is harmless but redundant. Keep it as documentation or drop it.
- **Redis cache-only (no persistence) + replication:** the Redis docs warn that a master with persistence off that restarts automatically comes back empty and **wipes its replicas**. For a cache this is acceptable, and versioned keys keep it consistent. But AD-1 should say so in one line, and the compose `restart:` policy should be chosen on purpose. This also supports AD-2's "old primary never restarts as writable" for Redis. Today compose would restart it as a master.

## Unverified / not checked

- I did not bring up `pg_basebackup -R` end to end in compose. F2 is based on the entrypoint source and the PG docs. Run it once in the build story, and check that the generated `primary_conninfo` holds the password, or pass it through a `.pgpass`/`PGPASSWORD` env var on the replica.
- I did not confirm whether Boot's `@ConditionalOnMissingBean` ignores a `defaultCandidate=false` `RedisConnectionFactory` (F3).
- The behaviour of `LettuceConnectionFactory.resetConnection()` after a promote (F1, fix 2) comes from the API. Test it in the runbook drill.

## Sources

- Lettuce HA and topology: https://redis.github.io/lettuce/ha-sharding/
- Lettuce MasterReplica API: https://lettuce.io/core/release/api/io/lettuce/core/masterreplica/MasterReplica.html
- Spring Data Redis connection modes: https://docs.spring.io/spring-data/redis/reference/redis/connection-modes.html
- Spring Boot two DataSources: https://docs.spring.io/spring-boot/how-to/data-access.html
- Spring Boot PR #47401 (static master/replica node validation, 4.0.0-RC1): https://github.com/spring-projects/spring-boot/pull/47401
- pgjdbc connection parameters: https://jdbc.postgresql.org/documentation/use/
- Postgres image: https://hub.docker.com/_/postgres
- Postgres image entrypoint: https://github.com/docker-library/postgres/blob/master/18/alpine3.24/docker-entrypoint.sh
- PG 18 pg_hba.conf: https://www.postgresql.org/docs/18/auth-pg-hba-conf.html
- PG 18 pg_basebackup: https://www.postgresql.org/docs/18/app-pgbasebackup.html
- Redis replication: https://redis.io/docs/latest/operate/oss_and_stack/management/replication/
- Jar checks (local): `lettuce-core-7.5.2.RELEASE.jar`, `postgresql-42.7.14.jar`, `spring-data-redis-4.1.2.jar`, `spring-boot-data-redis-4.1.2.jar` under `~/.gradle/caches/modules-2/files-2.1/`
