import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { SheriffRevealView } from '../core/room.models';
import { SheriffPlayingStatus } from './sheriff-playing-status';
import { SheriffRevealActions } from './sheriff-reveal-actions';
import { SheriffRevealEntry, SheriffRevealGrid } from './sheriff-reveal-grid';

@Component({
  imports: [SheriffPlayingStatus, SheriffRevealActions, SheriffRevealGrid],
  styleUrl: './sheriff-reveal.scss',
  template: `
    <section class="sheriff-reveal page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (room(); as room) {
        <main class="reveal-shell">
          <nav class="game-nav" aria-label="Game context">
            <span class="game-label">SHERIFF · FINAL REVEAL</span>
            <div class="room-context" aria-label="Current room code">
              <span>ROOM</span><strong>{{ room.roomId }}</strong>
            </div>
          </nav>

          <header class="reveal-heading">
            <img
              src="assets/drinkduel/games/sheriff/characters/sheriff.png"
              width="1024"
              height="1536"
              alt="Sheriff role artwork"
            />
            <div>
              <p>EVERY BADGE IS ON THE TABLE</p>
              <h1>The whole town revealed.</h1>
              <span>No winner calculated — your crew decides how the showdown ended.</span>
            </div>
            <strong>{{ room.isGm ? 'Game Master' : 'Player' }}</strong>
          </header>

          <dd-sheriff-playing-status [game]="game()" [final]="true" />
          <dd-sheriff-reveal-actions [room]="room" />
          <dd-sheriff-reveal-grid
            [room]="room"
            [entries]="entries()"
            [excluded]="excludedPlayers()"
          />

          @if (client.notice()) {
            <p class="notice" role="status">{{ client.notice() }}</p>
          }
        </main>
      } @else {
        <main class="loading" role="status">Restoring the final reveal…</main>
      }
    </section>
  `,
})
export class SheriffReveal {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly room = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.sheriff?.phase === 'REVEAL' ? room : null;
  });
  readonly game = computed(() => this.room()!.sheriff as SheriffRevealView);
  readonly entries = computed<readonly SheriffRevealEntry[]>(() => {
    const room = this.room();
    if (!room || room.sheriff?.phase !== 'REVEAL') return [];
    const players = new Map(room.players.map((player) => [player.playerId, player]));
    const roles = new Map(room.sheriff.publicRoles.map((entry) => [entry.playerId, entry.role]));
    return room.sheriff.participants.flatMap((participant) => {
      const player = players.get(participant.playerId);
      const role = roles.get(participant.playerId);
      return player && role ? [{ player, role, lifeStatus: participant.lifeStatus }] : [];
    });
  });
  readonly excludedPlayers = computed(() => {
    const room = this.room();
    if (!room || room.sheriff?.phase !== 'REVEAL') return [];
    const participants = new Set(room.sheriff.participants.map((entry) => entry.playerId));
    return room.players.filter((player) => !participants.has(player.playerId));
  });

  constructor() {
    this.client.resume(this.roomId);
  }
}
