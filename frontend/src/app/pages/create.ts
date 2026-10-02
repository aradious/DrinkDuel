import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { RoomClient } from '../core/room-client';
import { validNickname } from '../core/room.models';
@Component({
  imports: [FormsModule, RouterLink],
  styleUrl: './create.scss',
  template: ` <section class="create-page">
    <a routerLink="/" class="back"><span aria-hidden="true">←</span> Home</a>
    <div class="create-layout">
      <div class="visual-zone">
        <img
          class="brand"
          src="assets/drinkduel/brand/drinkduel-logo.webp"
          alt="DrinkDuel"
          width="1918"
          height="820"
        />
        <h1>Create your room.<br />Bring the crew.</h1>
        <img
          class="mascot"
          src="assets/drinkduel/characters/mascot-create-room.webp"
          alt="Blue monster Game Master holding a Create Room sign"
        />
      </div>
      <form class="create-panel" (ngSubmit)="create()" novalidate>
        <p class="intro">You’re the Game Master.</p>
        <p class="support">Create a room, then invite your friends.</p>
        <span class="chip">Private host session</span>
        <p class="support">This browser securely keeps your Game Master identity for reconnecting.</p>
        <label for="gm-name">Your nickname</label
        ><input
          id="gm-name"
          name="nickname"
          [(ngModel)]="nickname"
          autocomplete="nickname"
          placeholder="What should we call you?"
          [attr.aria-invalid]="submitted() && !validNickname(nickname)"
          aria-describedby="gm-error"
        />
        <p class="field-error" id="gm-error">
          @if (submitted() && !validNickname(nickname)) {
            Add a nickname for your crew.
          }
        </p>
        <button class="create-action" type="submit" [disabled]="!available() || client.busy()">
          <svg aria-hidden="true" viewBox="0 0 24 24" focusable="false">
            <circle cx="8" cy="7" r="3" />
            <path d="M2 20v-2a6 6 0 0 1 12 0v2m5-12v6m-3-3h6" />
          </svg>
          <span>{{ client.busy() ? 'Making room for the crew…' : 'Create Room' }}</span>
        </button>
        @if (checking()) {
          <p class="support" role="status">Getting ready…</p>
        } @else if (!available()) {
          <p class="notice" role="status">
            Room creation isn’t available. Check your connection and try again.
          </p>
        }
        @if (client.notice()) {
          <p class="notice" role="status">{{ client.notice() }}</p>
        }
      </form>
    </div>
  </section>`,
})
export class Create {
  readonly client = inject(RoomClient);
  readonly checking = signal(true);
  readonly available = signal(false);
  readonly submitted = signal(false);
  readonly validNickname = validNickname;
  nickname = '';
  constructor() {
    void this.client.hostIdentityAvailable().then((value) => {
      this.available.set(value);
      this.checking.set(false);
    });
  }
  create(): void {
    this.submitted.set(true);
    if (this.available() && validNickname(this.nickname)) void this.client.create(this.nickname);
  }
}
