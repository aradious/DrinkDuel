import { Component, inject, input } from '@angular/core';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';

@Component({
  selector: 'dd-sheriff-reveal-actions',
  styleUrl: './sheriff-reveal-actions.scss',
  template: `
    <footer class="round-actions" aria-label="Round actions">
      @if (room().isGm) {
        <div class="gm-actions">
          @if (room().allowedActions.includes('GM_RESTART_SHERIFF')) {
            <button
              class="play-again"
              type="button"
              [disabled]="!client.can('GM_RESTART_SHERIFF')"
              (click)="client.restartSheriff()"
            >
              {{ client.busy() ? 'Please wait…' : 'Play Again' }}
            </button>
          }
          @if (room().allowedActions.includes('GM_BACK_TO_ROOM')) {
            <div class="secondary-actions">
              <button
                type="button"
                [disabled]="!client.can('GM_BACK_TO_ROOM')"
                (click)="client.backToRoom()"
              >
                Back to Room
              </button>
              <button
                type="button"
                [disabled]="!client.can('GM_BACK_TO_ROOM')"
                (click)="client.chooseAnotherGame()"
              >
                Change Game
              </button>
            </div>
          }
        </div>
      } @else {
        <p class="waiting" role="status">
          <span aria-hidden="true"></span> Waiting for the Game Master…
        </p>
      }
    </footer>
  `,
})
export class SheriffRevealActions {
  readonly client = inject(RoomClient);
  readonly room = input.required<RoomView>();
}
