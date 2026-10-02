# DrinkDuel V1 Deployment

## Intended topology

DrinkDuel uses one public origin:

```text
Browser
  |
  v
https://example.com
  |-- /       Angular static files and SPA routes
  |-- /api/*  Spring Boot HTTP API
  `-- /ws/*   Spring Boot WebSocket
```

Keep Spring Boot private where practical. The public reverse proxy or platform terminates TLS and forwards API and WebSocket traffic to it. DrinkDuel does not depend on a specific proxy product.

## Build artifacts

From `frontend/`:

```powershell
npm ci
npm test
npm run build
```

Serve `frontend/dist/drinkduel/browser/` as the static web root.

From `backend/` with JDK 21:

```powershell
.\mvnw.cmd verify
```

The executable artifact is `backend/target/drinkduel-0.0.1-SNAPSHOT.jar`.

## Production startup

Run one backend process with the normal configuration. Do not enable `local-preview`:

```powershell
$env:SERVER_PORT = "8080"
$env:DRINKDUEL_SECURE_COOKIES = "true"
java -jar backend/target/drinkduel-0.0.1-SNAPSHOT.jar
```

`SERVER_PORT` is Spring Boot's standard port setting. The default is `8080`, so omit it when the platform supplies or accepts that port.

## Configuration

### Required

No application-specific environment variable is required for the current anonymous Host and guest-player model. The deployer must provide public HTTPS, static hosting, and reverse proxy behavior described below.

### Optional production values

| Setting | Default | Purpose |
|---|---:|---|
| `SERVER_PORT` | `8080` | Backend listen port supplied through standard Spring Boot configuration. |
| `DRINKDUEL_SECURE_COOKIES` | `true` | Host session cookie security. It must remain `true` in production. |

### Development only

| Setting | Purpose |
|---|---|
| Spring profile `local-preview` | Binds the backend to loopback and allows an insecure cookie for local HTTP. Never use in production. |
| `drinkduel.dev-identity.enabled=true` | Enables `/api/dev/session`, but only together with `local-preview` and never with `prod` or `production`. |

Do not put secrets in committed property files. V1 has no OAuth, database, or third-party API credentials.

## URLs and authentication

The frontend needs no domain-specific build setting:

- HTTP calls use relative `/api/...` paths.
- WebSocket uses the browser origin and converts `http` to `ws` and `https` to `wss`.
- QR join links use the browser's current origin.

The Host establishes an opaque, server-generated identity through `/api/host/session`. The browser receives only a server session cookie. In production it is `Secure`, `HttpOnly`, and `SameSite=Strict`. Mutating cookie-authenticated requests must pass same-origin checks. Players use stable guest credentials held by their browser.

The proxy must preserve the public host and scheme so Spring can validate origins and create secure sessions correctly. `server.forward-headers-strategy=framework` is enabled. Forward `Host`, `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Port`, and overwrite rather than append untrusted client-supplied forwarding headers.

## Reverse proxy requirements

The proxy or hosting platform must:

1. Redirect public HTTP to HTTPS.
2. Serve the Angular build at `/`.
3. Proxy `/api/*` to the private Spring Boot process without removing the `/api` prefix.
4. Proxy `/ws/*` without removing the `/ws` prefix and support HTTP Upgrade.
5. Forward the public host and HTTPS scheme.
6. Return Angular `index.html` for unknown frontend routes.
7. Keep `/api/*` and `/ws/*` out of the SPA fallback.

### Generic nginx example

This example illustrates the required behavior. Paths, TLS setup, and backend address are deployment-specific.

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    server_name example.com;

    root /srv/drinkduel/frontend;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_read_timeout 75s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

TLS certificates and the port-80 redirect belong to the hosting environment and are intentionally omitted.

## SPA routing

Routes such as `/room/ABC123`, `/room/ABC123/games`, and `/join?room=ABC123` must return Angular's `index.html` when opened or refreshed directly. Match `/api/` and `/ws/` before the SPA fallback so backend requests never receive HTML.

## V1 runtime limits

Run exactly one Spring Boot application instance:

- Room state is in memory.
- Host HTTP sessions are in memory.
- Restarting the backend removes active rooms and Host sessions.
- Horizontal scaling is unsupported. Do not put independent backend instances behind a load balancer.
- Future scaling requires shared room and session storage or a redesigned coordination layer.

A room has a fixed maximum lifetime of 48 hours from its creation time. Activity does not extend it. This application rule does not make the room survive a process restart.

## Health and smoke test

First confirm `GET /actuator/health` returns an `UP` response through the intended private or monitored path. Exposure of Actuator beyond health should remain disabled unless the hosting environment secures it.

After deployment:

1. Open the public HTTPS URL.
2. Create a room as Host and confirm the secure Host session works.
3. Open the generated QR/join URL on another device and join as a Player.
4. Confirm membership and presence update in realtime.
5. Submit names and shuffle.
6. During PLAYING, verify each participant's own assigned name remains hidden.
7. Exercise Got It and Undo.
8. End the game and verify Roast when exactly one participant remains PLAYING.
9. Continue to Reveal and start Play Again.
10. Refresh/reconnect both Host and Player.
11. Confirm realtime reconnection succeeds over WSS.

## Hosting handoff checklist

- Use the normal production configuration; never enable `local-preview`.
- Run one backend process on a private port.
- Serve the Angular browser output over HTTPS.
- Proxy `/api` and `/ws` to the same backend.
- Enable WebSocket Upgrade and forward trusted public host/scheme headers.
- Configure SPA fallback only for frontend paths.
- Keep `DRINKDUEL_SECURE_COOKIES=true` if explicitly supplied.
- Expect active rooms and Host sessions to disappear on backend restart.
