import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { RoomClient } from '../core/room-client';

@Component({
  imports: [RouterLink],
  styleUrl: './choose-game.scss',
  template: ` <section class="choose-page page-enter">
    <div class="choose-scene" aria-hidden="true"></div>
    <div class="choose-shell">
      <nav class="choose-nav" aria-label="Room navigation">
        <a [routerLink]="['/room', roomId]" class="back-to-room">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7"></path></svg>
          Back to Room
        </a>
        @if (client.room(); as room) {
          <div class="room-context" aria-label="Current room">
            <span>ROOM</span><strong>{{ room.roomId }}</strong>
          </div>
        }
      </nav>

      <header class="choose-header">
        <p class="eyebrow">CHOOSE A GAME</p>
        <h1>What are we playing<span>?</span></h1>
        <p>Pick a game and let the chaos begin.</p>
        <strong>Same crew. Same room. New game.</strong>
      </header>

      @if (client.room(); as room) {
        @if (room.lifecycle === 'LOBBY') {
          <section class="library" aria-labelledby="library-title">
            <div class="library-heading">
              <div>
                <p class="eyebrow">GAME LIBRARY</p>
                <h2 id="library-title">Pick your chaos</h2>
              </div>
              <p>More games coming soon.</p>
            </div>

            <div class="game-grid">
              @for (game of games; track game.id) {
                <article class="game-card">
                  <div class="game-art">
                    <img
                      [src]="game.artwork"
                      width="1680"
                      height="945"
                      [alt]="game.title + ' game artwork'"
                    />
                    <span>AVAILABLE NOW</span>
                  </div>
                  <div class="game-body">
                    <div>
                      <h3>{{ game.title }}</h3>
                      <p>{{ game.description }}</p>
                    </div>
                    <ul class="game-meta" aria-label="Game details">
                      @for (detail of game.metadata; track detail) {
                        <li>{{ detail }}</li>
                      }
                    </ul>

                    @if (room.isGm) {
                      <button
                        class="play-game"
                        type="button"
                        [disabled]="!canSelect(game.id)"
                        (click)="selectGame(game.id)"
                      >
                        <span class="play-icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24"><path d="m9 7 8 5-8 5Z"></path></svg>
                        </span>
                        {{ client.busy() ? 'Starting…' : 'Play This Game' }}
                        <svg class="arrow" viewBox="0 0 24 24" aria-hidden="true">
                          <path d="m9 5 7 7-7 7"></path>
                        </svg>
                      </button>
                      @if (!canSelect(game.id) && !client.busy()) {
                        <p class="start-help">At least two connected players are needed to start.</p>
                      }
                    } @else {
                      <p class="player-waiting" role="status">
                        <span aria-hidden="true"></span> Waiting for Game Master…
                      </p>
                    }
                  </div>
                </article>
              }
            </div>
          </section>
        } @else {
          <section class="state-panel">
            <h2>Who Am I has started</h2>
            <p>Your crew is moving into game setup.</p>
          </section>
        }
      } @else {
        <section class="state-panel">
          <p>Loading the room…</p>
        </section>
      }

      @if (client.notice()) {
        <p class="notice" role="status">{{ client.notice() }}</p>
      }
    </div>
  </section>`,
})
export class ChooseGame {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly roomReady = computed(() => this.client.room()?.roomId === this.roomId);
  readonly connectedPlayerCount = computed(
    () =>
      this.client.room()?.players.filter((player) => player.connectionStatus === 'CONNECTED')
        .length ?? 0,
  );
  readonly games = [
    {
      id: 'WHO_AM_I',
      title: 'Who Am I?',
      description: 'Guess the name everyone can see but you.',
      metadata: ['2–20 Players', 'Talking', 'Party'],
      artwork: 'assets/drinkduel/games/who-am-i.webp',
    },
    {
      id: 'LIARS_DICE',
      title: "Liar's Dice",
      description: 'Roll in secret, bluff with confidence, and call the crew out.',
      metadata: ['2–20 Players', 'Bluffing', 'Party'],
      artwork: 'assets/drinkduel/games/liars-dice/liars-dice-bg.webp',
    },
  ] as const;

  constructor() {
    this.client.resume(this.roomId);
  }

  canSelect(gameId: (typeof this.games)[number]['id']): boolean {
    if (this.connectedPlayerCount() < 2) return false;
    return gameId === 'WHO_AM_I'
      ? this.client.can('GM_START_GAME')
      : this.client.can('GM_SELECT_LIARS_DICE');
  }

  selectGame(gameId: (typeof this.games)[number]['id']): void {
    if (!this.roomReady() || !this.canSelect(gameId)) return;
    if (gameId === 'WHO_AM_I') this.client.action('GM_START_GAME');
    else this.client.selectLiarsDice();
  }
}
