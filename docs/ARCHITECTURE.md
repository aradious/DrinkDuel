# DrinkDuel V1 Architecture

## Status and Authority

[PRODUCT_SPEC.md](PRODUCT_SPEC.md) is the V1 product source of truth. This document defines the proposed technical design needed to implement it; it does not introduce new gameplay rules. Nothing in this document has been implemented by this documentation step.

## Stack and Deployment Boundary

- Angular 22: standalone components, routing, strict TypeScript, SCSS, mobile-first web UI.
- Spring Boot 4.1.x with Spring Web, Spring WebSocket, Validation, and Actuator.
- JDK 21; backend builds and tests use Maven Wrapper.
- One Angular frontend and one Spring Boot backend process. Shared room and game state exists only in backend memory.
- No database, JPA, Redis, Docker, Kafka, RabbitMQ, microservices, Kubernetes, event sourcing, or CQRS.
- No Three.js, WebGL, GLB, or runtime 3D engine. Use optimized 2D assets, preferably WebP/AVIF.

V1 runs a single backend instance. Multiple independent instances would not share rooms. Distributed storage is a future replacement boundary, not V1 infrastructure.

## Application Responsibilities

### Frontend

Use small feature areas for identity/joining, the room, Who Am I, and reusable presentation. An Angular service owns the WebSocket connection and latest personalized snapshot, exposed through signals. Routes render server-confirmed phases; URL changes never advance the game.

Keep nickname search, status filters, confirmations, animations, and My Clues local. Filter the existing card order without reordering on result changes. Use ordinary services and signals rather than adding a global state framework for V1.

Disable unavailable controls using server-provided capabilities, but enforce every permission again on the server. Do not optimistically assign secrets, alter membership, or advance phases. The server response is authoritative.

### Backend

Separate transport adapters, identity checks, room application service, game rules, storage, and personalized view projection. These are responsibility boundaries, not a requirement for a class per operation.

The room service handles membership, owner authorization, expiration, and session replacement. A Who Am I rules component handles submissions, assignments, results, and its phases. A view projector creates outbound DTOs. Domain objects are never serialized directly.

Keep network connections and transport buffers outside the domain aggregate. Do not perform network I/O or Google verification while holding a room mutation lock.

## Domain Model

```text
Room
  players: Player[]
  currentSession: GameSession? -> GameState
                                  -> WhoAmIState in V1
```

| Concept | Responsibility and minimum data |
| --- | --- |
| Room | Short public room ID; creation and fixed expiration instants; Google owner subject; GM player ID; ordered players; optional current session; monotonic revision; blocked guest-identity fingerprints. No Who Am I fields. |
| Player | Stable internal player ID, nickname, avatar ID, identity binding, and derived connection state. The GM is also a Player. Identity binding is a guest credential fingerprint or an authenticated Google subject, not a nickname. |
| GameSession | Unique session/round ID, GameType, and game-specific GameState. A new round gets a new session ID. |
| GameType | V1 value WHO_AM_I; identifies which rules and view projector handle the session. No additional playable games in V1. |
| GameState | A small game-state contract/tag, not a universal model of every game's phases or fields. |
| WhoAmIState | Phase, ordered participant IDs, submissions, assignments, result statuses, and final Roast summary when the round ends. |
| SubmittedName | Value record containing submitter player ID and secret text. One confirmed submission per current participant. Identify by session and submitting player, never text; identical text from different players remains distinct. |
| WhoAmIAssignment | Value record containing recipient player ID, assigned text, original submitter ID, and submitter nickname captured for round attribution. |
| PlayerGameStatus | PLAYING, GOT_IT, or GAVE_UP. Separate from connection state and session phase. |
| RoomStore | Room creation, lookup, atomic mutation, and removal boundary. |
| InMemoryRoomStore | V1 map and per-room synchronization implementation of RoomStore. |

SubmittedName and WhoAmIAssignment can be records stored in maps inside WhoAmIState; they do not need repositories or independent services. Counts, joinability, and GM availability are derived, not separately maintained truth.

Future games add a GameType, game-specific state, rules handler, and view projection. Room membership, ownership, expiration, and transport remain unchanged. A small handler registry is sufficient; no plugin framework or generic workflow engine is needed.

## Lifecycle and State Ownership

A live Room with no currentSession is the Lobby. A Room with a currentSession delegates phase to its game state. Do not duplicate SUBMIT_NAME, PLAYING, ROAST, or REVEAL in Room.

| State / transition | Authoritative behavior |
| --- | --- |
| Room created | Owner is also a player; no game session; Lobby is joinable up to 20 members. |
| GM opens Who Am I | Validate at least 2 active players; create session in SUBMIT_NAME; capture current membership; close new joins. |
| SUBMIT_NAME | Each participant confirms one secret. GM can reset submissions or kick. A disconnected session member blocks Shuffle. |
| Shuffle | Atomic assignment operation requiring at least 2 active participants, valid submissions from all remaining participants, and no disconnected members. Commit directly to PLAYING. |
| Shuffle presentation | Client animation of approximately 0.7–1.0 seconds when possible; not a durable server phase or timer. It cannot delay authoritative gameplay. |
| PLAYING | Accept permitted result actions. No turn order, timer, Wrong, Next Turn, or automatic completion. |
| GM End Game | Freeze final round results, derive Roast presentation, enter ROAST. |
| ROAST | Remain until GM continues, including the all-GOT_IT success presentation. No auto-dismiss. |
| GM continues | Enter REVEAL and expose assignments and attribution. |
| Play Again from Reveal | Validate the new-session minimum before mutation; replace the session with fresh SUBMIT_NAME state. Keep Room, players, avatars, ID, and QR. Clear previous round data and local clues through session-ID change. |
| Back to Room | From Reveal, or the specified fewer-than-2 Submit Name recovery, remove the current session and return to Lobby. Joins and normal Players' voluntary Leave become available; GM has no Leave action. |
| Close / expire | Remove Room and associated state; send terminal notification. These are terminal outcomes, not retained Room states. |

New joins stay closed through Submit, Shuffle presentation, Playing, Roast, Reveal, and Play Again. Reconnecting existing members is a separate path that bypasses the new-join phase check, but never identity validation or kick revocation.

If a pre-Shuffle kick leaves fewer than 2 participants, disable Shuffle and show “หาเพื่อนมาอีกคนก่อนน้า 🍻”. GM can return to Lobby or close the room. After Shuffle, reduced membership/connection count never automatically terminates the round.

## Identity and Authorization

### Guest Players

Use a cryptographically random opaque Guest Token retained by the browser as its local guest identity. It is a bearer credential, distinct from the public/internal player ID. Store only its secure fingerprint in Room membership and the room's blocked-identity set. Do not send tokens in room snapshots, URLs, QR codes, or logs.

The initial join associates the credential with a new player ID. Reconnect looks up the same credential binding in the room and restores the same player and game state. Nickname alone never restores membership. A voluntary Lobby leave removes membership; a later allowed join creates a new player ID. A kick additionally blocks that credential for this room until the room is removed.

Guest credentials are presented through an authenticated attachment message over WSS, before any room data is sent. Validate the browser Origin and impose transport limits. A new identity cannot subscribe to a room merely by knowing its ID.

### GM

Conceptually use Google authentication through a backend-verified login flow and a secure HTTP session cookie. Validate the provider response and bind the session to the stable Google subject, not email, nickname, or a client-supplied role. OAuth configuration and implementation are deferred.

Room ownership stores that Google subject and the GM's player ID. An authenticated WebSocket handshake establishes the server-side principal. On every GM command, match the principal to Room ownership and current membership. Re-login with the same Google identity restores the original GM; a guest token or Room ID never grants GM authority. There is no transfer.

GM sees the same secret-information restrictions as other players. Administrative permission does not imply access to raw game state.

GM has no voluntary Leave Room action, including in the Lobby. Reject LEAVE_ROOM from the owner on the server and omit it from their capabilities. To permanently end the Room, GM uses Close Room. Closing the browser/app, losing connection, or temporarily leaving changes presence only; preserve Room membership and ownership so the same authenticated Google identity can resume control. Fixed expiration still applies. No transfer is introduced.

### Connection Presence

Maintain a transport registry of live connections bound to room/player IDs. A player is connected while at least one validated connection remains. Closing an old socket must not mark a newly reconnected player disconnected. Heartbeats and transport timeouts detect lost connections; these are infrastructure timeouts, not gameplay timers.

When all GM connections disappear, show “Waiting for Game Master... 🍺”. Preserve state. Player submissions, Give Up in PLAYING, local clues, and permitted viewing continue. Only GM-controlled operations are unavailable. Expiration remains effective.

## RoomStore and Expiration

RoomStore exposes conceptual operations to create-if-absent, read an immutable snapshot, atomically mutate a live room, and remove a room with a terminal reason. It must not return mutable objects that callers can modify outside its concurrency boundary.

InMemoryRoomStore uses a map keyed by Room ID with a per-room lock. Create IDs using a random short code and atomically retry collisions. Store UTC instants; expiresAt is always createdAt plus 48 hours. Activity, reconnect, and Play Again do not change it.

Check expiration on every lookup, attachment, and mutation. Also schedule cleanup so idle rooms are removed and connected clients are notified without sending a command. A lightweight periodic sweep can recover missed scheduled cleanup. Scheduler and request paths use the same atomic deletion operation, preventing duplicate terminal outcomes.

At expiry, capture terminal recipients, remove the aggregate and identity bindings, revoke room access, and enqueue ROOM_ENDED with reason EXPIRED. The frontend shows the themed expiration screen and Back Home. Manual closure sends reason CLOSED and returns clients Home. Release associated transport buffers and command caches after terminal delivery/disconnection.

No historical room record is required. A request that encounters a still-present expired room receives ROOM_EXPIRED; an old ID looked up after removal receives ROOM_NOT_FOUND. Connected clients receive the specific expiration notification. Do not add a permanent tombstone store merely to classify removed IDs.

On server restart, rooms, sessions, assignments, revocations, and connections disappear. Local guest credentials cannot reconstruct lost rooms; users receive a friendly unavailable-room response and must create a new room. This is the accepted V1 durability limit.

A future distributed RoomStore must preserve atomic mutation/version semantics, TTL, and terminal deletion. Replace local locking with transactional or compare-and-set operations there, without moving game rules into the store. Multiple backend instances would also require distributed identity/session and message delivery support; replacing the map alone would not provide that. None of this is built in V1.

## Concurrency and Command Processing

Serialize every membership change, game command, connection transition, and terminal removal for the same room. Different rooms can proceed independently. One room is the consistency boundary; no global game lock or thread per room is needed.

Within a room operation:

1. Resolve the current live entry and check its fixed expiration.
2. Authenticate the actor, verify membership/revocation, and check session ID and phase.
3. Check command preconditions against current state.
4. Apply the entire mutation atomically, increment the room revision, and produce immutable personalized outputs.
5. Enqueue outputs in revision order, then release the lock. Network sending occurs outside the lock.

Do not allow a command that held an old room reference to revive a deleted room. Removal marks the entry terminal under the same lock. Keep lock/entry lifetime tied to that room instance.

| Concurrent scenario | Resolution |
| --- | --- |
| Joins / nickname collision / last slot | Check uniqueness and capacity and insert the member in one operation. Disconnected members retain membership and count toward capacity. |
| Simultaneous submissions | Each actor can fill only their own empty submission slot; reset and submit serialize. |
| Shuffle racing with submit, kick, or disconnect | Evaluate all Shuffle conditions and create all assignments inside one operation. |
| Got It racing with Give Up | First valid mutation wins. The second fails its PLAYING precondition; no direct GOT_IT/GAVE_UP conversion. |
| End Game racing with a result | First committed operation determines the final snapshot; result changes are rejected after PLAYING ends. |
| Reconnect racing with kick | Check revoked membership under the room lock; kick also revokes every attached connection for that identity. |
| Close/expiration racing with commands | A terminal room accepts no further changes. |

Commands carry requestId and game commands carry sessionId. Correction/reset commands also carry the target's expected current state or submission version so a delayed reset cannot erase a newer submission/result. Reject stale commands and return a fresh view. Do not require matching the whole-room revision for independent submissions, which would reject unrelated concurrent players unnecessarily.

Keep a bounded, short-lived in-memory request-result cache per actor/room to suppress duplicate sends; no durable event history. Clients do not blindly retry uncertain non-idempotent actions. They resync first. Enforce phase and result preconditions even when a request ID has aged out of the cache.

## WebSocket Transport and Synchronization

Use native browser WebSocket with a small JSON protocol on Spring WebSocket. STOMP, SockJS, and an external broker are unnecessary for this single-server V1 design. HTTPS handles Google login and authenticated room creation; WSS handles room attachment and game commands.

A room channel is an internal registry of authorized connections, not a public topic carrying raw game state. Joining/resuming attaches a socket to one room and player. Attach plus initial personalized snapshot is coordinated with room mutations, so no update is lost between snapshot and subscription.

Send a complete personalized STATE snapshot after accepted shared mutations and on reconnect/resync. With at most 20 players, this is simpler than maintaining many delta reducers. Each snapshot has roomId, roomRevision, sessionId when present, the recipient's player ID, permitted room/game fields, and current allowed actions.

Clients replace their authoritative view with newer snapshots and ignore older revisions for the same room instance. A new connection performs a fresh attach before accepting updates. Transport notices and command errors are not authoritative game state.

Use ordered per-connection outbound queues with bounded buffers. Slow connections are disconnected and recover by snapshot instead of blocking a room indefinitely. Spring's standard WebSocket sessions require serialized sending; the implementation may use its [ConcurrentWebSocketSessionDecorator](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/handler/ConcurrentWebSocketSessionDecorator.html) alongside ordered enqueueing.

On connection loss, keep a read-only last snapshot for presentation, mark the connection unavailable, and reconnect with bounded backoff. Revalidate identity, membership, room lifetime, and session on attach. Return a fresh snapshot, including the current phase, rather than replaying old celebrations. GM reconnect uses the authenticated Google principal. A kicked guest is rejected even if their browser retained a previous snapshot.

## Minimum V1 Command and Message Model

Names below are protocol design, not implemented endpoints. Actor ID and role come from the server-bound connection. A targetPlayerId identifies the subject of a GM operation, never the command's authority.

### Client to Server

| Command / operation | Preconditions and intent |
| --- | --- |
| CREATE_ROOM (HTTPS) | Google-authenticated owner; create room and GM player; no game starts yet. |
| JOIN_ROOM | New guest, Lobby only, unique nickname, capacity available, credential not blocked. |
| RESUME_ROOM | Restore existing guest membership or authenticated GM; allowed in all live phases. |
| GET_STATE | Authorized member requests their current personalized snapshot. |
| LEAVE_ROOM | Normal Player only, Lobby only; remove membership. Reject this command from GM in every phase. |
| GM_START_GAME | Lobby, WHO_AM_I, at least 2 active players; begin Submit Name. |
| SUBMIT_NAME | Actor's own confirmed submission in SUBMIT_NAME; no direct edits afterward. |
| GM_RESET_SUBMISSION | SUBMIT_NAME; clear target submission and require confirmation again. |
| GM_KICK_PLAYER | Remove non-GM target and revoke room access; apply phase-specific cleanup. |
| GM_SHUFFLE | SUBMIT_NAME with all eligibility checks; assign and enter PLAYING atomically. |
| GIVE_UP | Actor is PLAYING in active gameplay; client confirmation required. |
| GM_MARK_GOT_IT | Target is PLAYING in active gameplay; GM may target themselves. |
| GM_RESET_PLAYER_STATUS | Active gameplay, target GOT_IT or GAVE_UP; reset to PLAYING. |
| GM_END_GAME | PLAYING; client confirmation required; freeze results and enter ROAST. |
| GM_CONTINUE_REVEAL | ROAST; enter REVEAL. |
| GM_PLAY_AGAIN | REVEAL; validate minimum, replace session, begin Submit Name. |
| GM_BACK_TO_ROOM | REVEAL or fewer-than-2 Submit Name recovery; remove session. |
| GM_CLOSE_ROOM | Live room, owner authority, client confirmation; terminal deletion. |

My Clues, search, filtering, confirmation cancellation, and tapping to skip a celebration do not send domain commands. There is no V1 Change Game command for an unimplemented game.

### Server to Client

| Message | Contents and purpose |
| --- | --- |
| COMMAND_RESULT | requestId, accepted/rejected outcome, safe error code if any, resulting revision. Never echo a submitted secret. |
| STATE | Personalized room and current game snapshot, including submission progress, phase, results, and safe connection/capability indicators. |
| PRESENTATION | Unique effect ID, revision/session ID, and safe effect metadata for Shuffle or Got It celebration. No secret text or attribution. |
| ACCESS_REVOKED | Kicked identity loses access; discard room view and detach. |
| ROOM_ENDED | CLOSED or EXPIRED; apply the specified terminal navigation/presentation. |

Joins, disconnects/reconnects, GM presence, submission progress, completed Shuffle, status changes, Roast, and Reveal are represented by STATE, not duplicate event streams. PRESENTATION is only a transient shared visual cue. Pick a randomized celebration variant in the server cue so every currently connected device can present it, auto-dismiss it, or locally skip it. Send only one cue per accepted Got It mutation. A reconnect snapshot never replays stale cues.

## Secret-Information Projection

Every outgoing snapshot is constructed for a verified recipient. Use allowlisted DTO fields, never raw aggregate serialization or frontend-only hiding. GM authority changes controls, not secret visibility.

| Phase | Fields allowed in the recipient's game view |
| --- | --- |
| Lobby | Member nicknames/avatars and allowed room controls; no previous round secrets. |
| Submit Name | Recipient submission-complete flag, aggregate progress; GM may additionally see each member's submitted/not-submitted flag. Do not transmit submitted text or submitter-text mappings. |
| Playing snapshot, including immediately after Shuffle | Other participants' assigned text, nicknames, avatars, results. Omit recipient's assigned text entirely; frontend renders ???. Omit all submitted-by attribution. The separate presentation cue contains no secrets. |
| Roast | Final result groups and presentation data. Do not expose the recipient's secret or assignment attribution before Reveal; a minimal result-only view is sufficient. |
| Reveal | Remaining participants' assigned text and preserved original submitter nickname, including the recipient's assignment. |

GOT_IT and GAVE_UP do not unlock the player's own secret. Redaction applies to every socket for that identity, reconnect snapshots, errors, debug outputs, and presentation cues. No all-secrets broadcast topic is exposed. Do not cache prior personalized snapshots across different authenticated identities.

## Who Am I Rules and Kick Data

Shuffle creates a permutation of submission records with no recipient matching the submitter player ID. For 2–20 players, randomize a permutation and retry any self-assignment before committing; the operation is all-or-nothing. Duplicate submitted text is allowed. Identify each submission by its session and submitting player; do not deduplicate, reject, or compare submissions by text when checking self-assignment. Receiving another player's identically-worded submission is valid, even if every participant submitted the same text.

Kick in Submit removes membership, participant entry, and submission, then recalculates eligibility. Kick after Shuffle also removes room membership and access; within game data, remove that recipient's participation/result/assignment only. Other assignments remain unchanged, including any text originally supplied by the kicked player.

Assignments capture the original submitter nickname so Reveal never depends on the submitter still being in Room.players. Delete this attribution with the old round on reset, Play Again, or return to Lobby; it is not persistent history. Keep the blocked guest fingerprint at room scope across rounds so Play Again cannot undo a kick.

End Game derives Roast groups from final participant statuses: PLAYING first (single Last One or group), optionally a separate GAVE_UP group; GAVE_UP focus when none are PLAYING; group success when all are GOT_IT. No scores, rankings, statistics, or automatic end conditions are introduced.

## My Clues and Avatars

My Clues belongs only to the local player, including a GM's own local clues. Store locally keyed by room, player, and session ID. On any new session, clear old-round clues; this also works when a disconnected browser misses Play Again and later resyncs. Clues are absent from RoomStore, backend persistence, WebSocket messages, and other players' views.

On creation of a new Player identity, backend assigns avatarId uniformly from the 25-entry V1 catalog (equal probability of 1/25 per character). No rarity, manual selection, or reroll mechanism is provided. Store only avatarId, never image binaries in Room/Game state.

Keep avatarId unchanged throughout that Player's lifetime in the Room, including reconnect, Play Again, and future game changes. A voluntary leave followed by joining as a new Player creates a fresh random assignment; it need not differ from the previous avatar.

Frontend static assets map stable avatar IDs to optimized WebP/AVIF files and responsive sizes. The catalog can expand without redesigning Room or game state. Lightweight CSS motion and local visual components provide the toy-like presentation without runtime 3D.

## Error Model

Return domain codes plus request correlation; the frontend supplies friendly themed copy. Do not expose stack traces, tokens, submitted text, or internal exceptions.

| Code | UI meaning |
| --- | --- |
| ROOM_NOT_FOUND | Room is unavailable; offer a way Home. |
| ROOM_EXPIRED | Themed expiration screen and Back Home. |
| ROOM_FULL | Room has reached its 20-player limit. |
| NICKNAME_TAKEN | Choose another nickname. |
| GAME_IN_PROGRESS | Friendly waiting screen; new joining remains blocked until Lobby. |
| NOT_AUTHORIZED | Identity cannot perform this action. |
| PLAYER_KICKED | This guest identity cannot rejoin this room. |
| NOT_ENOUGH_PLAYERS | Shuffle/start unavailable; show the specified friendly message and permitted recovery actions. |
| WAITING_FOR_SUBMISSIONS | Some current participants have not submitted. |
| WAITING_FOR_RECONNECT | A disconnected participant blocks Shuffle; GM can wait or kick. |
| INVALID_PHASE / STALE_COMMAND | Action no longer applies; resync without changing state. |
| INVALID_TRANSITION | Result cannot change directly; GM must reset to PLAYING first. |
| INVALID_INPUT | Friendly field feedback, without echoing sensitive input. |

GM absence is normally a snapshot-derived waiting state, not a technical error. Unexpected server failures receive a generic safe response; diagnostic details stay server-side with secret values excluded.

## Review Against the Product Specification

The design covers Lobby-only joining/leaving, Google-owned GM reconnect, guest identity restoration, disconnected-player Shuffle blocking, owner-offline player actions, the minimum-player recovery, fixed 48-hour expiration, result corrections, Roast priority, delayed Reveal, retained kicked-submitter attribution, and round reset. Room remains game-independent. No game or UI code is part of this change.

The confirmed GM exit and duplicate-submission rules are reflected in both specifications: GM has Close Room rather than voluntary Leave, temporary absence preserves ownership, and Shuffle excludes the recipient's own submission by owner identity while allowing identical text from others. No unresolved product decisions remain in the specified V1 flow.

Unnecessary complexity is excluded: no broker, delta-event reconstruction, durable history, generic game engine, separate repository for every value record, frontend state framework, or duplicate Room/game phase enums. Per-room locking, full personalized snapshots, and a small game handler boundary are sufficient for V1.
