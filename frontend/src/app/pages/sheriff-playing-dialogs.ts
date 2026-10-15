import { Component, ElementRef, input, output, viewChild } from '@angular/core';
import { RoomClient } from '../core/room-client';
import { PlayerView, RoomView } from '../core/room.models';

@Component({
  selector: 'dd-sheriff-playing-dialogs',
  styleUrl: './sheriff-playing-dialogs.scss',
  template: `
    @if (room().allowedActions.includes('GM_MARK_SHERIFF_DEAD')) {
      <dialog #deathDialog class="confirm-dialog" (close)="deathClosed.emit()">
        <p class="eyebrow gold-label">REVEAL A ROLE</p>
        <h2>Mark {{ deathTarget()?.nickname ?? 'this player' }} dead?</h2>
        <p>This cannot be undone. Their role will be revealed to everyone.</p>
        <div class="dialog-actions">
          <button
            type="button"
            class="cancel-dialog"
            [disabled]="client.busy()"
            (click)="closeDeath()"
          >
            Keep Alive
          </button>
          <button
            type="button"
            class="confirm-death confirm-action"
            [disabled]="!client.can('GM_MARK_SHERIFF_DEAD')"
            (click)="deathConfirmed.emit()"
          >
            {{ client.busy() ? 'Revealing…' : 'Mark Dead & Reveal' }}
          </button>
        </div>
      </dialog>
    }

    @if (room().allowedActions.includes('GM_END_SHERIFF')) {
      <dialog #endDialog class="confirm-dialog">
        <p class="eyebrow gold-label">FINAL REVEAL</p>
        <h2>End this game?</h2>
        <p>Every role will be revealed to the whole town.</p>
        <div class="dialog-actions">
          <button
            type="button"
            class="cancel-dialog"
            [disabled]="client.busy()"
            (click)="closeEnd()"
          >
            Keep Playing
          </button>
          <button
            type="button"
            class="confirm-end confirm-action"
            [disabled]="!client.can('GM_END_SHERIFF')"
            (click)="endConfirmed.emit()"
          >
            {{ client.busy() ? 'Ending…' : 'End & Reveal All' }}
          </button>
        </div>
      </dialog>
    }
  `,
})
export class SheriffPlayingDialogs {
  readonly room = input.required<RoomView>();
  readonly deathTarget = input<PlayerView | null>(null);
  readonly deathConfirmed = output<void>();
  readonly deathClosed = output<void>();
  readonly endConfirmed = output<void>();
  readonly deathDialog = viewChild<ElementRef<HTMLDialogElement>>('deathDialog');
  readonly endDialog = viewChild<ElementRef<HTMLDialogElement>>('endDialog');

  constructor(readonly client: RoomClient) {}

  openDeath(): void {
    this.deathDialog()?.nativeElement.showModal();
  }

  closeDeath(): void {
    this.deathDialog()?.nativeElement.close();
  }

  openEnd(): void {
    this.endDialog()?.nativeElement.showModal();
  }

  closeEnd(): void {
    this.endDialog()?.nativeElement.close();
  }
}
