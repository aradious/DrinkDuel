# DrinkDuel V1 — Liar's Dice Specification

## Document Status

This document is the source of truth for the Liar's Dice V1 game. The confirmed rules below come from the approved product decisions. The existing Room joinability constraint is documented explicitly rather than silently changing Room behavior.

No gameplay is implemented by this documentation pass.

## Product Role

Liar's Dice reuses the current DrinkDuel Room, Game Master, Players, player IDs, nicknames, avatars, identities, authorization, presence, reconnect behavior, room capacity, and fixed room lifetime.

The application is only:

- the authoritative dice roller
- each player's private dice cup and secret viewer
- the synchronized Reveal and result reference

The people in the room control conversation, bidding, challenges, and the pace of play.

DrinkDuel does not track turns, bid quantities, bid faces, Call Liar, winners, losers, scores, or rankings.

## Roles and Participation

- The authoritative round roster is captured when the GM successfully presses Start Game.
- Only connected Room players at that moment participate in the round.
- At least two connected participants, including the GM, are required.
- A disconnected Room member is excluded from that round and receives no dice.
- The existing Room GM remains the GM and also participates as a player.
- Normal Players remain guests and require no account.
- The GM follows the same dice secrecy rule as every other participant.
- Existing Room membership limits apply.

## Authoritative Flow

```text
Choose Liar's Dice
        |
        v
START — waiting for GM
        |
        | GM: Start Game
        v
PLAYING — dice rolled; private Peek / Close; verbal free play
        |
        | GM: End Game + confirmation
        v
REVEAL — all hands and aggregate counts visible
        |
        | GM: Restart
        v
START — same Room and players; no dice rolled yet
```

`ROLL` is an atomic backend operation inside the transition from START to PLAYING. It is not a durable intermediate server phase. Client tumble animation never delays or changes the committed result.

## Start Screen

Selecting Liar's Dice from Choose Game opens a dedicated Liar's Dice start screen for the current Room.

The GM sees **Start Game**. Normal Players see **Waiting for the Game Master…**.

The authoritative Start Game action atomically validates at least two connected participants, captures those participants as the locked round roster, generates every hand, and enters PLAYING. If fewer than two connected participants exist, reject the command without changing phase, roster, session, or dice state. A Player cannot start or restart a round, including by forging a command.

During START, the GM may kick a non-GM Room member under the existing Room authorization rules. Reconnect of an existing Room member is allowed. The existing Room implementation permits a new guest join only in the Lobby; the integration consequence is recorded under **Existing Room Joinability Constraint**.

## Server-Side Roll

Every participant receives exactly five dice. Each die is an integer from 1 through 6.

The backend generates every gameplay value using a secure or otherwise reliable unbiased server-side random source. The frontend never generates, chooses, corrects, or rerolls gameplay values.

A five-die hand is valid only when it contains at least one duplicate value. A hand containing five distinct values is rejected in full and the backend rolls a fresh five-die hand for that participant. This rejection approach preserves equal probability among all valid hands and does not favor a particular face.

Examples:

- Invalid: `1 2 3 4 5`
- Invalid: `2 3 4 5 6`
- Valid: `1 2 2 4 6`
- Valid: `3 3 4 5 6`
- Valid: `1 1 2 3 4`

The complete set of hands is committed atomically with the transition to PLAYING. The roster becomes locked in the same mutation. Reconnect, refresh, Peek, Close, and client animation never invoke the roller.

## Private Dice and Peek

During PLAYING, an authenticated participant's personalized server snapshot contains only that participant's existing five-die hand. It must not contain another player's dice in nested fields, summaries, debug data, accessibility text, or presentation metadata.

The GM may see only the GM's own hand before Reveal.

The cup begins closed. The primary local interaction is hold or press to Peek:

- While held, the cup lifts or tilts and the player's five dice are visible.
- On release or close, the cup covers the dice again.
- Repeated Peek and Close operations show the same five authoritative values.
- Peek state is local presentation state and is not broadcast.
- Mouse, touch, and keyboard interaction must be supported where practical.
- The control must have an understandable accessible name and state without placing any hidden dice value into labels when the cup is closed.

Use `dice-cup.png` for the closed state and `dice-cup-open.png` for the open/Peek state, subject to the asset issue recorded below.

## Dice Rendering and Motion

`dice-reference.png` is a visual reference only. It must not be used as the gameplay dice image.

Gameplay dice are rendered in code so five independent elements can settle on the exact backend values. CSS transforms may provide:

- tumble
- `rotateX`
- `rotateY`
- a small bounce
- individual timing variation
- small final position and rotation differences

Animation changes only presentation transforms. The DOM/model value for each die comes from the authoritative snapshot and remains unchanged throughout animation.

Use deterministic visual parameters derived locally from die position or stable round metadata where useful; do not treat visual randomness as gameplay randomness. Under `prefers-reduced-motion`, show the final values without tumble or with a minimal transition.

Do not add Three.js, WebGL, GLB, or another runtime 3D engine.

## Free Play

PLAYING is free-form verbal play. There is no application turn, timer, bid, challenge, winner, loser, score, or automatic completion state.

Players may repeatedly Peek and Close their own cup. Only the GM sees **End Game**.

The GM cannot kick during PLAYING. The locked roster and all hands remain unchanged until Reveal.

## End Game and Reveal

End Game is a GM-only authoritative action and requires client confirmation to prevent accidental disclosure. An accepted End Game command transitions every connected client to REVEAL.

The GM cannot kick during REVEAL. The locked round roster remains unchanged.

Reveal may expose all hands because the round has ended. Show:

- every participant's nickname
- avatar
- five dice
- raw total for each face 1 through 6
- effective total for faces 2 through 6 after including Wild 1

The result display must distinguish **Raw Count** from **With Wild 1** clearly.

## Wild One Rule

Face 1 is Wild in V1.

Let `count(face)` be the raw number of that face across all revealed hands:

```text
effective(2) = count(2) + count(1)
effective(3) = count(3) + count(1)
effective(4) = count(4) + count(1)
effective(5) = count(5) + count(1)
effective(6) = count(6) + count(1)
```

Face 1 displays only its raw count. Do not add ones to face 1 itself.

The same Wild-1 pool is included independently in every non-one effective count. These values are references; Wild dice are not consumed.

Example: if raw `1 = 3` and raw `4 = 5`, then **With Wild 1** for face 4 is `8`.

Raw and effective counts are derived from revealed authoritative hands. They do not create separate score or history state.

## Restart

On REVEAL, the GM sees **Restart**. Players see **Waiting for the Game Master…**.

An accepted GM Restart action:

- clears every hand
- clears derived aggregate results
- leaves REVEAL
- returns all clients to the Liar's Dice START screen
- preserves Room ID and QR
- preserves GM ownership
- preserves Room players, internal player IDs, nicknames, and avatars
- preserves guest and Host reconnect identity bindings
- does not roll new dice

Restart creates a new `GameSession` with a new session ID in `LIARS_DICE / START`. It does not carry forward the previous roster, hands, Reveal data, aggregate counts, or any other round-specific state.

The GM must press Start Game again to create and roll the next active round.

## Authorization and Realtime

Reuse the current server-authoritative WebSocket model, verified connection identity, per-room mutation lock, session ID validation, request ID idempotency, ordered snapshots, and server-provided allowed actions.

GM-only actions are:

- select/open Liar's Dice
- Start Game
- End Game
- Restart
- Kick, only while the Liar's Dice session is in START

Peek and Close are local Player interactions and send no shared command.

Normal Players must be rejected if they forge any GM-only operation. All accepted shared mutations produce new personalized snapshots for connected Room members.

## Personalized Snapshot Contract

Use an explicit Liar's Dice wire DTO rather than serializing domain objects.

| Phase | Recipient-safe contents |
|---|---|
| START | Game type, phase, participant-safe public information, and allowed actions. No dice. |
| PLAYING | Game type, phase, the recipient's own five dice, participant-safe public information, and allowed actions. No other hand or aggregate capable of revealing other hands. |
| REVEAL | All revealed hands and derived raw/effective counts, plus allowed actions. |

The projection must branch on the verified recipient player ID. GM authority changes available controls, not pre-Reveal dice visibility.

Client-side hiding is defense in depth only. Another participant's pre-Reveal dice must be absent from the network payload and frontend state.

## Reconnect and Refresh

Reconnect is supported in START, PLAYING, and REVEAL through the existing Host session or guest identity.

- START restores the start/waiting screen without dice.
- PLAYING restores the same stored hand for the reconnecting participant and no other hand.
- REVEAL restores all hands and aggregate results.
- A Room member who was disconnected when Start Game succeeded is not in that round roster. Reconnecting during PLAYING or REVEAL does not add them, create a hand, or change aggregate counts.
- No reconnect or state request calls the roller.
- A reconnecting client follows the current authoritative phase and allowed actions.

## GM Disconnection

Existing Room behavior applies. The Room and Liar's Dice state remain in memory while the GM is disconnected. Players may continue local Peek and Close interaction in PLAYING. Shared GM actions remain unavailable until the same Host identity reconnects.

Show the existing friendly waiting treatment rather than a technical connection error.

## Responsive and Visual Direction

Use the established DrinkDuel visual language: immersive party background, dark-plum translucent surfaces, gold/orange accents, pink/purple highlights, rounded tactile controls, and lightweight motion.

Explicit targets:

- 390px: Peek is the dominant touch interaction; no horizontal overflow.
- 768px: cup, dice, status, and GM action retain a clear hierarchy.
- 1440px: use the environment and available space without turning the screen into a dashboard.

Use supplied assets from `frontend/src/assets/drinkduel/games/liars-dice/`:

| Asset | Intended use |
|---|---|
| `liars-dice-bg.webp` | Full-screen Liar's Dice environment. |
| `liars-dice-logo.png` | Game logo. |
| `dice-cup.png` | Closed cup. |
| `dice-cup-open.png` | Lifted/open cup during Peek. |
| `dice-reference.png` | Visual reference only; never a gameplay die. |

### Current Asset Issue

Technical inspection produced the following results:

| File | Actual encoding | Dimensions | Alpha | Checkerboard / intended-use result |
|---|---|---:|---|---|
| `liars-dice-bg.webp` | PNG truecolor with alpha | 1672×941 | Yes | No checkerboard issue was observed in the background. The extension does not match its PNG bytes, so it should be correctly encoded or renamed before reliable production serving. |
| `liars-dice-logo.png` | PNG truecolor | 1536×1024 | No | Checkerboard is baked into RGB pixels. Not safe as a transparent overlay in its current form. |
| `dice-cup.png` | PNG truecolor | 1402×1122 | No | Checkerboard is baked into RGB pixels. Not safe as the intended transparent closed-cup overlay. |
| `dice-cup-open.png` | PNG truecolor | 1402×1122 | No | Checkerboard is baked into RGB pixels. Not safe as the intended transparent open-cup overlay. |
| `dice-reference.png` | PNG truecolor | 1536×1024 | No | Checkerboard is baked into RGB pixels. Safe only as the specified visual reference; never use it as gameplay dice. |

Do not edit or replace these files in this documentation pass. Correct transparent logo/cup exports and a correctly encoded background are recommended before frontend integration.

### Code-Rendered Dice Appearance

Later implementation should render premium white or ivory dice with rounded edges and neon/gold lighting consistent with the supplied artwork. Face 1 uses a red pip. All four pips on face 4 are red. Other faces use the normal dark pip treatment. This visual rule does not change authoritative values.

## Proposed Domain and Transport Shape

The implementation should add:

- `GameType.LIARS_DICE`
- immutable `LiarsDiceState`
- phases `START`, `PLAYING`, and `REVEAL`
- an ordered participant list
- one immutable five-value hand per participant in PLAYING and REVEAL
- injected server-side dice random source for deterministic tests
- game-specific service operations for selection, Start Game, End Game, and Restart
- explicit Liar's Dice protocol DTOs and recipient-specific projection
- explicit GM-only allowed actions for each phase

Aggregate raw and effective counts should be derived from hands during Reveal projection or through a pure domain calculation. They are not independent mutable truth.

## Frontend Components

Likely feature components:

- Liar's Dice catalog card on Choose Game
- `LiarsDiceStart` for GM Start Game and Player waiting state
- `LiarsDicePlaying` for private cup, code-rendered dice, local Peek state, and GM End Game confirmation
- reusable `DieFace` presentation component for values 1–6
- `LiarsDiceReveal` for player hands, raw counts, Wild-1 effective counts, GM Restart, and Player waiting state

The existing Room client remains the connection owner. Its game model and safe snapshot normalization must become a discriminated union keyed by `gameType`, preserving the current Who Am I filtering exactly.

## Test Strategy

### Domain

- exactly five values per participant, each in 1–6
- every hand contains at least one duplicate
- rejection sampling does not rewrite a valid hand or force a face
- Start Game rolls once and commits atomically
- Peek/Close and reconnect never reroll
- End Game and Restart phase rules
- Restart clears hands and results while preserving Room membership data
- raw and effective Wild-1 calculations
- membership removal behavior after the related open decision is confirmed

### Authorization and Realtime

- only GM can select, start, end, and restart
- forged Player commands are rejected without mutation
- each PLAYING recipient receives only their own hand
- GM receives only the GM hand
- no other hand appears anywhere in pre-Reveal serialized JSON
- all hands appear only in REVEAL
- reconnect snapshots are stable in every phase
- duplicate request IDs do not reroll
- stale session commands are rejected
- Who Am I snapshots and commands remain unchanged

### Frontend

- catalog selection and authoritative routing
- GM and Player START states
- mouse, touch, pointer cancellation, and keyboard Peek behavior
- closing and reopening retains identical values
- End Game confirmation and pending state
- Reveal hands and correct raw/effective labels
- GM-only Restart and Player waiting state
- refresh/reconnect routing in all phases
- secret values absent from closed-cup accessibility text and other players' state
- reduced-motion behavior
- layout checks at 390px, 768px, and 1440px
- complete existing Who Am I regression suite

## Small Implementation Steps

1. Add the Liar's Dice domain state, injected roller, rejection sampling, and focused domain tests without transport or UI.
2. Add typed service operations and Room/GameSession integration with atomic connected-roster capture, GM authorization, phase checks, START-only Kick, and new-session Restart.
3. Add explicit protocol DTOs, allowed actions, personalized projection, commands, and exhaustive secret/reconnect integration tests.
4. Extend the frontend discriminated game model and authoritative RoomClient routing while keeping existing Who Am I normalization unchanged.
5. Add the Choose Game catalog entry and START screen with GM/Player states.
6. Add PLAYING cup interaction and code-rendered dice animation using authoritative values, including accessibility and reduced motion.
7. Add End Game confirmation and REVEAL with raw/Wild-1 counts and Restart.
8. Run complete backend/frontend regression, production builds, two-client secret checks, reconnect checks, and responsive browser review.

## Expected File Scope

Likely existing files to modify:

- `backend/src/main/java/com/drinkduel/game/GameState.java`
- `backend/src/main/java/com/drinkduel/game/GameType.java`
- `backend/src/main/java/com/drinkduel/room/RoomService.java`
- `backend/src/main/java/com/drinkduel/realtime/RoomProtocol.java`
- `backend/src/main/java/com/drinkduel/realtime/RoomRealtime.java`
- `backend/src/main/java/com/drinkduel/realtime/RoomViewFactory.java`
- focused backend tests for service, protocol, authorization, reconnect, and Who Am I regression
- `frontend/src/app/core/room.models.ts`
- `frontend/src/app/core/room-client.ts`
- `frontend/src/app/pages/choose-game.ts`
- `frontend/src/app/pages/choose-game.scss`
- `frontend/src/app/pages/lobby.ts`
- focused frontend model/client/screen tests

Likely new files:

- `backend/src/main/java/com/drinkduel/game/LiarsDiceState.java`
- a small injectable dice roller abstraction/implementation if kept separate from the state
- focused `LiarsDice*Tests.java` files
- `frontend/src/app/pages/liars-dice-start.ts` and `.scss`
- `frontend/src/app/pages/liars-dice-playing.ts` and `.scss`
- `frontend/src/app/pages/liars-dice-reveal.ts` and `.scss`
- `frontend/src/app/shared/die-face.ts` and `.scss` if the dice renderer is shared
- focused Liar's Dice component tests

Do not create a generic workflow engine, bidding model, score model, database table, or shared-state framework.

## Who Am I Regression Risks

- Replacing the current `WhoAmIView` field with a game union could accidentally weaken secret filtering.
- Reusing generic command names without a game discriminator could route Liar's Dice actions into Who Am I transitions.
- Generalizing `RoomService.gameState` could weaken session/phase type checks.
- A shared Reveal model could expose Who Am I attribution early or Liar's Dice hands before Reveal.
- Frontend normalization currently assumes Who Am I fields and may discard or misroute a new game snapshot.
- Lobby routing currently switches only on Who Am I phases.
- Generic Play Again/End Game handling could apply the wrong game's semantics.
- Allowed-action projection must expose Kick for Liar's Dice START only, without changing Who Am I's existing kick behavior.

Mitigate these risks with discriminated DTOs, game-specific state casts/handlers, unchanged Who Am I tests, negative cross-game command tests, and serialized-payload assertions.

## Existing Room Joinability Constraint

The current Room domain defines Lobby as “no current GameSession” and rejects new guest joins whenever a GameSession exists. Liar's Dice START is already a GameSession, so the existing architecture produces this behavior:

- new guests may join in the Lobby before Liar's Dice is selected
- existing Room members may reconnect during START, PLAYING, or REVEAL
- new guests cannot join during Liar's Dice START, PLAYING, or REVEAL
- a reconnecting member may enter the round roster only if connected when Start Game succeeds
- reconnect never adds a nonparticipant to an already locked PLAYING or REVEAL roster

This is consistent with preserving existing Room join rules. If “Join/room preparation is allowed in START” was intended to permit brand-new Room members after the Liar's Dice GameSession has been created, that would require an explicit change to the Room joinability rule before the transport integration step. It does not block the standalone domain and roller work in Step 1.

The four prior roster, kick, minimum-player, and Restart questions are resolved. No other gameplay decision is currently open. Corrected asset exports remain a frontend integration need rather than a gameplay decision.
