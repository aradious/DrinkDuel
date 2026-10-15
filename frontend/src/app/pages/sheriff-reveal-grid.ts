import { Component, input } from '@angular/core';
import { PlayerView, RoomView, SheriffPlayerStatus, SheriffRole } from '../core/room.models';
import { Avatar } from '../shared/avatar';

export interface SheriffRevealEntry {
  player: PlayerView;
  lifeStatus: SheriffPlayerStatus;
  role: SheriffRole;
}

@Component({
  selector: 'dd-sheriff-reveal-grid',
  imports: [Avatar],
  styleUrl: './sheriff-reveal-grid.scss',
  template: `
    <section class="reveal-panel" aria-labelledby="roles-title">
      <header class="section-heading">
        <div>
          <p>LOCKED ROUND ROSTER</p>
          <h2 id="roles-title">Every role on the table</h2>
        </div>
        <span>{{ entries().length }} revealed</span>
      </header>
      <ol class="role-grid">
        @for (entry of entries(); track entry.player.playerId; let index = $index) {
          <li
            class="role-card"
            [class.dead]="entry.lifeStatus === 'DEAD'"
            [class.sheriff]="entry.role === 'SHERIFF'"
            [style.--reveal-index]="index"
          >
            <div class="role-art">
              <img
                [src]="roleArtwork(entry.role)"
                width="1024"
                height="1536"
                [alt]="roleLabel(entry.role) + ' role artwork'"
              />
              <span class="life" [class.dead]="entry.lifeStatus === 'DEAD'">
                {{ entry.lifeStatus }}
              </span>
            </div>
            <div class="identity">
              <dd-avatar [id]="entry.player.avatarId" />
              <div>
                <h3 [title]="entry.player.nickname">{{ entry.player.nickname }}</h3>
                <p>
                  @if (entry.player.playerId === room().currentPlayerId) {
                    <span class="you badge">YOU</span>
                  }
                  @if (entry.player.playerId === room().gmPlayerId) {
                    <span class="gm badge">Game Master</span>
                  }
                  @if (entry.player.connectionStatus === 'DISCONNECTED') {
                    <span class="offline badge">Disconnected</span>
                  }
                </p>
              </div>
            </div>
            <strong class="role-name">{{ roleLabel(entry.role) }}</strong>
          </li>
        }
      </ol>
    </section>

    @if (excluded().length) {
      <section class="excluded-panel" aria-labelledby="excluded-title">
        <header class="section-heading">
          <div>
            <p>ROOM MEMBERS</p>
            <h2 id="excluded-title">Watching this round</h2>
          </div>
          <span>{{ excluded().length }} not assigned</span>
        </header>
        <ul class="excluded-grid">
          @for (player of excluded(); track player.playerId) {
            <li>
              <dd-avatar [id]="player.avatarId" />
              <div>
                <h3 [title]="player.nickname">{{ player.nickname }}</h3>
                <p>Not in this round · No role assigned</p>
              </div>
              <span [class.offline]="player.connectionStatus === 'DISCONNECTED'">
                {{ player.connectionStatus === 'CONNECTED' ? 'Connected' : 'Disconnected' }}
              </span>
            </li>
          }
        </ul>
      </section>
    }
  `,
})
export class SheriffRevealGrid {
  readonly room = input.required<RoomView>();
  readonly entries = input.required<readonly SheriffRevealEntry[]>();
  readonly excluded = input.required<readonly PlayerView[]>();

  roleLabel(role: SheriffRole): string {
    return role.charAt(0) + role.slice(1).toLowerCase();
  }

  roleArtwork(role: SheriffRole): string {
    return `assets/drinkduel/games/sheriff/characters/${role.toLowerCase()}.png`;
  }
}
