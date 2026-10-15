import { Component, input } from '@angular/core';
import { PlayerView, SheriffPlayerStatus } from '../core/room.models';
import { Avatar } from '../shared/avatar';

@Component({
  selector: 'dd-sheriff-playing-spotlight',
  imports: [Avatar],
  styleUrl: './sheriff-playing-spotlight.scss',
  template: `
    <section class="sheriff-spotlight" aria-labelledby="public-sheriff-title">
      <img
        src="assets/drinkduel/games/sheriff/characters/sheriff.png"
        width="1024"
        height="1536"
        alt="Sheriff role artwork"
      />
      <dd-avatar [id]="sheriff().player.avatarId" />
      <div>
        <p class="eyebrow gold-label">PUBLIC ROLE</p>
        <h2 id="public-sheriff-title">{{ sheriff().player.nickname }} is the Sheriff</h2>
        <p class="muted-copy">The badge is public. Everyone else keeps their role close.</p>
      </div>
      <span class="life-chip" [class.dead]="sheriff().lifeStatus === 'DEAD'">
        {{ sheriff().lifeStatus }}
      </span>
    </section>
  `,
})
export class SheriffPlayingSpotlight {
  readonly sheriff = input.required<{
    player: PlayerView;
    lifeStatus: SheriffPlayerStatus;
  }>();
}
