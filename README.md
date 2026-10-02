# DrinkDuel

DrinkDuel is a mobile-first party game for 2–20 people. V1 includes the complete **Who Am I?** flow with realtime room synchronization, anonymous Host sessions, guest players, and server-authoritative game state.

## Technology

- Angular 22, standalone components, strict TypeScript, SCSS
- Spring Boot 4.1.1, Java 21, Maven Wrapper
- Native Spring WebSocket
- In-memory rooms and Host sessions
- No database, Redis, Docker, or runtime 3D engine

## Repository

```text
frontend/   Angular application and static artwork
backend/    Spring Boot API, WebSocket server, domain, and tests
docs/       Product, architecture, design, and deployment specifications
run-dev.bat Windows local-preview launcher
```

The product rules live in [PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md). See [DEPLOYMENT.md](docs/DEPLOYMENT.md) for the production topology, configuration, reverse-proxy example, limitations, and smoke test.

## Requirements

- JDK 21 (`JAVA_HOME` must point to it)
- Node.js 24 and npm 11, matching the currently verified toolchain
- No global Angular CLI or Maven installation is required

## Local development

Install frontend dependencies once:

```powershell
cd frontend
npm ci
```

On Windows, run `run-dev.bat` from the repository root. It starts:

- Spring Boot on `127.0.0.1:8080` with the explicit `local-preview` profile
- Angular on `127.0.0.1:4200`, proxying `/api` and `/ws` to Spring Boot

The development identity endpoint is enabled only by that explicit profile plus its opt-in flag. It is unavailable in the normal production configuration.

To run the processes separately:

```powershell
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local-preview -Dspring-boot.run.arguments=--drinkduel.dev-identity.enabled=true
```

```powershell
cd frontend
npm start
```

On macOS/Linux, use `./mvnw` in place of `mvnw.cmd`.

## Tests and production builds

```powershell
cd frontend
npm test
npm run build
```

Serve the files in `frontend/dist/drinkduel/browser/` as the production web root.

```powershell
cd backend
.\mvnw.cmd verify
```

Run the executable backend artifact with:

```powershell
java -jar target/drinkduel-0.0.1-SNAPSHOT.jar
```

The resulting JAR is `backend/target/drinkduel-0.0.1-SNAPSHOT.jar`.

## Production summary

Use one public HTTPS origin. Serve Angular at `/`, proxy `/api/*` to Spring Boot, and proxy `/ws/*` with WebSocket Upgrade support. Frontend API calls are relative, WebSocket chooses `ws` or `wss` from the page origin, and QR links use the current public origin.

Run exactly **one backend instance** for V1. Rooms and Host server sessions are held in memory. A backend restart loses active rooms and Host sessions, and independent backend replicas cannot share state. Rooms expire 48 hours after creation, but that lifetime does not provide persistence across restarts.

Production Host cookies default to `Secure`, `HttpOnly`, and `SameSite=Strict`. Public HTTPS and correct forwarded headers are required. Do not activate the `local-preview` profile in production.

## Certificate trust on Windows

If this machine's dependency downloads require the Windows certificate store, set these only in the current shell:

```powershell
$env:NODE_OPTIONS = "--use-system-ca"
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
```

These settings keep TLS verification enabled and do not change global configuration.
