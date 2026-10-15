import { Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { SheriffGameView } from '../core/room.models';

@Component({
  selector: 'dd-sheriff-phase',
  template: `
    <section class="panel page-enter">
      @if (currentPhase()) {
        <p>SHERIFF</p>
        <h1>{{ phaseLabel() }}</h1>
        <p>
          The Sheriff table is ready. The full game screen arrives in the next implementation step.
        </p>
      } @else {
        <p role="status">Restoring the Sheriff table…</p>
      }
    </section>
  `,
})
export class SheriffPhase {
  readonly client = inject(RoomClient);
  private readonly route = inject(ActivatedRoute);
  readonly roomId = this.route.snapshot.paramMap.get('id') ?? '';
  private readonly expectedPhase = this.route.snapshot.data['sheriffPhase'] as
    SheriffGameView['phase'] | undefined;
  readonly currentPhase = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.sheriff?.phase === this.expectedPhase
      ? room.sheriff
      : null;
  });
  readonly phaseLabel = computed(() => {
    switch (this.currentPhase()?.phase) {
      case 'START':
        return 'Get ready';
      case 'PLAYING':
        return 'Round in progress';
      case 'REVEAL':
        return 'Roles revealed';
      default:
        return '';
    }
  });

  constructor() {
    this.client.resume(this.roomId);
  }
}
