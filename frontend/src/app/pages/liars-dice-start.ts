import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

@Component({
  imports: [Avatar],
  styleUrl: './liars-dice-start.scss',
  template: `
    <section class="dice-start page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (client.room(); as room) {
        <main class="start-shell">
          <nav class="game-nav" aria-label="Room navigation">
            <span class="game-label">LIAR'S DICE</span>
            <div class="room-context" aria-label="Current room code">
              <span>ROOM</span>
              <strong>{{ room.roomId }}</strong>
            </div>
          </nav>
          <header class="game-heading">
            <img
              class="game-logo"
              src="assets/drinkduel/games/liars-dice/liars-dice-logo.png"
              width="1536"
              height="1024"
              alt="Liar's Dice"
            />
            <div class="round-copy">
              <p class="eyebrow">THE CREW IS READY</p>
              <h1 id="start-title">Roll, bluff, survive.</h1>
              <p>
                Everyone gets five private dice. Keep yours hidden and make the table believe you.
              </p>
            </div>
            <span class="role-chip">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
          </header>

          <section class="start-panel" aria-labelledby="start-title">
            <div class="roster-heading">
              <div>
                <p class="eyebrow">PLAYERS</p>
                <h2>At the table</h2>
              </div>
              <span>{{ room.players.length }} / {{ room.capacity }}</span>
            </div>

            <ul class="player-list" aria-label="Players at the table">
              @for (player of room.players; track player.playerId) {
                <li
                  class="player-card"
                  [class.self]="player.playerId === room.currentPlayerId"
                  [class.disconnected]="player.connectionStatus === 'DISCONNECTED'"
                >
                  <dd-avatar [id]="player.avatarId" />
                  <div class="identity">
                    <h3 [title]="player.nickname">
                      {{ player.nickname }}
                      @if (player.playerId === room.currentPlayerId) {
                        <span class="you">YOU</span>
                      }
                    </h3>
                    <p>
                      @if (player.playerId === room.gmPlayerId) {
                        <span class="gm-badge"><span aria-hidden="true">♛</span> Game Master</span>
                      }
                      <span
                        class="connection"
                        [class.offline]="player.connectionStatus === 'DISCONNECTED'"
                      >
                        {{ player.connectionStatus === 'CONNECTED' ? 'Connected' : 'Disconnected' }}
                      </span>
                    </p>
                  </div>
                  @if (
                    room.isGm &&
                    player.playerId !== room.gmPlayerId &&
                    room.allowedActions.includes('GM_KICK_PLAYER')
                  ) {
                    <button
                      class="remove-player"
                      type="button"
                      [disabled]="!client.can('GM_KICK_PLAYER')"
                      (click)="client.action('GM_KICK_PLAYER', player.playerId)"
                      [attr.aria-label]="'Remove ' + player.nickname"
                    >
                      Remove
                    </button>
                  }
                </li>
              }
            </ul>

            <footer class="start-actions">
              @if (room.isGm) {
                <button
                  class="start-game"
                  type="button"
                  [disabled]="!client.can('GM_START_LIARS_DICE')"
                  (click)="startGame()"
                >
                  <span aria-hidden="true">◆</span>
                  {{ client.busy() ? 'Starting…' : 'Start Game' }}
                </button>
                @if (!client.can('GM_START_LIARS_DICE') && !client.busy()) {
                  <p class="start-help" role="status">Waiting for at least 2 connected players.</p>
                }
                @if (room.isGm && room.allowedActions.includes('GM_BACK_TO_ROOM')) {
                  <button
                    class="choose-another"
                    type="button"
                    [disabled]="!client.can('GM_BACK_TO_ROOM')"
                    (click)="client.chooseAnotherGame()"
                  >
                    <span aria-hidden="true">←</span> Choose Another Game
                  </button>
                }
              } @else {
                <p class="waiting" role="status">
                  <span aria-hidden="true"></span> Waiting for the Game Master to start…
                </p>
              }
            </footer>
          </section>

          @if (client.notice()) {
            <p class="notice" role="status">{{ client.notice() }}</p>
          }
        </main>
      } @else {
        <main class="loading" role="status">Restoring your seat at the table…</main>
      }
    </section>
  `,
})
export class LiarsDiceStart {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly onAuthoritativeStart = computed(
    () =>
      this.client.room()?.roomId === this.roomId &&
      this.client.room()?.liarsDice?.phase === 'START',
  );

  constructor() {
    this.client.resume(this.roomId);
  }

  startGame(): void {
    if (this.onAuthoritativeStart()) this.client.startLiarsDice();
  }
}
