import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { validCode, validNickname } from '../core/room.models';
@Component({
  imports: [FormsModule, RouterLink],
  styleUrl: './join.scss',
  template: ` <section class="join-page">
    <a routerLink="/" class="back"><span aria-hidden="true">←</span> Home</a>
    <div class="join-layout">
      <div class="visual-zone">
        <img
          class="brand"
          src="assets/drinkduel/brand/drinkduel-logo.webp"
          alt="DrinkDuel"
          width="1918"
          height="820"
        />
        <h1>Join the crew.<br />Let the chaos begin.</h1>
        <img
          class="mascot"
          src="assets/drinkduel/characters/mascot-join-room.webp"
          alt="Pink bunny party pal inviting you to join the crew"
          width="1536"
          height="1024"
        />
      </div>
      <form class="join-panel" (ngSubmit)="join()" novalidate>
        <h2 class="intro">Ready to join?</h2>
        <p class="support">Enter the room code from your Game Master.</p>
        <label for="room-code">Room code</label
        ><input
          id="room-code"
          name="code"
          [(ngModel)]="code"
          autocomplete="off"
          autocapitalize="characters"
          spellcheck="false"
          placeholder="ABC234"
          maxlength="6"
          [attr.aria-invalid]="submitted() && !validCode(code)"
          aria-describedby="code-error"
        />
        <p id="code-error" class="field-error">
          @if (submitted() && !validCode(code)) {
            Enter the six-character room code.
          }
        </p>
        <label for="nickname">Your nickname</label
        ><input
          id="nickname"
          name="nickname"
          [(ngModel)]="nickname"
          autocomplete="nickname"
          placeholder="What should we call you?"
          [attr.aria-invalid]="submitted() && !validNickname(nickname)"
          aria-describedby="name-error"
        />
        <p id="name-error" class="field-error">
          @if (submitted() && !validNickname(nickname)) {
            Your crew needs a name to call you.
          }
        </p>
        <button class="join-action" type="submit" [disabled]="client.busy()">
          <svg aria-hidden="true" viewBox="0 0 24 24" focusable="false">
            <circle cx="8" cy="7" r="3" />
            <circle cx="18" cy="7" r="3" />
            <path d="M2 20v-2a6 6 0 0 1 12 0v2m4-8a4 4 0 0 1 4 4v4" />
          </svg>
          <span>{{ client.busy() ? 'Finding your crew…' : 'Join Room' }}</span>
        </button>
        <p class="restore">Been here before? This browser will restore your place.</p>
        @if (client.notice()) {
          <p class="notice" role="status">{{ client.notice() }}</p>
        }
      </form>
    </div>
  </section>`,
})
export class Join {
  readonly client = inject(RoomClient);
  code = inject(ActivatedRoute).snapshot.queryParamMap.get('room') ?? '';
  nickname = '';
  readonly submitted = signal(false);
  readonly validCode = validCode;
  readonly validNickname = validNickname;
  join(): void {
    this.submitted.set(true);
    if (validCode(this.code) && validNickname(this.nickname))
      this.client.join(this.code, this.nickname);
  }
}
