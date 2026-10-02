import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RoomClient } from '../core/room-client';
import type { WhoAmIGameCardView } from '../core/room.models';
import { Avatar } from '../shared/avatar';

@Component({
  selector: 'dd-who-am-i-playing',
  imports: [Avatar, FormsModule],
  styleUrl: './who-am-i-playing.scss',
  template: `
    @if (client.room(); as room) {
      @if (room.game; as game) {
        <main class="playing-page page-enter">
          <header class="playing-header">
            <img src="/assets/drinkduel/games/who-am-i-logo.webp" width="2170" height="725" alt="Who Am I?" />
            <div class="round-copy">
              <p class="eyebrow">ROUND IS ON!</p>
              <h1>Talk, ask, guess… Who are you?</h1>
              <p>Help your crew figure out their hidden names.</p>
            </div>
            <div class="room-context" aria-label="Current room"><span>ROOM</span><strong>{{ room.roomId }}</strong></div>
          </header>

          <section class="board" aria-labelledby="players-title">
            <div class="board-heading">
              <div><p class="eyebrow">PLAYER CARDS</p><h2 id="players-title">Your crew</h2></div>
              <div class="board-summary">
                <strong>{{ filteredCards().length }} shown</strong>
                @if (room.isGm && room.allowedActions.includes('GM_END_GAME')) {
                  <button type="button" class="end-game" [disabled]="!client.can('GM_END_GAME')" (click)="openEndGame()">End Game</button>
                }
              </div>
            </div>

            <div class="card-scroll">
              <ul class="player-grid">
                @for (card of filteredCards(); track card.playerId) {
                  <li class="game-card" [class.self]="card.playerId === room.currentPlayerId" [class.guessed]="card.gameStatus === 'GOT_IT'">
                    <div class="player-top">
                      <dd-avatar [id]="card.avatarId" />
                      <div class="identity">
                        <h3 tabindex="0" [title]="card.nickname">{{ card.nickname }}</h3>
                        <p>
                          @if (card.playerId === room.currentPlayerId) { <span class="you">YOU</span> }
                          @if (card.playerId === room.gmPlayerId) { <span class="gm-badge">♛ Game Master</span> }
                        </p>
                      </div>
                    </div>

                    @if (card.playerId === room.currentPlayerId) {
                      <div class="name-card self-name"><strong>?</strong><span>Your name is hidden!</span></div>
                    } @else {
                      <div class="name-card" tabindex="0" [title]="card.assignedName ?? ''">
                        <strong>{{ card.assignedName }}</strong>
                      </div>
                    }

                    <div class="card-footer">
                      <span class="result" [class.result-guessed]="card.gameStatus === 'GOT_IT'">{{ resultLabel(card.gameStatus) }}</span>
                      @if (room.isGm && card.gameStatus === 'PLAYING') {
                        <button type="button" class="got-it" [disabled]="!client.can('GM_MARK_GOT_IT')" (click)="markGotIt(card.playerId)">Got It</button>
                      }
                      @if (room.isGm && card.gameStatus === 'GOT_IT') {
                        <button type="button" class="undo-result" [disabled]="!client.can('GM_RESET_PLAYER_STATUS')" (click)="undoResult(card.playerId, card.statusVersion)">Undo Got It</button>
                      }
                    </div>
                  </li>
                } @empty {
                  <li class="empty-state">No players match this view.</li>
                }
              </ul>
            </div>

            <div class="board-tools">
              <div class="filters" aria-label="Player result filter">
                <button type="button" [class.active]="filter() === 'NOT_GUESSED'" (click)="applyFilter('NOT_GUESSED')">Not Guessed <span>{{ notGuessedCount() }}</span></button>
                <button type="button" [class.active]="filter() === 'GUESSED'" (click)="applyFilter('GUESSED')">Already Guessed <span>{{ guessedCount() }}</span></button>
              </div>
              <label class="search"><span>Search players</span><input type="search" [ngModel]="query()" (ngModelChange)="query.set($event)" placeholder="Nickname or visible name" /></label>
            </div>
          </section>

          @if (room.isGm && room.allowedActions.includes('GM_END_GAME')) {
            <dialog #endGameDialog class="end-game-dialog" (click)="backdrop($event)" aria-labelledby="end-game-title" aria-describedby="end-game-copy">
              <p class="eyebrow">FINAL CALL</p>
              <h2 id="end-game-title">End this game?</h2>
              <p id="end-game-copy">The round will end for everyone.<br />You'll see the results next.</p>
              <div class="dialog-actions">
                <button type="button" class="dialog-cancel" autofocus [disabled]="client.busy()" (click)="closeEndGame()">Cancel</button>
                <button type="button" class="dialog-confirm" [disabled]="!client.can('GM_END_GAME')" (click)="confirmEndGame()">{{ client.busy() ? 'Ending…' : 'End Game' }}</button>
              </div>
            </dialog>
          }
        </main>
      }
    }
  `,
})
export class WhoAmIPlaying {
  readonly client = inject(RoomClient);
  readonly endGameDialog = viewChild<ElementRef<HTMLDialogElement>>('endGameDialog');
  readonly filter = signal<'NOT_GUESSED' | 'GUESSED'>('NOT_GUESSED');
  readonly retainedGotIt = signal<ReadonlySet<string>>(new Set());
  readonly query = signal('');
  private previousStatuses?: ReadonlyMap<string, WhoAmIGameCardView['gameStatus']>;
  readonly orderedCards = computed(() => {
    const room = this.client.room();
    const cards = room?.game?.cards ?? [];
    return [...cards].sort((a, b) =>
      a.playerId === room?.currentPlayerId ? -1 : b.playerId === room?.currentPlayerId ? 1 : 0,
    );
  });
  readonly guessedCount = computed(() => this.orderedCards().filter((card) => card.gameStatus === 'GOT_IT').length);
  readonly notGuessedCount = computed(() => this.orderedCards().filter((card) => card.gameStatus !== 'GOT_IT').length);
  readonly filteredCards = computed(() => {
    const query = this.query().trim().toLocaleLowerCase();
    return this.orderedCards().filter((card) => {
      const statusMatches = this.filter() === 'GUESSED'
        ? card.gameStatus === 'GOT_IT'
        : card.gameStatus !== 'GOT_IT' || this.retainedGotIt().has(card.playerId);
      const textMatches = !query || card.nickname.toLocaleLowerCase().includes(query) || (card.assignedName?.toLocaleLowerCase().includes(query) ?? false);
      return statusMatches && textMatches;
    });
  });
  constructor() {
    effect(() => {
      const game = this.client.room()?.game;
      if (game?.phase !== 'PLAYING') {
        this.previousStatuses = undefined;
        return;
      }
      const next = new Map(game.cards.map((card) => [card.playerId, card.gameStatus]));
      const previous = this.previousStatuses;
      this.previousStatuses = next;
      if (!previous) return;

      const becameGotIt = game.cards
        .filter((card) => previous.get(card.playerId) === 'PLAYING' && card.gameStatus === 'GOT_IT')
        .map((card) => card.playerId);
      const returnedToPlaying = game.cards
        .filter((card) => previous.get(card.playerId) === 'GOT_IT' && card.gameStatus === 'PLAYING')
        .map((card) => card.playerId);
      if (!becameGotIt.length && !returnedToPlaying.length) return;

      untracked(() => this.retainedGotIt.update((current) => {
        const retained = new Set(current);
        becameGotIt.forEach((playerId) => retained.add(playerId));
        returnedToPlaying.forEach((playerId) => retained.delete(playerId));
        return retained;
      }));
    });
  }
  resultLabel(status: 'PLAYING' | 'GOT_IT'): string {
    return status === 'GOT_IT' ? '✓ Got It' : 'Not Guessed';
  }
  markGotIt(playerId: string): void {
    this.retainedGotIt.update((ids) => new Set(ids).add(playerId));
    this.client.markGotIt(playerId);
  }
  undoResult(playerId: string, expectedStatusVersion: number): void {
    this.client.resetPlayerStatus(playerId, expectedStatusVersion);
  }
  applyFilter(filter: 'NOT_GUESSED' | 'GUESSED'): void {
    this.retainedGotIt.set(new Set());
    this.filter.set(filter);
  }
  openEndGame(): void {
    this.endGameDialog()?.nativeElement.showModal();
  }
  closeEndGame(): void {
    if (!this.client.busy()) this.endGameDialog()?.nativeElement.close();
  }
  confirmEndGame(): void {
    this.client.endGame();
  }
  backdrop(event: MouseEvent): void {
    if (event.target === this.endGameDialog()?.nativeElement) this.closeEndGame();
  }
}
