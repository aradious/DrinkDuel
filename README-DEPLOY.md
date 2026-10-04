# DrinkDuel Standalone Deployment

## Artifact and runtime

Deploy `drinkduel.jar` with Java 21. The application listens on port `8080` by default and serves the complete product from one process:

| Path | Service |
|---|---|
| `/` | Angular frontend and client routes |
| `/api` | REST backend |
| `/ws` | WebSocket realtime transport |
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

The browser uses same-origin paths:

- REST requests use `/api`.
- Realtime connections use `/ws`.
- The client selects `ws://` for HTTP and `wss://` for HTTPS.

Public deployments should use HTTPS so the default secure Host session cookie works correctly.

## Reverse proxy or load balancer

If TLS terminates at a load balancer, Nginx, Apache, Ingress, or another proxy, it must:

- preserve the application paths;
- forward normal HTTP traffic to the JAR;
- forward the original host and request scheme using trusted forwarding headers;
- support HTTP Upgrade for `/ws` WebSocket connections; and
- avoid sending `/api`, `/ws`, or `/actuator` requests through an external SPA fallback.

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
