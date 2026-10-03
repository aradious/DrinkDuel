# DrinkDuel V1 Product Specification

## Document Status

This document is the source of truth for DrinkDuel V1 product behavior. The rules below are confirmed. Unspecified details remain undecided and must not be treated as additional product rules.

## PRODUCT CONCEPT

DrinkDuel is a mobile-first real-time party game for groups of friends.

The application should assist the group, not control how people play.
The players themselves manage conversation, turn order, timing, and social rules.

The system mainly:
- manages the room
- distributes game information
- tracks game state
- synchronizes everyone
- provides fun visual moments

V1 supports two games:

- Who Am I, specified in this document.
- Liar's Dice, specified in [LIARS_DICE_SPEC.md](LIARS_DICE_SPEC.md).

A Room is NOT tied to a specific game.
In the future, the GM can change games while keeping the same room and players.

## ROLES

### Game Master (GM)

- GM is also a player.
- GM uses a backend-issued anonymous Host identity stored in a secure browser session.
- GM creates and controls the room.
- GM follows the same game-information rules as normal players.
- GM must NOT see secret submitted names before Shuffle.
- GM can kick players.
- GM can reset a player's submitted name before Shuffle.
- GM marks players as Got It.
- GM may mark themselves as Got It.
- GM ends the round.
- GM determines the next action after the round ends.
- GM has no voluntary Leave Room action, including in the Lobby. To permanently end the Room, GM must use Close Room.
- There is no GM transfer in V1.

### Player

- Guest access. No account required.
- Joins using QR code or Room ID.
- Enters a unique nickname.
- Receives a random avatar automatically.
- Can reconnect using the same local guest identity.
- Can voluntarily Leave Room only in the Lobby.
- Cannot control room/game progression.

## ROOM

- Room ID must be short and easy to type.
- Maximum room lifetime: a fixed 48 hours from room creation time. Activity does not extend the TTL.
- V1 rooms exist only in server memory.
- Server restart may destroy active rooms. This is acceptable for V1.
- GM may close a room manually.
- Closing a room removes it from memory.
- One room can be used for multiple rounds and, in the future, multiple games.
- Room ID and QR remain the same between rounds.
- Who Am I requires at least 2 active players to start. Check this when starting a Game Session and before Shuffle.
- Maximum players: 20.
- Nicknames must be unique within a room.

After Shuffle, if the game drops below 2 players because of kicks/disconnections, do not crash or automatically terminate the round. GM may decide to End Game. Before Shuffle, the minimum-player rules in Submit Name apply.

### Room Expiration

After 48 hours from room creation:

- Remove the Room from server memory.
- The Room ID becomes invalid.
- Inform connected clients that the room expired.
- Show a friendly themed expiration screen rather than a technical error.
- Provide a clear Back Home action.

Example presentation:

> Party's over! 🍻💤
>
> This room has expired.
>
> ถึงเวลาตั้งวงใหม่แล้ว

A new Room must be created to continue playing. The fixed expiration applies even during a game or while the GM is disconnected.

## JOINING

Normal player flow:

QR / Room ID
→ Enter Nickname
→ Join Room

There is no avatar-selection screen.

When a new Player identity is created, the server randomly assigns one of 25 V1 avatar characters, each with equal probability. There is no rarity system, manual avatar selection, or reroll.

The avatar remains stable for that Player's lifetime in the Room. Reconnect, Play Again, and future game changes keep the same avatar. Leaving and later joining as a NEW Player triggers a new random assignment, which may produce a different avatar.

The avatar system must allow additional characters later without redesign. The backend stores only avatarId; images remain frontend assets, never image binaries in Room/Game state.

New players may join ONLY while the Room is in the Lobby / Room state, until the room reaches 20 players.

Once GM opens Who Am I and Submit Name begins:
- New players cannot join throughout Submit Name, Shuffle, Playing, Roast, and Reveal.
- Show a friendly/cute "Game in progress" waiting screen instead of a technical error.
- Play Again starts a new Submit Name phase immediately and does not reopen joining.
- To add new players, GM must choose Back to Room first. The Lobby becomes joinable again.

Existing disconnected players may reconnect to their existing player identity even when new joins are closed.

## LEAVE / RECONNECT

If a player closes the app/browser or loses connection:
- Keep their player and game state.
- Mark them disconnected internally.
- They can return through the same QR code or Room ID.
- Use their local Guest Token / Guest ID to restore the same player.

Do not use nickname alone for identity.

Voluntary Leave Room is available ONLY to normal Players in the Lobby. GM never has this action. Once Who Am I enters Submit Name, no Leave Room action is provided during Submit Name, Shuffle, Playing, Roast, or Reveal.

Closing the app/browser or losing connection is treated as DISCONNECTED. Preserve the player and game state and allow reconnection as the same player using their Guest identity.

In the Lobby, if a normal Player intentionally chooses Leave Room:
- Remove them from the room.
- If they join again later, treat them as a new player.

When GM chooses Back to Room and everyone returns to the Lobby, Leave Room becomes available again for normal Players only.

## GM DISCONNECT

If GM closes the browser/app, loses connection, or temporarily leaves, treat this as disconnection. Preserve the Room and GM ownership, and allow the same authenticated Host session to reconnect and resume control. This does not transfer ownership or permanently close the Room.

If GM disconnects:
- Do not end the game.
- Do not delete the room.
- Do not discard or reset the Room or Game Session; preserve them subject to the fixed room expiration.
- Freeze shared game-changing actions that require GM authority.
- Other players remain in the current game phase; permitted player-owned actions may continue.
- Show a friendly animated message such as "Waiting for Game Master... 🍺".
- The original GM can return to the room.
- GM identity must be verified using the same backend-issued Host session.
- Room ID alone must NOT grant GM privileges.

Player-owned actions that do not require GM authority may continue. Players may still:

- Submit and confirm their own secret name.
- View/edit their local My Clues.
- View player cards and scroll.
- Search and filter players.

GM-controlled actions are unavailable until the GM reconnects:

- Reset Submission
- Kick Player
- Shuffle
- Mark Got It
- Reset Got It status
- End Game
- Continue from Roast to Reveal
- Choose the next action after Reveal
- Close Room

Show the friendly animated "Waiting for Game Master... 🍺" message. Do not show a technical connection error unless there is an actual unrecoverable system error.

If the GM never returns:
- GM privileges cannot be transferred.
- Someone else must authenticate as a GM and create a new room if the group wants to continue.

## KICK

GM can kick a player.

GM may kick players in any pre-game status.

GM may also kick players after gameplay has started.

A kicked player's existing Guest Token must not be allowed to reconnect to that same room.

GM cannot kick themselves.

If GM kicks a player after Shuffle:

- Remove that player from the active Game Session.
- Discard that player's assignment.
- Do not redistribute or reshuffle names for remaining players.
- Do not change anyone else's assigned name.
- Preserve the original submitter nickname for names already assigned to other players, as temporary Game Session attribution data for Reveal.

## ROOM / GAME RELATIONSHIP

Room and Game Session are separate concepts.

Room retains:
- Room ID
- GM
- Players
- Nicknames
- Avatars

Game Session contains temporary game-specific state.

When starting another round/game, the Room remains.

The Lobby is the Room membership and management screen. It contains the Room Code, QR code,
players, avatars, connection state, and the permitted Kick, Leave, Close Room, and Choose Game
controls. It does not contain game-specific setup or gameplay state.

Choose Game is a separate game-catalog screen for the current Room. Only the GM selects a game
in V1. Normal Players wait for the GM. Selecting a game does not create a new Room or replace Room
membership. Selecting Who Am I creates its Game Session and begins the Submit Name phase. The
Liar's Dice selection and round flow follow [LIARS_DICE_SPEC.md](LIARS_DICE_SPEC.md).

## WHO AM I — START

GM opens Who Am I.

Check the minimum of 2 active players when starting the Game Session. Opening Who Am I begins Submit Name and closes new joins.

Every participant, including the GM, must submit exactly one secret name.

Every player sees a simple text input.

Examples may include:
- celebrity
- friend
- fictional character
- anything the group wants

Do NOT require categories.

## SUBMIT NAME

Duplicate submitted name text is allowed. For example, Aood and Ken may each submit "Doraemon". Each is a distinct submission owned by its submitting player; submitted text is not identity.

Before final submission, show a confirmation dialog.

The confirmation must clearly say that the submission cannot be edited directly afterward.

Player can:
- Go Back
- Confirm Submit

After confirmation:
- Player cannot edit the submission.
- Show a cute animated waiting state.
- Player can see overall submission progress such as 5 / 8.
- Player must NOT see other people's submitted names.

GM receives the SAME secret-information rules as players.

GM can see:
- who has submitted
- who has not submitted
- total submission progress

GM must NOT see:
- what name anyone submitted

Before Shuffle, GM may reset a player's submission.

Reset Submission:
- deletes that player's existing submitted name
- returns that player to the Submit Name screen
- requires them to submit and confirm again

Shuffle cannot start until at least 2 active players remain, every remaining player in the current Game Session has a valid submission, and no remaining member is disconnected.

A disconnected player who is still a member of the current Game Session blocks Shuffle, even if they already submitted. GM must either wait for that player to reconnect or kick them.

After a kick, recalculate the player count and whether all remaining players have submitted. Shuffle becomes available only when at least 2 active players remain, every remaining player has a valid submission, and none is disconnected.

If someone will not participate, GM can kick that player before Shuffle.

### Fewer Than 2 Players Before Shuffle

If kicking players during Submit Name leaves fewer than 2 players:

- Shuffle is disabled.
- The round cannot start.
- Never assign a player's own submission to themselves.
- Show a friendly message such as "หาเพื่อนมาอีกคนก่อนน้า 🍻".
- GM can choose Back to Room to wait for more players, or Close Room.

## SHUFFLE

Only GM can trigger Shuffle.

Shuffle must assign submitted names among players.

A player must NEVER receive THEIR OWN submission. Receiving an identically-worded submission created by another player is valid. Match ownership by the submitting player, not by the submitted text.

After Shuffle, transition into gameplay using a short playful animation.

The transition should feel responsive and should not intentionally delay gameplay.
Target presentation duration should be approximately 0.7–1.0 seconds when possible.

## PLAYING

DrinkDuel does NOT control turn order.

There is:
- no Wrong button
- no Next Turn button
- no timer
- no enforced first player
- no enforced turn sequence

The group decides all of this socially.

The application only manages information and player results.

## PLAYER CARDS

Gameplay displays players as easy-to-read cards.

For another player, show:
- Avatar
- Nickname
- Assigned secret name
- Current result/status

For the current user's own card:
- Avatar
- Nickname
- clearly indicate YOU
- assigned name must display as ???
- identity of the person who submitted their assigned name must also remain hidden

During active gameplay, DO NOT display who originally submitted each assigned name.

That information is revealed only after End Game.

Cards must remain readable for rooms from 2 to 20 players.

## SEARCH AND FILTER

Gameplay should provide player search by nickname.

Provide filters:

- All
- Playing
- Got It

Do not automatically reorder cards when a player's status changes.
Filtering changes visibility, not the underlying display order.

## GOT IT

GM controls Got It status.

GM can mark any player who is PLAYING as Got It, including themselves.

When GM marks someone Got It:
- server updates the shared state
- all connected clients receive the update
- show a short celebration presentation on every device

Celebrations should have multiple randomized visual/text variations.

Tone:
- cute
- funny
- playful
- exaggerated drunk-party humor

Celebration should auto-dismiss after a short period.
User may tap to skip it.

Afterward the player's card clearly shows Got It.

Before End Game, GM can correct/undo an accidental Got It by resetting that player's status to PLAYING.

Player gameplay states are conceptually:

PLAYING
GOT_IT

### Result Transitions

Valid direct result transitions:

- PLAYING -> GOT_IT

To correct a mistake before End Game, GM resets GOT_IT back to PLAYING. A new result may then be applied through the normal action and permissions.

## MY CLUES

Each player has an optional My Clues view.

It must be separate from the main Players view so the main gameplay screen remains clean.

Suggested navigation:
Players | My Clues

My Clues:
- belongs only to the local player
- other players cannot see it
- GM cannot see it
- does not need to be sent through WebSocket
- does not need to be stored on the server
- may use local browser storage or equivalent local state
- is cleared when Play Again starts a new round

My Clues is optional.
The player must never be required to use it.

## END GAME

Only GM can End Game.

End Game requires confirmation to prevent accidental taps.

The round does NOT automatically end when everyone gets Got It.

GM always decides when to end the round.

After confirmation, all devices enter the end-game presentation together.

## ROAST SCREEN

Do NOT immediately show the full reveal.

First show a dedicated funny/cute Roast screen on every device.

This screen remains visible until GM chooses to continue.
It must NOT auto-dismiss.

At End Game, derive the presentation from the final round state using this priority:

1. If one or more players remain PLAYING, roast them first.
   - If exactly one remains PLAYING, highlight them with a "Last One" style presentation, playful teasing, and a cute/funny character.
   - If more than one remains PLAYING, present them together. Do not falsely choose a single last player.
2. If every player is GOT_IT, show a group success celebration instead of a Roast. This presentation follows the same GM-controlled continuation to Reveal; it does not auto-dismiss.

No scores or rankings are created from these presentations.

Only GM sees the control to proceed.

Other players see a friendly animated Waiting for GM state/control indication.

## REVEAL SCREEN

After GM proceeds from Roast, show the Reveal screen to everyone.

This is when submitted-by information becomes visible.

For every player clearly show:

- Player nickname
- Assigned secret name
- Who originally submitted that name

The UI must make the relationship unambiguous.

For example:

KEN
Doraemon
Created by EARTH

Avoid layouts that could make users confuse the assigned player with the person who created the name.

For large rooms, provide nickname search.

No scoring, ranking, statistics, or persistent game history is required.

### Kicked Submitter Attribution

If a submitted name was assigned to another player during Shuffle, its attribution must survive even if the original submitter is later kicked.

Example: Aood submits "Batman", Batman is assigned to Ken, and Aood is later kicked. At Reveal, still show:

```text
KEN
Batman
Created by AOOD
```

Preserve the submitter nickname as temporary Game Session attribution data. It exists only for the current round and is deleted when the round is reset or Play Again starts. It is not persistent history.

## AFTER REVEAL

All players wait for the GM to determine the next action.

GM controls progression.

Available concepts:

### Play Again

- Keep the same Room.
- Keep players.
- Keep nicknames.
- Keep avatars.
- Keep Room ID and QR.
- Destroy/reset the previous Who Am I game state.
- Clear submitted names.
- Clear assignments.
- Clear temporary submitter attribution data.
- Clear Got It states.
- Clear local My Clues.
- Every remaining player must submit a NEW secret name.
- Start a fresh Who Am I round by entering Submit Name immediately, subject to the minimum-player check for starting a Game Session.
- New players still cannot join. GM must choose Back to Room to reopen joining.

### Back to Room

Return everyone to the Room/Lobby.

The Lobby becomes joinable again, up to the room limit of 20 players.

Leave Room becomes available again for normal Players only; GM uses Close Room to permanently end the Room. Back to Room is also available to GM during Submit Name if kicking players leaves fewer than 2 players.

GM can manage the group before starting another game/round.

### Choose Another Game

- Keep the same Room, Room Code, GM, players, nicknames, and avatars.
- Finish and remove the current Game Session.
- Navigate to the separate Choose Game catalog.
- Allow the GM to select a game again.

V1 supports Who Am I and Liar's Dice. This action remains distinct from Play Again and Back to Room
so additional games can be added without redesigning the Lobby.

### Close Room

GM can close the room.

Require confirmation.

After closing:
- delete room from server memory
- all clients return to Home

## UX PRINCIPLE

The application should never feel like it is controlling the party.

It should:
- make joining extremely easy
- make game information easy to understand
- synchronize the group
- remember simple state
- create funny shared moments

It should NOT force:
- turn order
- timers
- penalties
- conversation rules
- complicated settings

Internal technical states such as DISCONNECTED or IN_PROGRESS should not normally be shown directly to users.

Use friendly human language such as:
- Waiting for the Game Master...
- Game in progress!
- Ready to play!
- Waiting for the crew...

Keep the product understandable for someone using DrinkDuel for the first time.

Keep implementation simple and predictable. DrinkDuel manages state and synchronization, but the people in the room control how they actually play.
