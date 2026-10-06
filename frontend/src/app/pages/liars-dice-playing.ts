import {
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { DieFace } from '../shared/die-face';

@Component({
  imports: [DieFace],
  styleUrl: './liars-dice-playing.scss',
  template: `
    <section class="dice-playing page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (room(); as room) {
        <main class="playing-shell">
          <nav class="game-nav" aria-label="Round context">
            <span class="game-label">PRIVATE TABLE</span>
            <div class="room-context" aria-label="Current room code">
              <span>ROOM</span><strong>{{ room.roomId }}</strong>
            </div>
          </nav>

          <header class="game-heading">
            <img
              class="game-logo"
              src="assets/drinkduel/games/liars-dice/liars-dice-logo.png"
              width="1536"
              height="1024"
              alt="Liar's Dice"
            />
            <div class="round-copy">
              <p class="eyebrow">ROUND IS ON</p>
              <h1>Your dice. Your secret.</h1>
              <p>{{ participantCount() }} players are at this table. Guard your hand.</p>
            </div>
            <span class="role-chip">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
          </header>

          @if (dice(); as hand) {
            <section class="private-table" aria-labelledby="private-hand-title">
              <div class="table-copy">
                <p class="eyebrow">YOUR PRIVATE HAND</p>
                <h2 id="private-hand-title">Hold to Peek</h2>
                <p>Keep your dice secret.</p>
              </div>

              <div class="dice-stage" [class.peeking]="peeking()">
                @if (peeking()) {
                  <div class="dice-scatter" aria-label="Your five dice">
                    @for (die of hand; track $index) {
                      <span [class]="'die-slot slot-' + ($index + 1)">
                        <dd-die-face [value]="die" />
                      </span>
                    }
                  </div>
                }
                <button
                  class="cup-control"
                  type="button"
                  aria-label="Hold to peek at your dice"
                  [attr.aria-pressed]="peeking()"
                  (pointerdown)="openPointer($event)"
                  (pointerup)="closePeek()"
                  (pointercancel)="closePeek()"
                  (pointerleave)="closePeek()"
                  (keydown)="openKey($event)"
                  (keyup)="closeKey($event)"
                  (blur)="closePeek()"
                >
                  <img
                    [src]="peeking() ? openCup : closedCup"
                    alt=""
                    width="1402"
                    height="1122"
                    draggable="false"
                  />
                  <span>{{ peeking() ? 'Release to Hide' : 'Hold to Peek' }}</span>
                </button>
              </div>

              @if (room.isGm && room.allowedActions.includes('GM_END_LIARS_DICE')) {
                <button
                  class="end-game"
                  type="button"
                  [disabled]="!client.can('GM_END_LIARS_DICE')"
                  (click)="openEndDialog()"
                >
                  End Game
                </button>
              }
            </section>
          } @else {
            <section class="excluded" role="status">
              <span class="excluded-icon" aria-hidden="true">◇</span>
              <p class="eyebrow">ROUND IN PROGRESS</p>
              <h2>You weren't in this round.</h2>
              <p>Wait for the Game Master to finish the round.</p>
              @if (room.isGm && room.allowedActions.includes('GM_END_LIARS_DICE')) {
                <button
                  class="end-game"
                  type="button"
                  [disabled]="!client.can('GM_END_LIARS_DICE')"
                  (click)="openEndDialog()"
                >
                  End Game
                </button>
              }
            </section>
          }

          @if (client.notice()) {
            <p class="notice" role="status">{{ client.notice() }}</p>
          }
        </main>
      } @else {
        <main class="loading" role="status">Restoring your private seat…</main>
      }

      <dialog #endDialog (close)="closePeek()">
        <p class="eyebrow">END LIAR'S DICE</p>
        <h2>Reveal every hand?</h2>
        <p>End this round and reveal everyone's dice?</p>
        <div class="dialog-actions">
          <button
            class="confirm-end"
            type="button"
            [disabled]="client.busy()"
            (click)="confirmEnd()"
          >
            {{ client.busy() ? 'Ending…' : 'End and Reveal' }}
          </button>
          <button
            class="cancel-end"
            type="button"
            [disabled]="client.busy()"
            (click)="closeEndDialog()"
          >
            Keep Playing
          </button>
        </div>
      </dialog>
    </section>
  `,
})
export class LiarsDicePlaying implements OnDestroy {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly endDialog = viewChild<ElementRef<HTMLDialogElement>>('endDialog');
  readonly peeking = signal(false);
  readonly room = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.liarsDice?.phase === 'PLAYING' ? room : null;
  });
  readonly dice = computed(() => {
    const game = this.room()?.liarsDice;
    return game?.phase === 'PLAYING' ? game.ownDice : null;
  });
  readonly participantCount = computed(() => {
    const game = this.room()?.liarsDice;
    return game?.phase === 'PLAYING' ? game.participantIds.length : 0;
  });
  readonly closedCup = 'assets/drinkduel/games/liars-dice/dice-cup.png';
  readonly openCup = 'assets/drinkduel/games/liars-dice/dice-cup-open.png';

  constructor() {
    this.client.resume(this.roomId);
    effect(() => {
      if (!this.room() || !this.dice()) this.closePeek();
    });
  }

  openPointer(event: PointerEvent): void {
    if (event.button !== 0 || !this.dice()) return;
    event.preventDefault();
    (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
    this.peeking.set(true);
  }

  openKey(event: KeyboardEvent): void {
    if ((event.key !== ' ' && event.key !== 'Enter') || event.repeat || !this.dice()) return;
    event.preventDefault();
    this.peeking.set(true);
  }

  closeKey(event: KeyboardEvent): void {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    this.closePeek();
  }

  closePeek(): void {
    this.peeking.set(false);
  }

  @HostListener('window:blur')
  onWindowBlur(): void {
    this.closePeek();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') this.closePeek();
  }

  openEndDialog(): void {
    if (this.client.can('GM_END_LIARS_DICE')) this.endDialog()?.nativeElement.showModal();
  }

  closeEndDialog(): void {
    this.endDialog()?.nativeElement.close();
  }

  confirmEnd(): void {
    if (!this.client.can('GM_END_LIARS_DICE')) return;
    this.client.endLiarsDice();
    this.closeEndDialog();
  }

  ngOnDestroy(): void {
    this.closePeek();
  }
}
