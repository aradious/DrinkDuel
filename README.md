# DrinkDuel

Initial project skeleton only. No UI screens or game features are implemented.

## Structure

```text
DrinkDuel/
  frontend/    Angular standalone application with an empty router outlet
  backend/     Spring Boot application and Maven Wrapper
  docs/        Project scope and future visual direction
  README.md
  .gitignore
```

## Toolchain

- Angular and Angular CLI 22.2.0 (project-local CLI)
- Spring Boot 4.1.1
- JDK 21 (verified with Eclipse Temurin 21.0.12.1)
- Node.js 24.21.0 and npm 11.19.0
- Maven 3.9.16 via Maven Wrapper

## Frontend

From `frontend/`:

```powershell
npm ci
npm start
```

The development server uses `http://localhost:4200`. A blank page is expected: routing is configured, but there are no routes or UI screens yet.

Build for production with `npm run build`. Output is written to `frontend/dist/drinkduel/`.

The application uses standalone components, SCSS, strict TypeScript and Angular template checks, and a mobile viewport. Future layouts should be mobile-first. Angular CLI analytics are disabled. No global Angular CLI installation is needed.

## Backend

Set `JAVA_HOME` to a JDK 21 installation. From `backend/`:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

On macOS/Linux, use `./mvnw` instead. The backend uses port 8080; Actuator health is available at `http://localhost:8080/actuator/health` while running.

Dependencies include Spring Web, Spring WebSocket, Validation, and Actuator. A context-load test verifies application startup. There are no application controllers, WebSocket events, authentication, or game services. Future game state will live only in server memory. There is no database, JPA, Redis, or Docker.

## Windows certificate trust

This environment required the Windows certificate store for dependency downloads. If certificate verification fails, set these variables in the current PowerShell session before the relevant commands:

```powershell
$env:NODE_OPTIONS = "--use-system-ca"
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
```

These settings preserve TLS verification and do not change global configuration.

## Scope

See [project scope](docs/project-scope.md) for the future art-toy visual direction and deferred features. No runtime 3D rendering is allowed.

Git is initialized at this project root. No remote or GitHub push is configured.
