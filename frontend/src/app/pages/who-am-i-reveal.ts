import { Component, computed, inject } from '@angular/core';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

@Component({
  selector: 'dd-who-am-i-reveal',
  imports: [Avatar],
  styleUrl: './who-am-i-reveal.scss',
  template: `
    @if (client.room(); as room) {
      <main class="reveal-page page-enter">
        <header class="reveal-header">
          <img src="assets/drinkduel/games/who-am-i-logo.webp" width="2170" height="725" alt="Who Am I?" />
          <div class="reveal-copy"><p class="eyebrow">THE BIG REVEAL</p><h1>So… who was everyone?</h1><p>Secrets out. Here's who everyone had.</p></div>
          <div class="room-context" aria-label="Current room"><span>ROOM</span><strong>{{ room.roomId }}</strong></div>
        </header>
        <section class="results" aria-labelledby="results-title">
          <div class="results-heading"><div><p class="eyebrow">ROUND RESULTS</p><h2 id="results-title">Meet the whole crew</h2></div><strong>{{ orderedCards().length }} revealed</strong></div>
          <div class="results-scroll">
            <ul class="reveal-grid">
              @for (card of orderedCards(); track card.playerId) {
                <li class="reveal-card" [class.self]="card.playerId === room.currentPlayerId" [class.got-it-card]="card.gameStatus === 'GOT_IT'">
                  <div class="identity"><dd-avatar [id]="card.avatarId" /><div><h3 tabindex="0" [title]="card.nickname">{{ card.nickname }}</h3><p>@if (card.playerId === room.currentPlayerId) { <span class="you">YOU</span> } @if (card.playerId === room.gmPlayerId) { <span class="gm">♛ Game Master</span> }</p></div></div>
                  <div class="answer" tabindex="0" [title]="card.assignedName"><span>THEY WERE</span><strong>{{ card.assignedName }}</strong></div>
                  <p class="creator" tabindex="0" [title]="card.createdBy">Submitted by <strong>{{ card.createdBy }}</strong></p>
                  <span class="result" [class.got-it]="card.gameStatus === 'GOT_IT'">{{ resultLabel(card.gameStatus) }}</span>
                </li>
              }
            </ul>
          </div>
          <div class="celebration" aria-hidden="true"><i>✦</i><i>◆</i><i>✧</i><i>●</i></div>
        </section>
        <div class="post-round-actions">
          @if (room.isGm) {
            <button
              class="play-again"
              [disabled]="!client.can('GM_PLAY_AGAIN')"
              (click)="client.playAgain()"
            >
              {{ client.busy() ? 'Starting new round…' : 'Play Again' }}
            </button>
          } @else {
            <p class="waiting-for-gm" role="status">Waiting for the Game Master… 🍺</p>
          }
        </div>
      </main>
    }
  `,
})
export class WhoAmIReveal {
  readonly client = inject(RoomClient);
  readonly orderedCards = computed(() => {
    const room = this.client.room();
    const cards = room?.game?.reveal ?? [];
    return [...cards].sort((a, b) => a.playerId === room?.currentPlayerId ? -1 : b.playerId === room?.currentPlayerId ? 1 : 0);
  });
  resultLabel(status: 'PLAYING' | 'GOT_IT'): string {
    return status === 'GOT_IT' ? '✓ Got It' : 'Not Guessed';
  }
}
