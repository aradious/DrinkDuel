# DrinkDuel Standalone Deployment

## Artifact and runtime

Deploy `drinkduel.jar` with Java 21. The application listens on port `8080` by default and serves the complete product from one process:

| Path | Service |
|---|---|
| `/` | Angular frontend and client routes in a root build |
| `/api` | REST backend in a root build |
| `/ws` | WebSocket realtime transport in a root build |
| `/actuator/health` | Health check |

The runtime does not require Node.js, npm, Angular CLI, Docker, Nginx, a database, or Redis. A reverse proxy is optional when the deployment platform already supplies HTTPS and routing.

## Start the application

Activate the production Spring profile and keep secure cookies enabled for every public deployment.

Linux/server example:

```sh
SPRING_PROFILES_ACTIVE=production DRINKDUEL_SECURE_COOKIES=true java -jar drinkduel.jar
```

Windows PowerShell example:

```powershell
$env:SPRING_PROFILES_ACTIVE = "production"
$env:DRINKDUEL_SECURE_COOKIES = "true"
java -jar drinkduel.jar
```

Spring Boot's standard `SERVER_PORT` setting can change the default port when required by the hosting platform.

`DRINKDUEL_SECURE_COOKIES=false` is supported only for local, non-HTTPS testing. Never use it for a public production deployment. Do not enable the `local-preview` profile or development identity settings in production.

## Domain and HTTPS

DrinkDuel is not tied to a specific hostname. The deployment team may assign its own domain or subdomain, such as the conceptual `https://drinkduel.example.com`. Changing the hostname does not require rebuilding Angular.

The browser uses same-origin paths derived from the Angular build's base href:

- A root build uses `/api` and `/ws`.
- A `/drinkduel/` build uses `/drinkduel/api` and `/drinkduel/ws` publicly.
- The client selects `ws://` for HTTP and `wss://` for HTTPS.
- Angular assets, router links, REST requests, WebSocket connections, and QR join links all use the same configured public base path.

Public deployments should use HTTPS so the default secure Host session cookie works correctly.

## Configurable public base path

`APP_BASE_PATH` is a build-time setting for the standalone artifact. It must start and end with `/`. The default is `/`, so existing root and Docker deployments remain unchanged.

Build a root standalone JAR:

```powershell
Remove-Item Env:APP_BASE_PATH -ErrorAction SilentlyContinue
.\build-standalone.bat
```

Build the standalone JAR for `https://app.kook.in.th/drinkduel/`:

```powershell
$env:APP_BASE_PATH = "/drinkduel/"
.\build-standalone.bat
```

The second build writes `<base href="/drinkduel/">` into the packaged Angular shell. It also causes the browser to request assets, REST, WebSocket, SPA routes, and generated join links beneath `/drinkduel/`. The internal Spring endpoints remain `/api` and `/ws`.

Docker continues to build with its existing root base href. No Compose variable is required:

```powershell
docker compose build
docker compose up -d
```

Do not reuse a `/drinkduel/` JAR at a root public URL, or a root JAR at `/drinkduel/`; the public base path is part of the Angular build.

## Reverse proxy or load balancer

If TLS terminates at a load balancer, Nginx, Apache, Ingress, or another proxy, it must:

- preserve the application paths;
- forward normal HTTP traffic to the JAR;
- forward the original host and request scheme using trusted forwarding headers;
- support HTTP Upgrade for `/ws` WebSocket connections; and
- avoid sending `/api`, `/ws`, or `/actuator` requests through an external SPA fallback.

For a subpath standalone deployment, the proxy must strip the public prefix before forwarding to the JAR:

| Public request | JAR upstream request |
|---|---|
| `/drinkduel/` | `/` |
| `/drinkduel/main-*.js` | `/main-*.js` |
| `/drinkduel/room/ABC123` | `/room/ABC123` |
| `/drinkduel/api/rooms` | `/api/rooms` |
| `/drinkduel/ws/rooms` | `/ws/rooms` |

The `/drinkduel/ws/` route must preserve HTTP Upgrade and use a WebSocket-capable proxy configuration. Deep frontend routes must be forwarded after stripping the prefix so the JAR's SPA fallback can return its packaged `index.html`. API and WebSocket routes must never be rewritten to that SPA fallback.

Conceptual topology:

```text
Internet --HTTPS--> Reverse proxy / load balancer --HTTP or HTTPS--> DrinkDuel JAR :8080
```

The application enables Spring's framework forwarded-header handling. The proxy should set `Host`, `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Port`, replacing untrusted client-supplied values.

## Health check

Use:

```text
GET /actuator/health
```

A healthy instance returns HTTP 200 with status `UP`. This endpoint is suitable for deployment verification, service monitoring, and load-balancer health checks.

## V1 in-memory limitation

> **Restarting the JAR loses every active Room, game, and Host session.**

DrinkDuel V1 keeps Rooms, active games, and Host/session state in application memory. Run exactly one backend instance. Multiple independent instances behind a load balancer will not share Room state, so horizontal scaling is unsupported in V1.

## Artifact verification

The source repository does not contain the generated JAR. Build it with `build-standalone.bat`, then transfer `release/drinkduel.jar` and verify it against the SHA-256 checksum supplied with the deployment handoff.
