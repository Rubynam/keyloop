# Shared proxy settings for server-api locations (not a *.conf, so not loaded on its own)

# Upstream keepalive
proxy_http_version 1.1;
proxy_set_header Connection "";

proxy_set_header Host              $host;
proxy_set_header X-Real-IP         $remote_addr;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Port  $server_port;
proxy_set_header X-Request-Id      $req_id;

# Docker DNS lists a replica before Spring is listening. A refused connection was never
# sent, so it is retried on another replica (even POST). Requests that reached a replica
# are NOT retried when non-idempotent: no `non_idempotent` here (double-booking risk).
proxy_connect_timeout     2s;
proxy_read_timeout        30s;
proxy_next_upstream       error timeout http_502 http_503;
proxy_next_upstream_tries 2;

# Local debugging: which replica answered (Spec02 LB-10)
add_header X-Upstream-Addr $upstream_addr always;
