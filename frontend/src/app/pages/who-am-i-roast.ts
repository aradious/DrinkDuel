import { Component, computed, inject } from '@angular/core';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

@Component({
  selector: 'dd-who-am-i-roast',
  imports: [Avatar],
  styleUrl: './who-am-i-roast.scss',
  template: `
    @if (client.room(); as room) {
      @if (target(); as player) {
        <main class="roast-page page-enter">
          <header class="roast-header">
            <img src="/assets/drinkduel/games/who-am-i-logo.webp" width="2170" height="725" alt="Who Am I?" />
            <div class="room-context" aria-label="Current room"><span>ROOM</span><strong>{{ room.roomId }}</strong></div>
          </header>

          <section class="roast-stage" aria-labelledby="roast-title">
            <div class="sparkles" aria-hidden="true"><i>✦</i><i>?</i><i>✧</i><i>♛</i><i>✦</i></div>
            <p class="eyebrow">LAST ONE STANDING</p>
            <div class="hero-card">
              <div class="avatar-crown" aria-hidden="true">♛</div>
              <dd-avatar [id]="player.avatarId" />
              <p class="last-chip">LAST UNGUESSED PLAYER</p>
              <h1 id="roast-title">Still guessing, {{ player.nickname }}?</h1>
              <p class="roast-copy">Don't worry, the crew believes in you… probably.</p>
            </div>

            @if (room.isGm && room.allowedActions.includes('GM_CONTINUE_REVEAL')) {
              <button class="reveal-everyone" type="button" [disabled]="!client.can('GM_CONTINUE_REVEAL')" (click)="client.continueReveal()">
                {{ client.busy() ? 'Revealing…' : 'Reveal Everyone' }}
              </button>
            } @else {
              <p class="waiting" role="status">Waiting for the Game Master… 🍺</p>
            }
          </section>
        </main>
      } @else {
        <section class="roast-unavailable" role="status">Waiting for the final player…</section>
      }
    }
  `,
})
export class WhoAmIRoast {
  readonly client = inject(RoomClient);
  readonly target = computed(() => {
    const room = this.client.room();
    const targetId = room?.game?.roast?.playingPlayerIds[0];
    return targetId ? room?.players.find((player) => player.playerId === targetId) ?? null : null;
  });
}
