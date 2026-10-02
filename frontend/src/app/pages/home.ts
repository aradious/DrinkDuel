import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RoomClient } from '../core/room-client';
@Component({
  imports: [RouterLink],
  styleUrl: './home.scss',
  template: ` <section class="home page-enter">
    <header class="home-heading">
      <h1>
        @if (useApprovedLogo) {
          <img
            class="brand-logo"
            src="assets/drinkduel/brand/drinkduel-logo.webp"
            width="1918"
            height="820"
            alt="DrinkDuel"
            fetchpriority="high"
          />
        } @else {
          <span class="brand-fallback">Drink<span>Duel</span></span>
        }
      </h1>
      <p class="lede">Create a room. Bring your friends.<br />Pick a game. Let the chaos begin.</p>
    </header>
    <div class="hero-art">
      <img
        src="assets/drinkduel/characters/mascot-home.webp"
        width="1536"
        height="1024"
        fetchpriority="high"
        alt="Pink bunny and blue monster party pals clinking their mugs"
      />
    </div>
    <div class="hero-content">
      <div class="home-actions">
        <a class="button primary" routerLink="/create">
          <svg class="control-icon" aria-hidden="true" viewBox="0 0 24 24" focusable="false">
            <circle cx="8" cy="7" r="3" />
            <path d="M2 20v-2a6 6 0 0 1 12 0v2m5-12v6m-3-3h6" />
          </svg>
          <span>Create Room</span
          ><svg class="chevron" aria-hidden="true" viewBox="0 0 24 24">
            <path d="m9 5 7 7-7 7" />
          </svg>
        </a>
        <a class="button secondary" routerLink="/join">
          <svg class="control-icon" aria-hidden="true" viewBox="0 0 24 24" focusable="false">
            <circle cx="8" cy="7" r="3" />
            <circle cx="18" cy="7" r="3" />
            <path d="M2 20v-2a6 6 0 0 1 12 0v2m4-8a4 4 0 0 1 4 4v4" />
          </svg>
          <span>Join Room</span
          ><svg class="chevron" aria-hidden="true" viewBox="0 0 24 24">
            <path d="m9 5 7 7-7 7" />
          </svg>
        </a>
      </div>
      @if (client.savedRoom()) {
        <a class="text-link" [routerLink]="['/room', client.savedRoom()]">Back to your crew →</a>
      }
      @if (client.notice()) {
        <p class="notice" role="status">{{ client.notice() }}</p>
      }
      <details class="how">
        <summary>How to play?</summary>
        <p>
          One friend creates a room as Game Master. Everyone else joins with the room code and a
          nickname. Gather your friends, then let your Game Master choose a game.
        </p>
      </details>
    </div>
  </section>`,
})
export class Home {
  readonly useApprovedLogo = true;
  readonly client = inject(RoomClient);
}
