import { Component, inject, input, output } from '@angular/core';
import {
  PlayerView,
  RoomView,
  SheriffPlayerStatus,
  SheriffPlayingView,
  SheriffRole,
} from '../core/room.models';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

export interface SheriffRosterEntry {
  player: PlayerView;
  lifeStatus: SheriffPlayerStatus;
  publicRole?: SheriffRole;
}

@Component({
  selector: 'dd-sheriff-playing-roster',
  imports: [Avatar],
  styleUrl: './sheriff-playing-roster.scss',
  template: `
    <section class="town-roster" aria-labelledby="town-roster-title">
      <div class="roster-heading">
        <div>
          <p>LOCKED ROSTER</p>
          <h2 id="town-roster-title">The town</h2>
        </div>
        <span>{{ game().overallStatus.alive }} alive · {{ game().overallStatus.dead }} dead</span>
      </div>
      <ul class="player-grid">
        @for (entry of entries(); track entry.player.playerId) {
          <li
            class="player-card"
            [class.dead-player]="entry.lifeStatus === 'DEAD'"
            [class.self]="entry.player.playerId === room().currentPlayerId"
          >
            <dd-avatar [id]="entry.player.avatarId" />
            <div class="player-identity">
              <h3 [title]="entry.player.nickname">
                {{ entry.player.nickname }}
                @if (entry.player.playerId === room().currentPlayerId) {
                  <span class="you badge">YOU</span>
                }
              </h3>
              <p>
                @if (entry.player.playerId === room().gmPlayerId) {
                  <span class="gm-badge badge">Game Master</span>
                }
                <span
                  class="connection badge"
                  [class.offline]="entry.player.connectionStatus === 'DISCONNECTED'"
                >
                  {{ entry.player.connectionStatus === 'CONNECTED' ? 'Connected' : 'Disconnected' }}
                </span>
              </p>
            </div>
            <div class="public-state">
              <span class="life-chip badge" [class.dead]="entry.lifeStatus === 'DEAD'">{{
                entry.lifeStatus
              }}</span>
              @if (entry.publicRole; as role) {
                <span class="public-role badge">{{ roleLabel(role) }}</span>
              } @else {
                <span class="hidden-role badge">Role hidden</span>
              }
            </div>
            @if (
              entry.lifeStatus === 'ALIVE' && room().allowedActions.includes('GM_MARK_SHERIFF_DEAD')
            ) {
              <button
                class="mark-dead"
                type="button"
                [disabled]="!client.can('GM_MARK_SHERIFF_DEAD')"
                [attr.aria-label]="'Mark ' + entry.player.nickname + ' dead'"
                (click)="deathRequested.emit(entry.player.playerId)"
              >
                Mark Dead
              </button>
            }
          </li>
        }
      </ul>

      @if (room().allowedActions.includes('GM_END_SHERIFF')) {
        <button
          class="end-game"
          type="button"
          [disabled]="!client.can('GM_END_SHERIFF')"
          (click)="endRequested.emit()"
        >
          End Game
        </button>
      }
    </section>
  `,
})
export class SheriffPlayingRoster {
  readonly client = inject(RoomClient);
  readonly room = input.required<RoomView>();
  readonly game = input.required<SheriffPlayingView>();
  readonly entries = input.required<readonly SheriffRosterEntry[]>();
  readonly deathRequested = output<string>();
  readonly endRequested = output<void>();

  roleLabel(role: SheriffRole): string {
    return role.charAt(0) + role.slice(1).toLowerCase();
  }
}
