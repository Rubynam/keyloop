# Spec02 — Nginx Load Balancer in front of `server-api`

> Keyloop Technical Assessment · Scenario A: The Unified Service Scheduler
> Roadmap: **Phase 2 — Scaffolding (extension)** · Builds on [Spec01](Spec01.md)
> Status: ✅ Implemented · Owner: Dai Nam

---

## 1. Goal

Put **one Nginx load balancer** in front of the main service (`server-api`) so that the API runs as **several identical instances** behind a single entry point. When Spec02 is done:

1. Clients call **only Nginx** (`localhost:8080`). No `server-api` instance publishes a host port any more.
2. By default **2 `server-api` instances** run, and Nginx spreads requests across them.
3. Nginx and the API instances talk over an **internal Docker network**; the API talks to Postgres/Redis over a **second network** that Nginx is not on.
4. When a **new `server-api` instance is started** (`--scale server-api=3`), Nginx **discovers it automatically** and starts routing to it — **no Nginx reload, no config edit**. When an instance stops, Nginx stops sending traffic to it.

No business logic changes. The API stays stateless (all shared state is in Postgres/Redis — Spec01 §2), which is what makes horizontal scaling safe.

---

## 2. Target topology

```
                     host :8080
                         │
                 ┌───────▼────────┐   network: edge (bridge, published)
                 │     nginx      │
                 └───────┬────────┘
                         │           network: lb-net (internal: true)
          ┌──────────────┼──────────────┐
   ┌──────▼──────┐ ┌─────▼───────┐ ┌────▼────────┐
   │ server-api#1│ │ server-api#2│ │ server-api#N│  ← started later, auto-discovered
   └──────┬──────┘ └─────┬───────┘ └────┬────────┘
          └──────────────┼──────────────┘
                         │           network: data-net
        ┌────────────────┼──────────────────┐
   ┌────▼─────┐     ┌────▼────┐      ┌──────▼──────────┐
   │ postgres │     │  redis  │      │ scheduler-batch │
   └──────────┘     └─────────┘      └─────────────────┘
```

| Network | Members | `internal` | Purpose |
|---|---|---|---|
| `edge` | nginx | no | Only network with a published port. The single public entry point. |
| `lb-net` | nginx, server-api | **yes** | LB → API traffic. No route to the outside world. |
| `data-net` | server-api, scheduler-batch, postgres, redis | no (dev) | App → data traffic. **Nginx is not on it**, so the edge can never reach the DB/cache directly. |

> ℹ️ `data-net` stays non-internal in the local compose only because Spec01 INF-10 publishes Postgres/Redis ports for IDE runs (Docker cannot publish ports from a container that is only on an internal network). In a production-like profile it should be `internal: true` with no published DB/cache ports.

---

## 3. Scope

### In scope
- New `nginx` service + config in `infra/nginx/`.
- Split compose into the 3 networks above.
- `server-api`: no host port, 2 replicas by default, scalable with `--scale`.
- **Dynamic service discovery** of API instances via Docker's embedded DNS.
- Passive health checking + safe retry to another instance.
- Forwarded headers (`X-Forwarded-*`, `X-Request-Id`) so the API sees the real client.
- Graceful shutdown of API instances (no dropped in-flight requests on scale-down).

### Out of scope
- TLS termination (HTTPS) — later / production concern.
- Active health checks (Nginx OSS has none; NGINX Plus only).
- Rate limiting at Nginx — rate limiting stays in the API on Redis (ROADMAP Phase 3), because it is per-client business policy and must be shared across instances.
- Kubernetes Service/Ingress (same idea, different platform).
- Scaling Nginx itself (single LB is a SPOF — accepted for the assessment, see §9).

---

## 4. Discovery design — how Nginx learns about new instances

### 4.1 The problem

Classic Nginx resolves `server server-api:8080;` **once at startup** and caches the IPs forever. A replica started later has a new IP that Nginx never sees; a stopped replica's IP stays in the pool until reload.

### 4.2 Options considered

| Option | How | Verdict |
|---|---|---|
| A. Static upstream list + manual `nginx -s reload` | Edit config per instance | ❌ Fails goal 4. |
| B. `proxy_pass http://$api;` with a variable + `resolver` | Re-resolves on TTL; Docker DNS returns all replica IPs | ⚠️ Works, but loses the `upstream` block: no `least_conn`, no `keepalive`, no `max_fails`. |
| **C. `upstream` + `server … resolve` (Nginx OSS ≥ 1.27.3)** | Upstream group in shared memory `zone`, re-resolved by `resolver` every *valid* seconds | ✅ **Chosen.** Keeps all upstream features, zero extra containers. |
| D. Traefik / nginx-proxy + docker-gen | Watch the Docker socket, regenerate config | ❌ Needs `/var/run/docker.sock` mounted into an internet-facing container (= root on the host). Extra moving part. |

`resolve` on upstream servers used to be NGINX Plus only; it is in **open-source Nginx since 1.27.3** (and therefore in the 1.28+ stable line).

### 4.3 How it works

1. Compose gives every `server-api` replica the DNS alias `server-api` on `lb-net`.
2. Docker's embedded DNS (`127.0.0.11`) answers `server-api` with **one A record per running replica**.
3. Nginx re-queries every `valid=5s`. New IPs are **added** to the upstream group, vanished IPs are **removed** — at runtime, without reload.
4. Worst case a new instance gets traffic ≤ 5 s after its container starts; a stopped one is dropped ≤ 5 s after it exits (and passive health covers the gap, §4.4).

### 4.4 Readiness gap — important

Docker DNS registers a container **as soon as it starts**, **not** when its healthcheck turns healthy. Spring Boot needs several seconds (Flyway, pools) before it listens. So Nginx can pick an instance that is not ready yet.

Mitigation (required):
- `proxy_next_upstream error timeout http_502 http_503;` — a **connection refused** means the request was never sent, so Nginx safely retries it on another instance **even for `POST`** (non-idempotent requests are only *not* retried once they were actually sent).
- `max_fails=1 fail_timeout=5s` — the refused instance is skipped for 5 s, then tried again.
- `proxy_connect_timeout 2s` — fail fast instead of hanging.
- API readiness group already includes `db` and `redis` (Spec01 API-06); the API returns `503` from `/actuator/health/readiness` while not ready.

> ⚠️ `POST /bookings` must **not** be retried after it reached an instance (double-booking risk). Do **not** add `non_idempotent` to `proxy_next_upstream`. Correctness is still protected by the DB exclusion constraint (D1), but we don't rely on it for LB behaviour.

---

## 5. Requirements

### 5.1 Nginx service

| ID | Requirement |
|---|---|
| LB-01 | Official image, **stable line pinned by minor tag**, version **≥ 1.27.3** (implemented with `nginx:1.30-alpine`). No `latest`. |
| LB-02 | Config mounted read-only from `infra/nginx/nginx.conf` (+ `conf.d/api.conf`). No custom image needed. |
| LB-03 | Publishes `${API_PORT:-8080}:80` → clients keep using `localhost:8080` (no change for README/curl from Spec01). |
| LB-04 | Networks: `edge` + `lb-net` only. **Not** on `data-net`. |
| LB-05 | `resolver 127.0.0.11 valid=5s ipv6=off;` (Docker embedded DNS). |
| LB-06 | `upstream server_api { zone server_api 64k; least_conn; server server-api:8080 resolve max_fails=1 fail_timeout=5s; keepalive 32; }` |
| LB-07 | Upstream keepalive: `proxy_http_version 1.1;` + `proxy_set_header Connection "";`. |
| LB-08 | Retry policy as in §4.4: `proxy_next_upstream error timeout http_502 http_503; proxy_next_upstream_tries 2; proxy_connect_timeout 2s; proxy_read_timeout 30s;` |
| LB-09 | Forwarded headers: `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Request-Id` (`$request_id` if client sent none). |
| LB-10 | Debug header **`X-Upstream-Addr: $upstream_addr`** on responses (local profile) — lets us *see* which instance answered. |
| LB-11 | Access log includes `$request_id`, `$upstream_addr`, `$upstream_status`, `$upstream_response_time`. |
| LB-12 | `/actuator/**` is **not** exposed through the LB (`location /actuator/ { return 404; }`), except `GET /actuator/health` for the smoke test. |
| LB-13 | LB own liveness: `location = /nginx-health { access_log off; return 200 "ok"; }` used by the compose healthcheck. |
| LB-14 | `depends_on: server-api: condition: service_healthy` — Nginx starts after the first replicas are up (startup convenience only; discovery handles later changes). |
| LB-15 | Upstream errors (`502/503/504`) returned as JSON `ProblemDetail`-shaped body, consistent with API-08. |

### 5.2 `server-api` changes

| ID | Requirement |
|---|---|
| API-10 | **Remove the `ports:` mapping** — instances are reachable only through Nginx. (Replicas can't share a host port anyway.) |
| API-11 | `deploy.replicas: 2` → `docker compose up` starts 2 instances by default. Must stay `container_name`-free so `--scale` works. |
| API-12 | Networks: `lb-net` (alias `server-api`) + `data-net`. |
| API-13 | `server.forward-headers-strategy: framework` → `request.getRemoteAddr()`, scheme and redirects reflect the real client, not Nginx. |
| API-14 | Future rate limiter (Phase 3) must key on the client IP taken from the trusted forwarded header — **otherwise every client looks like the Nginx IP and shares one bucket**. Note added to ROADMAP. |
| API-15 | `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`; compose `stop_grace_period: 30s`. Scale-down finishes in-flight requests. |
| API-16 | Log `X-Request-Id` in MDC so one request can be traced across Nginx log and API log. |
| API-17 | Container port stays fixed at `8080` inside the container (do not let host-side `API_PORT` change the container port). |

### 5.3 Other services

| ID | Requirement |
|---|---|
| NET-01 | `postgres`, `redis` → `data-net` only. |
| NET-02 | `scheduler-batch` → `data-net` only (it has no reason to be reachable from Nginx). Its `depends_on server-api: service_healthy` (Spec01 RUN-03) stays. |
| NET-03 | `docker-compose.scale.yml` (Spec01 RUN-04) keeps working for `scheduler-batch`; no override needed for `server-api` any more. |

---

## 6. Reference config (sketch)

`infra/nginx/conf.d/api.conf`

```nginx
resolver 127.0.0.11 valid=5s ipv6=off;
resolver_timeout 2s;

upstream server_api {
    zone server_api 64k;               # shared memory → required for `resolve`
    least_conn;
    server server-api:8080 resolve max_fails=1 fail_timeout=5s;
    keepalive 32;
}

server {
    listen 80;

    location = /nginx-health { access_log off; return 200 "ok\n"; }
    location = /actuator/health { proxy_pass http://server_api; }
    location /actuator/ { return 404; }

    location / {
        proxy_pass http://server_api;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Request-Id      $request_id;

        proxy_connect_timeout 2s;
        proxy_read_timeout    30s;
        proxy_next_upstream   error timeout http_502 http_503;
        proxy_next_upstream_tries 2;

        add_header X-Upstream-Addr $upstream_addr always;
    }
}
```

`docker-compose.yml` (delta)

```yaml
services:
  nginx:
    image: nginx:1.30-alpine
    ports: ["${API_PORT:-8080}:80"]
    volumes:
      - ./infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./infra/nginx/conf.d:/etc/nginx/conf.d:ro
    networks: [edge, lb-net]
    depends_on:
      server-api: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/nginx-health"]

  server-api:
    # ports: removed
    deploy: { replicas: 2 }
    stop_grace_period: 30s
    networks:
      lb-net: { aliases: [server-api] }
      data-net: {}

networks:
  edge: {}
  lb-net: { internal: true }
  data-net: {}
```

---

## 7. Implementation Steps

1. **Networks** — add `edge`, `lb-net`, `data-net`; attach postgres/redis/batch to `data-net`. Verify Spec01 acceptance still passes.
2. **API** — remove port, add replicas, aliases, graceful shutdown, forward-headers strategy, request-id MDC.
3. **Nginx** — `infra/nginx/nginx.conf` + `conf.d/api.conf`, compose service, healthcheck.
4. **Verify discovery** — scale up/down while running a request loop (§8).
5. **Docs** — README "Running behind the load balancer"; update DLD §13 deployment diagram (`docs/diagrams/dld/13-deployment.mermaid`); ROADMAP: link Spec02 + note on rate-limit key (API-14).

---

## 8. Acceptance Criteria

- [x] `docker compose up -d --build` → `nginx`, 2× `server-api`, `scheduler-batch`, `postgres`, `redis` all **healthy**.
- [x] `docker compose ps server-api` shows **2** containers, **no** published ports.
- [x] `curl -i localhost:8080/api/v1/ping` → `200 {"status":"ok"}` with an `X-Upstream-Addr` header.
- [x] `for i in $(seq 10); do curl -s -o /dev/null -D - localhost:8080/api/v1/ping | grep X-Upstream; done` → **2 distinct** upstream IPs.
- [x] **Scale up:** `docker compose up -d --scale server-api=3 --no-recreate` → within ~10 s (start-up + `valid=5s`) the loop above shows **3 distinct** IPs. **No Nginx reload or restart** was done (`docker compose ps nginx` uptime unchanged).
- [x] **Scale down / crash:** `docker compose up -d --scale server-api=2` (or `docker kill` one replica) while running `hey -z 20s localhost:8080/api/v1/ping` (or a curl loop) → **0 non-2xx** responses for `GET`.
- [x] **Not-ready instance:** starting a new replica during the load loop produces no `502` for the client (retry to a healthy instance, §4.4).
- [x] `curl localhost:8080/actuator/env` → `404`; `curl localhost:8080/actuator/health` → `UP`.
- [x] From inside nginx: `docker compose exec nginx nc -z postgres 5432` **fails** (nginx is not on `data-net`).
- [x] `docker network inspect keyloop-scheduler_lb-net` → `"Internal": true`, members = nginx + every `server-api` replica only.
- [x] `X-Request-Id` is forwarded, echoed and put in the API MDC (`RequestIdFilterTest`); nginx log has `rid=`. *API-side log line pending: `/ping` logs nothing yet — re-check with the first endpoint that logs.*
- [x] Spec01 acceptance criteria still pass (with `:8080` now served via Nginx).

---

## 9. Trade-offs & Open Questions

- **Single Nginx = single point of failure.** Acceptable locally. Production: 2 LBs + keepalived/VIP, or a cloud LB / K8s Ingress in front.
- **Passive health only.** Nginx OSS cannot probe `/actuator/health/readiness`; a slow-but-alive instance is only detected by timeouts. Alternative if needed later: Traefik/Envoy (active checks) — at the cost of Docker socket access or a control plane.
- **DNS TTL `valid=5s`** — lower = faster discovery, more DNS queries. 5 s chosen as a balance.
- [ ] `least_conn` vs `round_robin` — `least_conn` chosen because booking requests (DB transactions) vary in duration.
- [ ] Should `scheduler-batch` actuator also go behind Nginx (e.g. `/batch/actuator/health`), or stay internal?
- [ ] Production profile: make `data-net` `internal: true` and drop Postgres/Redis host ports.
- [ ] TLS at Nginx (self-signed for demo) — in this spec or Phase 5?
