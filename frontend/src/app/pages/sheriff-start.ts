import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

@Component({
  imports: [Avatar],
  styleUrl: './sheriff-start.scss',
  template: `
    <section class="sheriff-start page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (room(); as room) {
        <main class="start-shell">
          <nav class="game-nav" aria-label="Room navigation">
            <span class="game-label">SHERIFF</span>
            <div class="room-context" aria-label="Current room code">
              <span>ROOM</span>
              <strong>{{ room.roomId }}</strong>
            </div>
          </nav>

          <header class="game-heading">
            <div class="western-mark" aria-hidden="true">★</div>
            <div class="round-copy">
              <p class="eyebrow">THE TOWN IS GATHERING</p>
              <h1 id="start-title">Who can you trust?</h1>
              <p>
                Start with 4–20 connected players. Roles stay secret, and the Sheriff steps into the
                spotlight.
              </p>
            </div>
            <span class="role-chip">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
          </header>

          <section class="start-panel" aria-labelledby="start-title">
            <div class="roster-heading">
              <div>
                <p class="eyebrow">TOWN ROSTER</p>
                <h2>Ready for the showdown</h2>
                <p>Only connected players receive a role when the Game Master starts.</p>
              </div>
              <div class="roster-counts" aria-label="Room presence">
                <strong>{{ connectedPlayerCount() }}</strong>
                <span>connected</span>
                <small>{{ room.players.length }} / {{ room.capacity }} in room</small>
              </div>
            </div>

            <ul class="player-list" aria-label="Players in the Sheriff room">
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
                  [disabled]="!client.can('GM_START_SHERIFF')"
                  (click)="startGame()"
                >
                  <span aria-hidden="true">★</span>
                  {{ client.busy() ? 'Dealing roles…' : 'Start Game' }}
                </button>
                @if (!client.can('GM_START_SHERIFF') && !client.busy()) {
                  <p class="start-help" role="status">
                    At least 4 connected players are needed to start.
                  </p>
                }
                @if (room.allowedActions.includes('GM_BACK_TO_ROOM')) {
                  <div class="secondary-actions">
                    <button
                      class="back-to-room"
                      type="button"
                      [disabled]="!client.can('GM_BACK_TO_ROOM')"
                      (click)="client.backToRoom()"
                    >
                      <span aria-hidden="true">←</span> Back to Room
                    </button>
                    <button
                      class="choose-another"
                      type="button"
                      [disabled]="!client.can('GM_BACK_TO_ROOM')"
                      (click)="client.chooseAnotherGame()"
                    >
                      Choose Another Game
                    </button>
                  </div>
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
        <main class="loading" role="status">Restoring your place in town…</main>
      }
    </section>
  `,
})
export class SheriffStart {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly room = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.sheriff?.phase === 'START' ? room : null;
  });
  readonly connectedPlayerCount = computed(
    () =>
      this.room()?.players.filter((player) => player.connectionStatus === 'CONNECTED').length ?? 0,
  );

  constructor() {
    this.client.resume(this.roomId);
  }

  startGame(): void {
    if (this.room()?.sheriff?.phase === 'START') this.client.startSheriff();
  }
}
