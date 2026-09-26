# DrinkDuel project scope

## Initial skeleton

- Frontend: Angular 22, standalone components, routing, SCSS, and strict TypeScript.
- Backend: Spring Boot with Java 21 and Maven Wrapper.
- Backend dependencies: Spring Web, Spring WebSocket, Validation, and Actuator.
- Future game state lives only in server memory. There is no persistence layer.
- No database, JPA, Redis, or Docker.

## Future visual direction

Develop mobile-first layouts with a cute collectible art-toy / blind-box aesthetic, pastel colors, rounded UI, and soft shadows.

Characters should look like 3D rendered toys, but must use optimized 2D image assets, preferably WebP or AVIF. Do not introduce Three.js, WebGL, GLB, 3D engines, or runtime 3D rendering.

These are constraints for future work. The initial skeleton includes no UI screens, theme, design system, or avatar assets.

## Deferred functionality

Do not add Google login, authentication, rooms, players, game logic, WebSocket events, Who Am I, or analytics during initial setup. The WebSocket dependency is present without application endpoints or event handlers. Actuator supplies operational health support; no product analytics are implemented.
