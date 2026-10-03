import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { PlayerView } from '../core/room.models';
import { Avatar } from '../shared/avatar';
import { DieFace } from '../shared/die-face';

@Component({
  imports: [Avatar, DieFace],
  styleUrl: './liars-dice-reveal.scss',
  template: `
    <section class="dice-reveal page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (room(); as room) {
        <main class="reveal-shell">
          <nav class="game-nav" aria-label="Round context">
            <span class="game-label">ROUND REVEAL</span>
            <div class="room-context" aria-label="Current room code">
              <span>ROOM</span><strong>{{ room.roomId }}</strong>
            </div>
          </nav>

          <header class="game-heading">
            <img
              class="game-logo"
              src="/assets/drinkduel/games/liars-dice/liars-dice-logo.png"
              width="1536"
              height="1024"
              alt="Liar's Dice"
            />
            <div class="round-copy">
              <p class="eyebrow">EVERY HAND IS PUBLIC</p>
              <h1>Show your dice.</h1>
              <p>The cups are up. See every hand and count the whole table.</p>
            </div>
            <span class="role-chip">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
          </header>

          <section class="counts-panel" aria-labelledby="counts-title">
            <div class="section-heading count-heading">
              <div>
                <p class="eyebrow">TABLE COUNTS</p>
                <h2 id="counts-title">What the table is showing</h2>
              </div>
              <p><strong>1s are wild</strong> for faces 2–6.</p>
            </div>
            <ul class="count-grid">
              @for (count of counts(); track count.face; let index = $index) {
                <li
                  class="count-card"
                  [attr.data-count-face]="count.face"
                  [style.--reveal-index]="index"
                >
                  <dd-die-face class="count-die" [value]="count.face" />
                  <div class="count-values">
                    <span
                      ><small>RAW COUNT</small><strong>{{ count.rawCount }}</strong></span
                    >
                    <span class="effective">
                      <small>WITH WILD 1s</small><strong>{{ count.effectiveCount }}</strong>
                    </span>
                  </div>
                  @if (count.face === 1) {
                    <p>1s stay 1 — no double counting.</p>
                  } @else {
                    <p>Includes every wild 1.</p>
                  }
                </li>
              }
            </ul>
          </section>

          <footer class="reveal-actions">
            @if (room.isGm && room.allowedActions.includes('GM_RESTART_LIARS_DICE')) {
              <button
                class="play-again"
                type="button"
                [disabled]="!client.can('GM_RESTART_LIARS_DICE')"
                (click)="restart()"
              >
                <span aria-hidden="true">↻</span>
                {{ client.busy() ? 'Starting…' : 'Start New Round' }}
              </button>
            } @else {
              <p class="waiting" role="status">
                <span aria-hidden="true"></span> Waiting for the Game Master to start another round.
              </p>
            }
          </footer>

          <section class="hands-panel" aria-labelledby="hands-title">
            <div class="section-heading">
              <div>
                <p class="eyebrow">LOCKED ROUND ROSTER</p>
                <h2 id="hands-title">Hands on the table</h2>
              </div>
              <span>{{ hands().length }} players</span>
            </div>
            <ol class="hands-grid">
              @for (hand of hands(); track hand.playerId; let index = $index) {
                @if (player(hand.playerId); as participant) {
                  <li class="hand-card" [style.--reveal-index]="index">
                    <header class="player-heading">
                      <dd-avatar [id]="participant.avatarId" />
                      <div class="identity">
                        <h3 [title]="participant.nickname">{{ participant.nickname }}</h3>
                        <div class="badges">
                          @if (participant.playerId === room.gmPlayerId) {
                            <span class="gm-badge"
                              ><span aria-hidden="true">♛</span> Game Master</span
                            >
                          }
                          @if (participant.playerId === room.currentPlayerId) {
                            <span class="you">YOU</span>
                          }
                        </div>
                      </div>
                    </header>
                    <div
                      class="hand-dice"
                      [attr.aria-label]="participant.nickname + ' revealed hand'"
                    >
                      @for (die of hand.dice; track $index) {
                        <dd-die-face [value]="die" />
                      }
                    </div>
                  </li>
                }
              }
            </ol>
          </section>

          @if (client.notice()) {
            <p class="notice" role="status">{{ client.notice() }}</p>
          }
        </main>
      } @else {
        <main class="loading" role="status">Restoring the table reveal…</main>
      }
    </section>
  `,
})
export class LiarsDiceReveal {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly room = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.liarsDice?.phase === 'REVEAL' ? room : null;
  });
  readonly hands = computed(() => {
    const game = this.room()?.liarsDice;
    return game?.phase === 'REVEAL' ? game.revealedHands : [];
  });
  readonly counts = computed(() => {
    const game = this.room()?.liarsDice;
    return game?.phase === 'REVEAL' ? game.revealCounts : [];
  });

  constructor() {
    this.client.resume(this.roomId);
  }

  player(playerId: string): PlayerView | null {
    return this.room()?.players.find((candidate) => candidate.playerId === playerId) ?? null;
  }

  restart(): void {
    if (this.room()?.liarsDice?.phase === 'REVEAL' && this.client.can('GM_RESTART_LIARS_DICE')) {
      this.client.restartLiarsDice();
    }
  }
}
