# Sheriff V1 Product and Technical Specification

## 1. Purpose and source-of-truth status

This document is the V1 source of truth for the DrinkDuel game **Sheriff**. The general Room, identity, connection, expiration, authorization, and transport rules in `PRODUCT_SPEC.md` and `ARCHITECTURE.md` continue to apply unless this document defines Sheriff-specific behavior.

Sheriff is a social deduction aid. DrinkDuel assigns and protects roles, tracks one-life status, synchronizes public information, and lets the Game Master reveal deaths and end or restart a round. The people in the Room conduct the physical game and decide its outcome.

## 2. Product scope

Sheriff V1 provides:

- A 4–20 participant game selected from the existing Room.
- Authoritative random role assignment.
- One public Sheriff and private Deputy, Outlaw, and Renegade roles.
- One life per participant.
- A private press-and-hold role-card interaction.
- A public Sheriff identity from the beginning of `PLAYING`.
- GM-controlled, irreversible death and role reveal.
- Public live role and life counts.
- GM-controlled final reveal.
- Play Again with a new unassigned `START` phase and a fresh reshuffle when Start is pressed again.
- Existing Room membership, identity, avatar, reconnect, expiration, Close Room, and realtime behavior.

### 2.1 Non-goals

DrinkDuel does not implement or enforce:

- Shooting turns or turn order.
- Shot or Skip tracking.
- Target selection.
- Rock-paper-scissors input or results.
- Sheriff protection volunteer selection.
- Combat resolution validation.
- Automatic victory detection.
- Automatic game completion.
- Scores, rankings, statistics, or game history.

These activities happen offline between the people in the Room. The GM records only the resulting deaths and manually ends the game.

## 3. Roles and teams

Sheriff has four roles:

| Role | Team | Visibility during `PLAYING` |
|---|---|---|
| Sheriff | Sheriff team | Player identity and role are public from the start |
| Deputy | Sheriff team | Secret except to that Deputy until death/reveal or End Game |
| Outlaw | Outlaw team | Secret except to that Outlaw until death/reveal or End Game |
| Renegade | Independent | Secret except to the Renegade until death/reveal or End Game |

Outlaws do not learn the identities of other Outlaws. Deputies do not receive any additional private team roster. Every non-Sheriff participant sees only their own role.

### 3.1 Distribution formula

Let `N` be the number of connected participants captured when the GM successfully starts the game. `N` must be from 4 through 20 inclusive.

- Sheriff: `1`
- Deputy: `floor(N / 2) - 1`
- Outlaw: `floor(N / 2)`
- Renegade: `N % 2`

The Sheriff plus Deputies equals the Outlaw count. An odd participant count adds exactly one Renegade.

### 3.2 Distribution table

| Players | Sheriff | Deputy | Outlaw | Renegade |
|---:|---:|---:|---:|---:|
| 4 | 1 | 1 | 2 | 0 |
| 5 | 1 | 1 | 2 | 1 |
| 6 | 1 | 2 | 3 | 0 |
| 7 | 1 | 2 | 3 | 1 |
| 8 | 1 | 3 | 4 | 0 |
| 9 | 1 | 3 | 4 | 1 |
| 10 | 1 | 4 | 5 | 0 |
| 11 | 1 | 4 | 5 | 1 |
| 12 | 1 | 5 | 6 | 0 |
| 13 | 1 | 5 | 6 | 1 |
| 14 | 1 | 6 | 7 | 0 |
| 15 | 1 | 6 | 7 | 1 |
| 16 | 1 | 7 | 8 | 0 |
| 17 | 1 | 7 | 8 | 1 |
| 18 | 1 | 8 | 9 | 0 |
| 19 | 1 | 8 | 9 | 1 |
| 20 | 1 | 9 | 10 | 0 |

### 3.3 Assignment requirements

- The backend is the sole authority for role assignment.
- The backend builds the exact role multiset for `N` and randomly shuffles it across the locked participant IDs.
- The Sheriff is randomized like every other role; the GM is not automatically the Sheriff.
- Assignment and the transition into `PLAYING` are atomic. A failure must leave the game in its previous valid state with no partial assignments.
- Every locked participant receives exactly one role, and every generated role is assigned exactly once.
- Refresh, reconnect, rendering, and realtime updates must never reshuffle an active round.
- A new assignment is created only by a successful Start for a new round.

## 4. Offline gameplay rules

These rules describe how the people play. DrinkDuel does not model or enforce them.

### 4.1 Turn action

- Each player receives one action per offline round: **Shoot** once or **Skip**.
- The group tracks whose action has been used.
- DrinkDuel does not store actions, turns, shots, skips, or targets.

### 4.2 Combat

- A shooter must win rock-paper-scissors to kill the target.
- A tie means the target survives.
- The players resolve rock-paper-scissors outside DrinkDuel.
- After the offline result, the GM records a death in DrinkDuel only when a player has died.

### 4.3 Protecting the Sheriff

- When the Sheriff is targeted, any living player other than the shooter may volunteer to protect the Sheriff.
- The Sheriff chooses one volunteer or chooses to defend personally.
- If the selected volunteer loses, that volunteer dies and the Sheriff survives.
- If the selected volunteer wins or ties, both the volunteer and Sheriff survive.
- If the Sheriff defends personally, the normal combat result applies to the Sheriff.
- Defending does not consume the defender's Shoot or Skip action.
- DrinkDuel does not collect volunteers, select a defender, or resolve the defense.

### 4.4 Victory rules

Victory is decided manually by the group and is documentation-only:

- The Sheriff team wins when every assigned Outlaw and any assigned Renegade are dead while the Sheriff is alive.
- The Outlaws win when the Sheriff dies, unless the Renegade is the sole survivor.
- The Renegade wins only when they are the sole survivor.

DrinkDuel does not infer a winner, announce a winning team, end the game automatically, or calculate a score from these conditions.

## 5. Authoritative state machine

```text
Lobby
  └─ GM selects Sheriff
       ↓
     START
       └─ GM starts with 4–20 connected participants
            ↓
          PLAYING
            ├─ GM records zero or more irreversible deaths
            └─ GM manually ends the game
                 ↓
               REVEAL
                 ├─ Play Again → START
                 ├─ Back to Room → Lobby
                 └─ Change Game → authoritative Lobby, then Choose Game
```

There are no automatic phase transitions based on deaths or documented victory conditions.

### 5.1 START

`START` is a Sheriff Game Session preparation phase with no role assignments and no life-state records for a round.

- New players cannot join because the Room is no longer in Lobby.
- Existing Room members may reconnect to their existing identity.
- The roster displays all Room members and their connection status.
- The GM may kick a non-GM player only during `START`.
- Kicking removes that player from Room membership under the existing Room rules.
- The GM may return to Room or choose another game through the existing authoritative Back-to-Room behavior.
- Start is available only to the connected GM and only when 4–20 Room members are connected.
- Disconnected Room members are excluded when Start captures the round roster.
- If fewer than four participants are connected, Start is unavailable and a forged command is rejected without changing the session.
- A successful Start locks the connected roster, creates a fresh round/session, assigns roles atomically, gives every participant one life with `ALIVE` status, and enters `PLAYING`.

A Room member excluded because they were disconnected at Start remains a Room member but is not a participant in that round. Reconnecting during `PLAYING` does not add them to the locked roster or assign them a role.

### 5.2 PLAYING

- Participant order is the authoritative order captured at Start.
- The Sheriff player ID and Sheriff role are public to every client.
- Each non-Sheriff participant receives only their own private role.
- Every participant starts `ALIVE` and has exactly one life.
- Offline play continues until the GM manually ends the game.
- The connected GM may record a death for any living participant, including themselves.
- The GM cannot kick players during `PLAYING`.
- Disconnecting does not remove a participant, alter their role, change their life status, or change Role Status counts.
- Existing participants may reconnect and receive their unchanged personalized state.
- Players excluded at Start see that the round is in progress but receive no role.

### 5.3 REVEAL

- Every locked participant's role is public to every client.
- Final `ALIVE` or `DEAD` status remains visible.
- Role Status remains visible and uses the final authoritative state.
- No winning team, score, or ranking is derived.
- Normal Players see the reveal and wait for the GM's next action.

The connected GM receives:

- **Play Again:** enter an unassigned Sheriff `START` phase. No roles are assigned by this action.
- **Back to Room:** remove the Sheriff session and return everyone to the joinable Lobby.
- **Change Game:** remove the Sheriff session through the same authoritative Back-to-Room operation, return everyone to Lobby, and route the GM to Choose Game. It is not a separate backend phase transition.

After Play Again, the GM must press Start again. The successful Start captures the currently connected Room members, begins a fresh round/session, generates a new random assignment, resets every new-round participant to `ALIVE`, and removes all previous round role and death data. Room membership, nicknames, avatars, player identities, and GM ownership are preserved.

## 6. Life, death, and public reveal

### 6.1 Life model

- Each locked participant has one authoritative life status: `ALIVE` or `DEAD`.
- Every new round begins with every locked participant `ALIVE`.
- The only valid round transition is `ALIVE → DEAD`.
- `DEAD → ALIVE` is not supported in V1.

### 6.2 GM death action

The GM death action is one atomic authoritative operation:

1. Validate that the actor is the connected GM.
2. Validate the current session and `PLAYING` phase.
3. Validate that the target belongs to the locked roster and is currently `ALIVE`.
4. Change the target to `DEAD`.
5. Make that target's role public to every client.
6. Publish personalized authoritative snapshots with updated Role Status counts.

The GM may target their own participant.

Death is irreversible. A duplicate, delayed, or retried death action against an already dead participant must not mutate state, reveal any additional secret assignment, increment the dead count, decrement the alive count, or create another revision representing a second death. Request idempotency and stale-session protection remain required.

Once a role becomes public because of death, it stays public for the rest of the round, including after disconnect and reconnect.

## 7. Permissions and authority

All application controls depend on authoritative `allowedActions`. The frontend must not infer authority solely from local UI state or a client-supplied GM flag.

| Action | START | PLAYING | REVEAL | Authority |
|---|---:|---:|---:|---|
| View public Room/player information | Yes | Yes | Yes | Current Room members |
| View own private role | No assignment | Yes | All roles public | Locked participant |
| Start Sheriff round | Yes | No | No | Connected GM only |
| Kick non-GM player | Yes | No | No | Connected GM only |
| Mark living participant dead and reveal role | No | Yes | No | Connected GM only |
| End game and reveal all roles | No | Yes | No | Connected GM only |
| Play Again | No | No | Yes | Connected GM only |
| Back to Room | Yes | No | Yes | Connected GM only |
| Change Game | Yes | No | Yes | Connected GM only |
| Close Room | Yes | Yes | Yes | Connected GM under existing Room rules |

When the GM disconnects:

- The Room and Sheriff session are preserved.
- GM-controlled actions become unavailable.
- Other clients remain in the current authoritative phase.
- Private-role viewing and public information remain available to eligible players.
- Shared progression resumes only after the same GM identity reconnects.
- There is no GM transfer.

Normal Players cannot start, kick, mark deaths, end, restart, return the Room, change the game, or close the Room by forging commands.

## 8. Role visibility and card interaction

### 8.1 Sheriff

- The Sheriff card is always face-up during `PLAYING`.
- Every client can see which locked participant is the Sheriff.
- The Sheriff role never depends on the private hold interaction.

### 8.2 Deputy, Outlaw, and Renegade

- A living participant sees their own role through a press-and-hold card flip.
- Pointer down or supported keyboard press reveals the card.
- Pointer release, pointer cancel, pointer leave, key release, focus loss, window blur, hidden-document state, route change, or component destruction hides the card.
- Releasing the control must not require a server command because role visibility on the owner's device is local presentation of already-authorized data.
- Other clients never receive this private assignment.

### 8.3 Public roles

- A dead participant's card becomes face-up for everyone after the authoritative GM death action.
- All cards become face-up for everyone in `REVEAL`.
- A hidden role must not appear in DOM text, ARIA labels, titles, tooltips, CSS-generated content, search/filter metadata, debug output, or client logs belonging to another participant.

## 9. Role Status

### 9.1 Display requirements

During `PLAYING` and `REVEAL`, every client displays the same shared live Role Status summary near the current player's role card or the corresponding primary role area.

The summary shows these values for all four roles, including roles with zero assigned players:

| Role | Public values |
|---|---|
| Sheriff | Assigned, Alive, Dead |
| Deputy | Assigned, Alive, Dead |
| Outlaw | Assigned, Alive, Dead |
| Renegade | Assigned, Alive, Dead |

It also shows:

- Overall total players
- Overall alive players
- Overall dead players

On mobile, the summary may stack or use a compact grid. It must remain readable, avoid horizontal overflow, stay close to the role-card area, and not obstruct the press-and-hold interaction.

### 9.2 Authority and privacy

- Counts are derived from authoritative locked assignments and life states.
- Counts are not maintained as an independent mutable source of truth.
- Role totals are public from the beginning of `PLAYING`.
- The same public counts are sent to GM and Player clients.
- Disconnected locked participants remain included in assigned, alive, dead, and overall counts.
- Counts reveal aggregate role composition only. They must not contain an explicit living secret role's Player ID binding beyond information already public for the Sheriff or a dead/revealed participant. Logical deductions made from public totals, the recipient's own role, and public reveals are part of the shared information model rather than an additional server disclosure.
- Every GM death action publishes updated counts to all connected clients.
- Refresh and reconnect rebuild the same counts from unchanged authoritative state.

### 9.3 Count invariants

For every authoritative snapshot in `PLAYING` or `REVEAL`:

- Every role is present exactly once in the summary.
- `role.assigned = role.alive + role.dead` for each role.
- Sum of role assigned counts equals overall total players.
- Sum of role alive counts equals overall alive players.
- Sum of role dead counts equals overall dead players.
- `overall total = overall alive + overall dead`.
- At the start of `PLAYING`, overall dead is zero and overall alive equals overall total.
- Sheriff assigned is always one.
- Renegade assigned is zero for even rosters and one for odd rosters.
- One valid death changes exactly one role from alive to dead and changes the overall alive/dead values by exactly one.
- A duplicate death action causes no count change.
- Counts remain unchanged merely because a locked participant disconnects or reconnects.

The Role Status summary is informational only. It does not create gameplay controls, scores, rankings, automatic winner detection, or automatic phase transitions.

## 10. Realtime privacy and security

### 10.1 Authoritative data

- The complete role-assignment map exists only in backend game state until `REVEAL`.
- The backend projects a personalized snapshot for each verified Room participant.
- During `PLAYING`, a personalized snapshot may contain:
  - The public Sheriff player ID and role.
  - The recipient's own role if they belong to the locked roster.
  - Roles of participants already marked dead.
  - Public life states and aggregate Role Status counts.
- It must not contain another living participant's Deputy, Outlaw, or Renegade assignment.
- During `REVEAL`, every role may be included.

### 10.2 Transport and authorization

- New Sheriff commands use the existing server-bound identity and GM authorization model.
- Session-changing commands carry the current Sheriff session ID.
- Stale-session commands are rejected without mutation.
- Request IDs use the existing idempotency behavior so retries cannot repeat a death or phase transition.
- Clients do not optimistically assign roles, mark deaths, change counts, or advance phases.
- All clients navigate in response to authoritative snapshots.
- A reconnect receives a newly projected snapshot from unchanged authoritative game state.

### 10.3 Sensitive-data handling

Secret role assignments must not appear in:

- Application logs or exception messages.
- `toString()` output for game state, protocol views, or commands.
- Command-result error messages.
- Complete snapshot logging.
- Metrics labels or analytics payloads.
- URLs, local-storage keys, cookies, or credentials.

The frontend may retain the current participant's authorized private role only as part of the active recipient-safe Room state needed to render their card. It must not persist role assignments as game history.

## 11. Room membership, reconnect, and kick behavior

- Brand-new joins are permitted only in Lobby under the existing Room rules.
- Sheriff `START`, `PLAYING`, and `REVEAL` are not joinable phases.
- Closing a browser or losing connection marks the member disconnected; it does not voluntarily remove them.
- Existing identities may reconnect during any Sheriff phase.
- A participant who was locked at Start retains their assignment and life status through disconnect and reconnect.
- A Room member disconnected at Start is excluded from the locked roster and receives no mid-round assignment after reconnecting.
- The GM may kick a non-GM member only during `START`.
- Kick is unavailable during `PLAYING` and `REVEAL`.
- There is no voluntary Leave Room action during a Sheriff session. Existing Lobby leave rules resume after Back to Room.
- Room expiration, Close Room, owner identity, and in-memory durability continue to follow the general Product and Architecture specifications.

## 12. UI screens and supplied asset mapping

Use only the supplied files under `frontend/src/assets/drinkduel/games/sheriff/` for Sheriff-specific artwork. Do not repaint, regenerate, crop permanently, or overwrite the files.

| Asset | Screen usage |
|---|---|
| `cover.png` | Sheriff card in Choose Game |
| `backgrounds/game-bg.png` | Environment for START, PLAYING, and REVEAL |
| `cards/card-back.png` | Hidden Deputy, Outlaw, or Renegade private card |
| `characters/sheriff.png` | Public Sheriff card and final reveal |
| `characters/deputy.png` | Authorized Deputy card and public reveal |
| `characters/outlaw.png` | Authorized Outlaw card and public reveal |
| `characters/renegade.png` | Authorized Renegade card and public reveal |

The supplied character files are rectangular scene artwork rather than guaranteed transparent cutouts. Display them with responsive image containers and appropriate `object-fit`; do not assume transparent layering. No separate Sheriff logo asset exists, so screen headings use accessible text rather than fabricating a new logo.

### 12.1 Choose Game

- Add Sheriff beside the existing games using `cover.png`.
- Match the existing card size, button hierarchy, disabled state, and responsive behavior.
- Disable selection when fewer than four players are connected.
- Backend Start validation remains authoritative even when the frontend disables the control.

### 12.2 START screen

- Use the established Liar's Dice START interaction pattern.
- Show Room code, Sheriff heading, Room roster, avatars, connection status, YOU and Game Master indicators.
- GM sees Remove controls for eligible non-GM members, Start Game, and secondary Back/Change Game actions.
- Player sees a waiting-for-GM state.
- Explain that only connected players at Start receive roles.

### 12.3 PLAYING screen

- Show the public Sheriff identity prominently.
- Show the current participant's role-card area.
- Keep the Sheriff card face-up.
- Use hold-to-reveal for a living Deputy, Outlaw, or Renegade's own private card.
- Show Role Status near the role-card area.
- Show every locked participant with public life and connection state.
- Show public roles for Sheriff and dead players only.
- GM receives a death/reveal control for each living participant and a separate End Game action.
- Players receive no GM action controls.

### 12.4 REVEAL screen

- Show every locked participant in authoritative roster order.
- Show avatar, nickname, role artwork, role label, and final life status.
- Show final Role Status.
- GM receives Play Again, Back to Room, and Change Game.
- Players see a waiting-for-GM message.
- Do not display a computed winner, score, or ranking.

### 12.5 Responsive and accessibility requirements

- Verify at 390px, 768px, and 1440px.
- Support up to 20 participants with normal vertical scrolling and no horizontal overflow.
- Keep touch controls large enough for mobile use.
- Preserve image aspect ratio and never stretch role artwork.
- Press-and-hold must support pointer and keyboard input.
- Releasing, losing focus, or hiding the page must immediately hide a private card.
- Honor `prefers-reduced-motion`.
- Do not depend on color alone for role, life, connection, or action state.

## 13. Edge cases and acceptance tests

### 13.1 Distribution

- 3 connected players: Start rejected; no assignments or phase change.
- 4 players: 1 Sheriff, 1 Deputy, 2 Outlaws, no Renegade.
- 5 players: 1 Sheriff, 1 Deputy, 2 Outlaws, 1 Renegade.
- 19 players: 1 Sheriff, 8 Deputies, 9 Outlaws, 1 Renegade.
- 20 players: 1 Sheriff, 9 Deputies, 10 Outlaws, no Renegade.
- Every supported count satisfies the formula and assigns every participant once.
- Repeated test runs demonstrate that role placement is not fixed to GM, join order, nickname, avatar, or Player ID.

### 13.2 Start and roster locking

- Only connected Room members are captured.
- A disconnected member does not receive a role.
- An assignment failure commits no partial state.
- Start cannot be executed by a Player or disconnected GM.
- Kick works during START and is rejected during PLAYING and REVEAL.
- A kicked identity cannot reconnect under the existing Room rules.

### 13.3 Privacy

- Every client sees the Sheriff identity from the first `PLAYING` snapshot.
- A living non-Sheriff receives their own role and no other living secret assignment.
- Outlaws cannot identify other Outlaws from personalized fields.
- An excluded Room member receives no private role.
- Role totals are identical across clients without Player ID-to-secret-role bindings.
- Frontend hidden-card DOM and accessibility metadata contain no role text while hidden.
- Logs and error paths contain no role assignments or complete game snapshots.

### 13.4 Death and counts

- GM may mark any living locked participant dead, including themselves.
- Death and role reveal arrive in the same authoritative update.
- All connected clients see the dead status, revealed role, and updated counts.
- A duplicate death request makes no second change.
- A dead participant cannot return to alive.
- Death never automatically ends the game.
- All count invariants hold before and after every death.
- Disconnect/reconnect does not change counts.

### 13.5 Reconnect and authority

- A locked participant reconnects with the same role and life state.
- A dead participant reconnects with their role still public.
- An excluded member reconnects as a spectator for the active round.
- GM disconnect freezes GM actions without resetting the game.
- The same GM identity reconnects and regains phase-appropriate actions.
- A normal Player never receives or successfully forges a GM command.

### 13.6 End and restart

- Only the connected GM can end `PLAYING`.
- End Game enters `REVEAL` and exposes every assignment to every client.
- Documented victory conditions do not trigger a transition.
- Play Again enters START with no assignments.
- Previous roles and deaths cannot appear in the new START state.
- The subsequent Start creates a fresh role assignment and makes all new-round participants alive.
- Room membership, identity, nickname, avatar, and GM ownership remain unchanged.
- Back to Room restores a joinable Lobby.
- Change Game returns everyone authoritatively to Lobby before the GM enters Choose Game.
- Stale commands from the previous session cannot affect the new round.

### 13.7 UI and interaction

- Sheriff card remains face-up for all clients.
- Hold reveals only the current participant's authorized private card.
- Pointer release, cancellation, leaving the control, key release, blur, hidden page, and component destruction hide it.
- Role Status remains legible beside the role area at 390px, 768px, and 1440px.
- A 20-player START, PLAYING, and REVEAL layout scrolls vertically without horizontal overflow.

## 14. Implementation phases

### Phase 1: Domain foundation

- Add Sheriff to `GameType` and the sealed `GameState` hierarchy.
- Add immutable Sheriff phase, role assignment, locked roster, and life-state models.
- Add an injectable secure role shuffler.
- Implement role distribution, atomic start, irreversible death, final reveal, participant removal rules for START, and redacted string representations.
- Add focused domain tests for 4–20 participants and all state invariants.

### Phase 2: Room service integration

- Add typed Sheriff selection, Start, death/reveal, End Game, Play Again, Back to Room, and Change Game-compatible operations.
- Reuse Room GM authorization, per-room locking, session IDs, connection state, kick, Close Room, and expiration behavior.
- Add service tests for connected-roster capture, GM self-death, duplicate death, reconnect, stale sessions, and restart preservation.

### Phase 3: Realtime protocol and projections

- Add explicit Sheriff command validation and idempotency metadata.
- Add a Sheriff-specific recipient view to the Room snapshot.
- Advertise only phase-appropriate `allowedActions` to the connected GM.
- Project the public Sheriff, recipient's role, dead roles, life states, and derived Role Status without leaking living secret assignments.
- Add realtime tests comparing GM, Sheriff, non-Sheriff, dead, excluded, disconnected, and reconnected clients.

### Phase 4: Frontend state and authoritative routing

- Add phase-safe Sheriff models and strict untrusted-snapshot normalization.
- Add RoomClient commands and routing for START, PLAYING, and REVEAL.
- Restore the correct phase after refresh/reconnect.
- Reject incompatible or secret-leaking wire shapes instead of rendering them.

### Phase 5: Choose Game and START UI

- Add the Sheriff Choose Game card using `cover.png`.
- Add the responsive START roster, GM controls, availability message, and waiting state.
- Verify minimum-player presence updates and START-only kick behavior.

### Phase 6: PLAYING UI

- Add the public Sheriff display, private hold-to-reveal card, participant life roster, Role Status, GM death/reveal confirmation, and End Game confirmation.
- Add pointer, keyboard, visibility, reduced-motion, realtime, and privacy tests.

### Phase 7: REVEAL and round lifecycle UI

- Add the complete role reveal and final Role Status.
- Add GM Play Again, Back to Room, and Change Game actions controlled by authoritative snapshots.
- Verify that the new START contains no previous round data.

### Phase 8: Full regression and security verification

- Run complete backend and frontend suites.
- Perform multi-client GM and Player tests for every role and phase.
- Audit personalized snapshots, logs, DOM, accessibility metadata, reconnect, and stale commands for role leakage.
- Verify 390px, 768px, and 1440px layouts with 4 and 20 participants.
- Run Angular root and `/drinkduel/` production builds, Maven verify, standalone JAR verification, and Docker/root regression checks required by the deployment architecture.
