# DrinkDuel V1 Design System

## Status and Authority

This is the V1 visual/UX source of truth. [PRODUCT_SPEC.md](PRODUCT_SPEC.md) defines product behavior; [ARCHITECTURE.md](ARCHITECTURE.md) defines state, identity, communication, and secret filtering. Design presents those rules without adding gameplay, permissions, or progression controls.

This document specifies the experience only. It does not implement screens, styles, animations, or image assets.

## Personality and Originality

DrinkDuel is cute, funny, playful, and slightly chaotic, with friendly drunk-party humor. Make a gathering feel charming and approachable through an original collectible art-toy / blind-box-inspired character universe.

Do not copy an existing character, product, brand identity, packaging, logo, or copyrighted character. Collectible-toy inspiration describes proportions, materials, and the feeling of surprise; it does not introduce purchases, rarity, or collection mechanics.

Avoid casino styling, a predominantly nightclub-black UI, excessive neon, cyberpunk treatments, overly childish baby-game styling, clutter, and excessive gradients or effects. Humor must never obscure the next action or turn into cruelty, humiliation, or hostility.

## Mobile-First Layout

Design for a phone held during a social gathering. Important information should be readable at a glance and actions easy to tap with minimal explanation.

- Start with a single-column phone layout. Add columns on wider screens only when card text remains comfortable to read.
- Use cards rather than dense desktop tables. Support 2–20 players without shrinking type or controls to fit everyone on one screen.
- Allow vertical scrolling. Keep primary actions accessible without covering content or the on-screen keyboard.
- Respect device safe areas. Leave space beneath content when bottom navigation or actions are fixed.
- Use approximately 16–20 px page gutters and generous separation between sections. Layout must adapt to narrow screens, tablet, desktop, and text enlargement without horizontal page scrolling.
- Let long content increase card height; do not force uniform heights at the cost of readability.

The screen hierarchy is: current context, main information, next available action, then secondary controls. Decorative characters must not push essential actions out of reach.

## Visual Language

### Palette and Contrast

Use warm off-white or cream foundations with pastel-inspired peach, pink, lavender, mint, and butter-yellow accents. Use dark neutral text on light surfaces for essential information. Pastel colors belong mainly on surfaces and decorative accents, not low-contrast text.

Use one clear primary action treatment, restrained secondary controls, and a visually separate destructive treatment. Status distinctions combine labels and icons with color; they never depend on color alone.

Exact color values may be refined during visual implementation within these constraints. Contrast requirements below are acceptance criteria for the final palette.

### Surfaces, Spacing, and Shadows

Use rounded cards and controls with soft, restrained shadows. Initial sizing guidance: card corners around 20–24 px, controls around 12–16 px, and pill-shaped status chips. Use a consistent spacing scale based on 4 px, with 16–24 px between major groups.

Shadows should gently separate layers rather than create heavy floating panels. Avoid stacking gradients, glow, blur, and multiple shadows on every surface. Dialogs must be distinguishable without visually overwhelming the page.

### Typography

Use a readable rounded sans-serif treatment with full Thai and Latin support. Favor playful headings over novelty fonts for body text. Use a system fallback and avoid blocking rendering on custom fonts.

- Body, form inputs, and action labels: start at 16 px.
- Supporting labels: normally at least 14 px; never shrink essential game information to fit.
- Main headings: approximately 24–32 px on phones.
- Assigned names: prominent, approximately 20–24 px, with wrapping.
- Use comfortable line height around 1.4–1.6 and verify Thai marks are not clipped.

Player-provided names retain their entered spelling and case. Uppercase examples indicate hierarchy, not a requirement to transform user content.

## Character Art Direction

Characters should look like polished 3D collectible figures while remaining optimized 2D images. Use oversized heads, compact bodies, expressive faces, rounded forms, soft materials, playful proportions, expressive poses, and a toy-like finish.

Keep lighting, material finish, framing, and image quality coherent across the collection. Give all 25 characters distinct silhouettes, expressions, poses, or props; do not create a set that feels like recolors of one model.

Possible personality directions include sleepy, overexcited, trying to look sober, hugging an ice bucket, holding two drinks, red-cheeked, confused, dancing, dramatic, exhausted, smug, and innocent-looking troublemaker. These are inspiration, not mandatory character names or a fixed roster.

Characters must remain recognizable at small card sizes. Decorative props must not obscure faces or compete with nickname and game information. No character designs or assets are created in this documentation step.

## V1 Avatar Collection and Assignment

- Provide 25 original avatar characters, with equal random probability for all 25.
- Assign automatically when a new Player identity is created; no rarity, manual selection, or reroll.
- Keep the avatar stable for that Player's lifetime in the Room, including reconnect, Play Again, and future game changes.
- Leaving and joining as a new Player triggers a fresh random assignment, which may produce the same or a different character.
- Backend stores only avatarId. Frontend owns the image assets and maps stable IDs to them.
- Never store image binaries in Room/Game state. Keep the catalog expandable without redesign.

Surprise comes from the character's personality and presentation, not artificial scarcity or a selectable avatar gallery.

### Avatar Reveal

After successful creation of a new Player on join, show a short local reveal before entering the Room:

```text
POP!
[character appears]
วันนี้คุณได้...
[assigned avatar]
Nickname
```

Target approximately 0.7–1.0 seconds. The avatar must come from the server-confirmed assignment. Do not delay the join request or room synchronization to stage the animation, and do not wait for large imagery to load before letting the user enter the Room. No reroll button or extra confirmation is added.

Reconnect and Play Again retain the existing avatar rather than presenting a new draw. Reduced-motion users receive a brief static or subtle-opacity presentation without a forced wait. The latest server phase always takes precedence over a decorative reveal.

## Asset Performance

- Use optimized WebP/AVIF assets with responsive sizes; prefer transparent cutouts where useful.
- Supply small card renditions and larger hero/reveal renditions rather than using full-resolution artwork everywhere.
- Reserve image dimensions or aspect ratio to avoid layout jumps. Keep a lightweight placeholder if an asset is delayed or unavailable.
- Prioritize the visible hero or newly assigned avatar; lazy-load off-screen character images as appropriate.
- Do not unnecessarily preload all 25 large full-resolution images. Reuse cached assets between screens and rounds.
- Do not use Three.js, WebGL, GLB, runtime 3D engines, or large character video files.
- Asset resolution and compression should be checked at actual display size on phones. Detail that cannot be seen should not increase download cost.

## Motion Language

Use lightweight CSS movement, primarily transform and opacity: scale, translate, and rotate. Keep text and interactive controls stable while the character moves.

| Presentation | Motion and behavior |
| --- | --- |
| Avatar reveal | One short pop/scale appearance; approximately 0.7–1.0 seconds when possible. |
| Shuffle | Short playful transition; approximately 0.7–1.0 seconds, without intentionally delaying gameplay. |
| Waiting | Gentle bounce, sway, float, small rotation, or breathing-like scale. Use a slow, subtle loop on a limited decorative element. |
| Got It | Short randomized character celebration; auto-dismiss and allow tap to skip. |
| Roast / group success after End Game | Theatrical arrival, then an understandable waiting presentation until GM continues. Never auto-dismiss this phase. |

Avoid expensive continuous effects, moving entire card lists, flashing, or animating layout dimensions. Honor prefers-reduced-motion by removing looping/bouncing/rotating movement and using static states or brief opacity changes. Essential progress and status remain understandable without animation. Do not add sound or video requirements.

## Home and Joining

### Home

Use an original character or group hero to communicate personality immediately. Keep actions simple:

- Primary: CREATE ROOM and JOIN ROOM.
- Secondary: How to Play?

Do not ask users to choose a game on Home. Create Room leads into the approved Google-authenticated GM flow. How to Play explains existing rules; it does not add onboarding gates or new game settings.

### Join

Preserve the flow: QR / Room ID -> Nickname -> Join. Do not add avatar selection. Show clear field labels and friendly validation; a successful new-player join receives the brief avatar reveal, then the Room.

New joins are allowed only in Lobby. Play Again does not reopen joining. Returning players reconnect through their existing identity without a new nickname identity or random avatar assignment.

### Lobby

Make Room ID, QR joining, and the crew easy to understand. Use the same readable player cards and restrained GM controls. Normal Players may Leave Room here; GM never has a Leave Room action and uses Close Room instead. The UI should not imply that closing a browser transfers ownership or deletes the room.

## Submit Name and Waiting

Provide one simple text input per participant without mandatory categories. The confirmation explains that the submission cannot be edited directly afterward, with Go Back and Confirm Submit actions.

After submission, show progress such as 5 / 8 and a cute animated waiting state. Do not display other submissions. GM may see who has or has not submitted and Reset Submission controls, never the submitted names.

Waiting imagery should feel alive without obscuring progress or permitted actions. Possible short copy:

- “Waiting for the crew...”
- “Waiting for Game Master...”
- “Someone's still getting a drink...”
- “Almost ready to cause trouble...”

Use context-appropriate copy and keep it stable long enough to read; do not rapidly cycle messages. When GM disconnects, the waiting indication must not block player-owned submission, My Clues, search, filtering, or viewing that remains permitted in the current phase.

## Player Cards

Support 2–20 players using a stable card order and readable vertical scrolling or a responsive grid.

For another player's gameplay card, make the assigned secret name the most prominent game information. Show avatar, nickname, and an explicit current status. Keep the nickname visually distinct from the assigned name.

The current user's card clearly shows YOU and ???, alongside avatar and nickname. Never expose their assigned secret or submitted-by attribution in visible text, accessibility labels, image descriptions, tooltips, or hidden markup. Use only authorized data received from the backend.

During active gameplay, do not show Submitted By on any card. Got It never reveals the user's own answer. Distinguish Playing and Got It with readable labels and icons plus restrained color; avoid fading a completed card until its contents become unreadable.

Do not reorder cards when statuses change. Search and filters change visibility only. Do not add turn controls, timers, scores, or rankings.

## Search and Filter

Provide nickname search and filters for players who are still Playing and those who Got It. These are available for gameplay, including small rooms; keep them compact for phones and especially useful in large rooms.

Search may expand from an accessible, labeled search icon. Keep the selected filter obvious with text/shape as well as color. Controls must remain usable with long labels or enlarged text. An empty filtered view should explain that no players match rather than suggesting the room is empty.

## My Clues

Separate My Clues visually from the Players list. Use simple navigation such as Players | My Clues, with clear selected state and enough space above any fixed bottom navigation.

Make its personal, optional nature clear. It must not compete with the main player information or require use before continuing. Clues stay local, are invisible to other players and GM, and are cleared for a new round as defined by Product and Architecture.

## Got It Celebrations

When GM marks a player Got It, all connected devices show a short presentation using multiple randomized visual/text variations. Use the server's presentation cue; visual effects do not determine results or progression.

Possible concepts include a character jumping, raising a glass, holding a trophy, surrounded by stars, or celebrating too hard. The tone is cute, absurd, funny, exaggerated drunk-party humor. A trophy prop does not imply scores or rankings.

Auto-dismiss after a short duration, allow tap to skip, and provide keyboard-accessible dismissal. Do not require interaction or block gameplay for a long period. Afterwards the card clearly shows Got It. Do not reveal secret names or submitter attribution in celebration content.

## End Game and Roast

After GM confirms End Game, show the dedicated Roast experience on every device before answers are revealed. Make it theatrical, cute, funny, teasing, and memorable, never cruel, humiliating, or hostile.

Follow the authoritative final-state priority:

1. If any players remain Playing, feature them first: “LAST ONE!” for exactly one, or a group presentation for multiple players.
2. If everyone Got It, use a group success celebration instead.

Do not invent a last player, ranking, penalty, or score. Keep answers and attribution hidden until Reveal. The entire Roast/group-success phase remains until GM continues; it is not the auto-dismiss Got It celebration.

Only GM sees the continuation control. Label it “Reveal answers” or similarly explicit wording rather than an ambiguous Next. Other players see a friendly animated waiting-for-GM indication.

## Reveal and Next Actions

Reveal first exposes the relationship between player, assigned name, and original submitter. Structure each card as clearly separate labeled roles:

```text
Player: KEN
Assigned name: Doraemon
Created by EARTH
```

Use the assigned name prominently while keeping Player and Created by labels unmistakable. Preserve attribution supplied by the server even when its original submitter was kicked. Provide nickname search for large rooms.

Reveal is social discovery, not a scoreboard, ranking, statistics, or analytics screen. All normal Players wait for GM's next action. Present Play Again and Back to Room clearly; keep Close Room secondary. V1 may omit Change Game or mark it Coming Soon without offering an unimplemented action.

Play Again immediately begins Submit Name with the same avatars and no new joins. Back to Room reopens Lobby joining and normal Players' Leave Room action.

## GM Controls and Confirmations

GM controls should be recognizable but not dominate the game. Show only controls permitted in the current phase: Got It, Reset status, Reset Submission, Kick, Shuffle, End Game, Reveal answers, Play Again, Back to Room, and Close Room.

- GM may mark themselves Got It but cannot kick themselves or voluntarily Leave Room.
- A result correction returns the player from Got It to Playing before another result is applied.
- Confirm submission, End Game, and Close Room as required by Product. Do not add mandatory confirmation flows to other actions without a product requirement.
- Keep Close Room visually separate from primary game actions so it is not an easy accidental tap.
- Disabled actions should have a short explanation, such as waiting for submissions or needing another player.
- If fewer than 2 players remain before Shuffle, GM can Back to Room or Close Room. Do not display an enabled Shuffle control.
- GM disconnection makes GM controls unavailable while leaving permitted player-owned actions usable.

## Errors and Special States

Lead with understandable product language and a clear permitted next step, not internal status codes or technical exception messages.

| Situation | Presentation |
| --- | --- |
| Game in progress | “They're playing right now.” Explain that joining reopens when GM returns to the Room/Lobby. Do not promise that the next round allows joining. |
| Expired Room | “Party's over! 🍻💤” / “This room has expired.” / “ถึงเวลาตั้งวงใหม่แล้ว”. Provide Back Home; a new Room is needed. |
| Not enough players | “หาเพื่อนมาอีกคนก่อนน้า 🍻”. Show only the recovery actions allowed to the current user. |
| GM disconnected | “Waiting for Game Master... 🍺” with subtle character movement. Preserve current phase and permitted interactions. |
| Room full / nickname taken | Brief factual explanation close to the relevant join field/action. |
| Room unavailable / kicked | Friendly clear explanation matching the server result; never imply reconnect or nickname changes can bypass a kick. |

Expiration is a fixed 48 hours from creation. Presentation must not imply activity extends the room's life. Manual Close Room returns everyone Home, whereas expiration shows its themed screen with Back Home.

## Accessibility and Usability Requirements

- Use the readable type sizes and line heights above; allow browser zoom and text enlargement.
- Target contrast of at least 4.5:1 for normal text and 3:1 for large text. Essential control boundaries, state indicators, and focus outlines should achieve at least 3:1 against adjacent colors.
- Provide tap targets at least 44 by 44 CSS px, with separation between adjacent actions. An icon may be smaller inside that target.
- Use semantic controls, persistent form labels, visible keyboard focus, and logical focus order. Icon-only buttons need accessible names.
- Confirmations must have a clear title, action, and cancellation control; manage focus within the dialog and return it appropriately on close.
- Announce meaningful changes such as submission completion, result updates, and connection waiting without repeatedly announcing decorative motion or rapidly changing copy.
- Combine status text/icons with color; do not rely on color, animation, or avatar identity alone.
- Honor prefers-reduced-motion across reveals, waiting loops, Shuffle, and celebrations. Avoid flashing and do not hide essential information behind motion.
- Give meaningful imagery concise alternative text where needed; decorative characters should not create repetitive screen-reader announcements. Never put secret data into alternate text.
- Wrap long nicknames and long secret names, including unbroken strings, with appropriate overflow wrapping. Preserve complete readable content rather than permanent ellipses or smaller text. Keep attribution labels attached to the correct person.
- Test narrow layouts, Thai and Latin text, enlarged text, keyboard operation, 20-player rooms, and slow/missing images. No essential control should be covered or pushed into inaccessible horizontal overflow.

## Alignment and Implementation Readiness

The avatar count is 25 across Product, Architecture, and Design; equal-probability assignment, stable identity, no reroll, and frontend-only image storage are consistent. The design preserves Lobby-only joining, GM authority, secret visibility, local clues, result corrections, Roast timing, Reveal attribution, and fixed expiration.

Exact character artwork, color values, font selection, and celebration copy can be produced during implementation within this specification; they do not require new product behavior or block V1 work. No unresolved design decision blocks the specified V1 implementation.

A first-time user should understand what to do without someone teaching the interface. The characters create personality. The cards organize information. The GM controls progression. The people in the room create the actual fun.
