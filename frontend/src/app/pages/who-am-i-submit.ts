import { Component, computed, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';

@Component({
  selector: 'dd-who-am-i-submit',
  imports: [Avatar, FormsModule],
  styleUrl: './who-am-i-submit.scss',
  template: `
    @if (client.room(); as room) {
      @if (room.game; as game) {
        <main class="submit-page page-enter">
          <header class="game-header">
            <div class="brand-block">
              <img
                src="/assets/drinkduel/games/who-am-i-logo.webp"
                width="2170"
                height="725"
                alt="Who Am I?"
              />
              <div class="round-copy">
                <p class="eyebrow">SECRET NAME ROUND</p>
                <h1>Everyone secretly submits one name.</h1>
                <p>Pick a name for the crew to guess. Keep it sneaky.</p>
              </div>
            </div>
            <div class="room-context" aria-label="Current room">
              <span>ROOM</span><strong>{{ room.roomId }}</strong>
            </div>
          </header>

          @if (!gmConnected()) {
            <p class="gm-waiting" role="status">Waiting for Game Master… 🍺</p>
          }

          <div class="submit-layout">
            <section class="submit-panel" aria-labelledby="submit-title">
              @if (!game.currentPlayerSubmitted) {
                <p class="eyebrow">YOUR SECRET PICK</p>
                <h2 id="submit-title">Who should they guess?</h2>
                <p class="helper">A person, character, celebrity—anything your crew will know.</p>
                <form (ngSubmit)="submit()">
                  <label for="secret-name">Secret name</label>
                  <input
                    id="secret-name"
                    name="secretName"
                    [(ngModel)]="secretName"
                    maxlength="100"
                    autocomplete="off"
                    placeholder="Type one secret name"
                    [disabled]="client.busy() || !client.can('SUBMIT_NAME')"
                    [attr.aria-invalid]="error ? 'true' : null"
                  />
                  <p class="field-note" [class.error]="error">{{ error || 'You cannot edit it after submitting unless the Game Master resets it.' }}</p>
                  <button class="submit-name" type="submit" [disabled]="client.busy() || !client.can('SUBMIT_NAME')">
                    <span class="send-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"></path></svg>
                    </span>
                    {{ client.busy() ? 'Submitting…' : 'Submit Name' }}
                    <svg class="arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"></path></svg>
                  </button>
                </form>
              } @else {
                <div class="submitted-state" role="status">
                  <span class="big-check" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6"></path></svg>
                  </span>
                  <p class="eyebrow">NAME LOCKED IN</p>
                  <h2 id="submit-title">Your secret is safe.</h2>
                  <p>Your name was accepted and cannot be edited right now.</p>
                  <strong>Waiting for the rest of the crew…</strong>
                </div>
              }
            </section>

            <section class="progress-panel" aria-labelledby="progress-title">
              <div class="progress-heading">
                <div>
                  <p class="eyebrow">SUBMISSION PROGRESS</p>
                  <h2 id="progress-title">The crew</h2>
                </div>
                <strong>{{ game.submittedCount }} / {{ game.participantCount }}</strong>
              </div>
              <div class="progress-track" aria-hidden="true">
                <span [style.width.%]="progressPercent()"></span>
              </div>
              <ul class="participant-list">
                @for (entry of participants(); track entry.player.playerId) {
                  <li [class.current]="entry.player.playerId === room.currentPlayerId" [class.submitted]="entry.status.submitted">
                    <dd-avatar [id]="entry.player.avatarId" />
                    <div class="participant-copy">
                      <h3>
                        {{ entry.player.nickname }}
                        @if (entry.player.playerId === room.currentPlayerId) { <span class="you">YOU</span> }
                      </h3>
                      <p>
                        @if (entry.player.playerId === room.gmPlayerId) { <span class="gm-badge">♛ Game Master</span> }
                        @if (entry.player.connectionStatus === 'DISCONNECTED') { <span>Be right back</span> }
                      </p>
                    </div>
                    <div class="participant-actions">
                      <span class="status" [class.done]="entry.status.submitted">
                        {{ entry.status.submitted ? '✓ Submitted' : 'Waiting' }}
                      </span>
                      @if (room.isGm && entry.status.resetSubmissionId) {
                        <button class="reset" type="button" [disabled]="!client.can('GM_RESET_SUBMISSION')" (click)="client.resetSubmission(entry.player.playerId, entry.status.resetSubmissionId)" [attr.aria-label]="'Reset submission for ' + entry.player.nickname">Reset</button>
                      }
                      @if (room.isGm && entry.player.playerId !== room.gmPlayerId && client.can('GM_KICK_PLAYER')) {
                        <button class="remove" type="button" (click)="client.action('GM_KICK_PLAYER', entry.player.playerId)" [attr.aria-label]="'Remove ' + entry.player.nickname">Remove</button>
                      }
                    </div>
                  </li>
                }
              </ul>

              @if (room.isGm) {
                <div class="gm-controls">
                  @if (game.readyForShuffle) {
                    <p>Everyone is ready. Mix up the names!</p>
                  } @else {
                    <p>Shuffle unlocks when every remaining player has submitted.</p>
                  }
                  <button class="shuffle" type="button" [disabled]="!client.can('GM_SHUFFLE')" (click)="client.shuffle()">Shuffle Names</button>
                  @if (client.can('GM_BACK_TO_ROOM')) {
                    <button class="back-room" type="button" (click)="client.backToRoom()">Back to Room</button>
                  }
                </div>
              } @else {
                <p class="player-note">The Game Master will shuffle when everyone is ready.</p>
              }
            </section>
          </div>
        </main>
      }
    }
  `,
})
export class WhoAmISubmit {
  readonly client = inject(RoomClient);
  secretName = '';
  error = '';
  readonly participants = computed(() => {
    const room = this.client.room();
    const statuses = new Map(room?.game?.participants.map((status) => [status.playerId, status]));
    return (room?.players ?? [])
      .filter((player) => statuses.has(player.playerId))
      .map((player) => ({ player, status: statuses.get(player.playerId)! }));
  });
  readonly progressPercent = computed(() => {
    const game = this.client.room()?.game;
    return game?.participantCount ? (game.submittedCount / game.participantCount) * 100 : 0;
  });
  readonly gmConnected = computed(() => {
    const room = this.client.room();
    return room?.players.find((player) => player.playerId === room.gmPlayerId)?.connectionStatus === 'CONNECTED';
  });

  constructor() {
    effect(() => {
      if (this.client.room()?.game?.currentPlayerSubmitted) this.secretName = '';
    });
  }

  submit(): void {
    if (!this.secretName.trim()) {
      this.error = 'Add a name before you submit.';
      return;
    }
    this.error = '';
    this.client.submitName(this.secretName);
  }
}
