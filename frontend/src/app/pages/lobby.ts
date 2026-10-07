import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import QRCode from 'qrcode';
import { copyText } from '../core/clipboard';
import { BROWSER, joinRoomUrl } from '../core/browser';
import { RoomClient } from '../core/room-client';
import { Avatar } from '../shared/avatar';
import { WhoAmISubmit } from './who-am-i-submit';
import { WhoAmIPlaying } from './who-am-i-playing';
import { WhoAmIRoast } from './who-am-i-roast';
import { WhoAmIReveal } from './who-am-i-reveal';
@Component({
  imports: [Avatar, RouterLink, WhoAmISubmit, WhoAmIPlaying, WhoAmIRoast, WhoAmIReveal],
  styleUrl: './lobby.scss',
  template: ` <section class="lobby page-enter">
    @if (client.room(); as room) {
      @if (room.lifecycle === 'LOBBY') {
        <div class="lobby-layout">
          <aside class="lobby-visual">
            <img
              class="lobby-logo"
              src="assets/drinkduel/brand/drinkduel-logo.webp"
              width="516"
              height="198"
              alt="DrinkDuel"
            />
            <div class="lobby-intro">
              <p class="eyebrow">THE GANG’S GETTING TOGETHER</p>
              <h1>Your Room<span class="accent">.</span></h1>
              <p>Share the code, gather your crew, then pick a game when everyone is ready.</p>
            </div>
            <img
              class="lobby-mascot"
              src="assets/drinkduel/characters/mascot-lobby.webp"
              width="620"
              height="640"
              alt=""
            />
          </aside>

          <main class="lobby-panel">
            <header class="panel-heading">
              <div>
                <p class="eyebrow">PARTY LOBBY</p>
                <h2>{{ room.isGm ? 'Your crew' : 'The crew' }}</h2>
              </div>
              <span class="chip">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
            </header>

            <section class="invite" aria-labelledby="room-code-label">
              <div class="invite-copy">
                <p class="eyebrow" id="room-code-label">ROOM CODE</p>
                <p class="room-code">{{ room.roomId }}</p>
                <p class="support">Send the code. Gather the crew.</p>
                <div class="copy-actions">
                  <button class="copy-code" (click)="copyCode(room.roomId)">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <rect x="8" y="8" width="11" height="11" rx="2"></rect>
                      <path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"></path>
                    </svg>
                    {{ codeCopied() ? 'Copied!' : 'Copy code' }}
                  </button>
                  <button class="copy-link" (click)="copyLink()">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1"></path>
                      <path d="M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1"></path>
                    </svg>
                    {{ linkCopied() ? 'Copied!' : 'Copy Link' }}
                  </button>
                </div>
              </div>
              @if (qr()) {
                <div class="qr-frame">
                  <img [src]="qr()" width="116" height="116" alt="Scan to join this room" />
                  <span>Scan to join</span>
                </div>
              }
            </section>

            @if (!gmConnected()) {
              <p class="waiting gm-offline" role="status">
                <span aria-hidden="true">🍺</span> Waiting for Game Master…
              </p>
            }

            <div class="section-title">
              <div>
                <p class="eyebrow">PLAYERS</p>
                <h2>The crew</h2>
              </div>
              <span>{{ room.players.length }} / {{ room.capacity }}</span>
            </div>
            <ul class="crew">
              @for (player of room.players; track player.playerId) {
                <li
                  class="player-card"
                  [class.self-card]="player.playerId === room.currentPlayerId"
                  [class.gm-card]="player.playerId === room.gmPlayerId"
                >
                  <dd-avatar [id]="player.avatarId" />
                  <div class="player-info">
                    <h3>
                      {{ player.nickname }}
                      @if (player.playerId === room.currentPlayerId) {
                        <span class="you">YOU</span>
                      }
                    </h3>
                    <p>
                      @if (player.playerId === room.gmPlayerId) {
                        <span class="gm-badge"><span aria-hidden="true">♛</span> Game Master</span>
                      }
                      <span class="connection-status">
                        {{
                          player.connectionStatus === 'CONNECTED'
                            ? 'Here for the fun'
                            : 'Be right back'
                        }}
                      </span>
                    </p>
                  </div>
                  @if (
                    room.isGm &&
                    player.playerId !== room.gmPlayerId &&
                    room.allowedActions.includes('GM_KICK_PLAYER')
                  ) {
                    <button
                      class="kick"
                      [disabled]="!client.can('GM_KICK_PLAYER')"
                      (click)="client.action('GM_KICK_PLAYER', player.playerId)"
                      [attr.aria-label]="'Remove ' + player.nickname"
                    >
                      Remove
                    </button>
                  }
                </li>
              }
            </ul>

            <section class="game-preview">
              <div class="game-preview-copy">
                <span class="game-icon" aria-hidden="true">
                  <svg viewBox="0 0 28 28">
                    <path
                      d="M8.3 8.4h11.4c3.2 0 5.6 2.5 5.6 5.7v4.1c0 2.1-1.2 3.5-2.9 3.5-1.3 0-2.2-.8-3.2-2.3l-1-1.5H9.8l-1 1.5c-1 1.5-1.9 2.3-3.2 2.3-1.7 0-2.9-1.4-2.9-3.5v-4.1c0-3.2 2.4-5.7 5.6-5.7Z"
                    ></path>
                    <path d="M8.2 11.7v4.6M5.9 14h4.6M18.8 12.7h.1M21.5 15.2h.1"></path>
                  </svg>
                </span>
                <div>
                  <p class="eyebrow">READY WHEN YOU ARE</p>
                  <h2>{{ room.isGm ? 'Choose what to play' : 'Game Master is choosing' }}</h2>
                  <p class="support">
                    {{
                      room.isGm
                        ? 'The room stays together while you pick a game.'
                        : 'Hang tight. The next game is coming up.'
                    }}
                  </p>
                </div>
              </div>
              @if (room.isGm) {
                <a class="choose-game" [routerLink]="['/room', room.roomId, 'games']">
                  Choose Game
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"></path></svg>
                </a>
              } @else {
                <p class="player-waiting" role="status">Waiting for Game Master…</p>
              }
            </section>

            <div class="room-actions">
              @if (room.allowedActions.includes('LEAVE_ROOM')) {
                <button
                  class="secondary"
                  [disabled]="!client.can('LEAVE_ROOM')"
                  (click)="client.action('LEAVE_ROOM')"
                >
                  Leave Room
                </button>
              }
              @if (room.allowedActions.includes('GM_CLOSE_ROOM')) {
                <button
                  class="quiet danger"
                  [disabled]="!client.can('GM_CLOSE_ROOM')"
                  (click)="openClose()"
                >
                  Close Room
                </button>
              }
            </div>
          </main>
        </div>
      } @else {
        @if (room.game?.phase === 'SUBMIT_NAME') {
          <dd-who-am-i-submit />
        } @else if (room.game?.phase === 'PLAYING') {
          <dd-who-am-i-playing />
        } @else if (room.game?.phase === 'ROAST') {
          <dd-who-am-i-roast />
        } @else if (room.game?.phase === 'REVEAL') {
          <dd-who-am-i-reveal />
        } @else {
          <section class="panel">
            <h2>Your crew is playing</h2>
            <p>The room will be ready for joining when the Game Master brings everyone back.</p>
          </section>
        }
      }
    } @else {
      <section class="empty panel">
        <span class="empty-face" aria-hidden="true">{{
          client.terminal() === 'EXPIRED' ? '🍻💤' : '☁'
        }}</span>
        <h1>
          {{
            client.terminal() === 'EXPIRED'
              ? 'Party’s over!'
              : client.connection() === 'connecting' || client.connection() === 'reconnecting'
                ? 'Finding your crew…'
                : 'Room unavailable'
          }}
        </h1>
      </section>
    }
    @if (client.notice()) {
      <p class="notice" role="status">{{ client.notice() }}</p>
    }
    @if (client.connection() === 'connecting' || client.connection() === 'reconnecting') {
      <p class="waiting" role="status">
        <span aria-hidden="true">🍺</span>
        {{
          client.connection() === 'reconnecting'
            ? 'Reconnecting… Your place is saved.'
            : 'Pulling up a chair…'
        }}
      </p>
    }
    @if (
      client.connection() === 'stopped' && (!client.terminal() || client.terminal() === 'REPLACED')
    ) {
      <button class="secondary" (click)="client.retry()">Reconnect</button>
    }
    @if (!client.room()) {
      <button class="quiet" (click)="client.home()">Back Home</button>
    }
    @if (client.newAvatar() && self(); as player) {
      <div class="avatar-pop" role="status">
        <dd-avatar [id]="player.avatarId" /><span
          >POP! Your party pal, {{ player.nickname }} ✦</span
        >
      </div>
    }
    @if (client.room()?.isGm && client.room()?.allowedActions?.includes('GM_CLOSE_ROOM')) {
      <dialog #closeDialog (click)="backdrop($event)">
        <h2>Call it a night?</h2>
        <p>Closing this room sends everyone Home. You’ll need a new room to play again.</p>
        <div class="dialog-actions">
          <button class="secondary" autofocus (click)="closeDialog.close()">
            Keep the party going</button
          ><button
            class="destructive"
            [disabled]="!client.can('GM_CLOSE_ROOM')"
            (click)="closeRoom()"
          >
            Close Room
          </button>
        </div>
      </dialog>
    }
  </section>`,
})
export class Lobby {
  readonly client = inject(RoomClient);
  private readonly browser = inject(BROWSER);
  private readonly route = inject(ActivatedRoute);
  readonly qr = signal('');
  readonly codeCopied = signal(false);
  readonly linkCopied = signal(false);
  readonly joinUrl = computed(() => {
    const roomId = this.client.room()?.roomId;
    return roomId ? joinRoomUrl(this.browser.origin, this.browser.basePath, roomId) : '';
  });
  readonly closeDialog = viewChild<ElementRef<HTMLDialogElement>>('closeDialog');
  readonly self = computed(() =>
    this.client.room()?.players.find((p) => p.playerId === this.client.room()?.currentPlayerId),
  );
  readonly gmConnected = computed(
    () =>
      this.client.room()?.players.find((p) => p.playerId === this.client.room()?.gmPlayerId)
        ?.connectionStatus === 'CONNECTED',
  );
  constructor() {
    this.client.resume(this.route.snapshot.paramMap.get('id') ?? '');
    effect(() => {
      const id = this.client.room()?.roomId;
      if (id)
        void QRCode.toDataURL(
          this.joinUrl(),
          {
            width: 232,
            margin: 1,
            color: { dark: '#34283d', light: '#fffdf8' },
          },
        )
          .then((url) => this.qr.set(url))
          .catch(() => this.qr.set(''));
    });
  }
  async copyCode(code: string): Promise<void> {
    if (await copyText(code)) {
      this.codeCopied.set(true);
    } else {
      this.client.notice.set('Select the room code above to copy it.');
    }
  }
  async copyLink(): Promise<void> {
    const url = this.joinUrl();
    if (url && (await copyText(url))) {
      this.linkCopied.set(true);
    } else {
      this.client.notice.set('Copy the join link from your browser address bar.');
    }
  }
  openClose(): void {
    this.closeDialog()?.nativeElement.showModal();
  }
  closeRoom(): void {
    this.closeDialog()?.nativeElement.close();
    this.client.action('GM_CLOSE_ROOM');
  }
  backdrop(event: MouseEvent): void {
    if (event.target === this.closeDialog()?.nativeElement)
      this.closeDialog()?.nativeElement.close();
  }
}
