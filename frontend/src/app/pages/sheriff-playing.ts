import {
  Component,
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
import { PlayerView, SheriffPlayingView, SheriffRole } from '../core/room.models';
import { SheriffPlayingDialogs } from './sheriff-playing-dialogs';
import { SheriffPlayingExcluded } from './sheriff-playing-excluded';
import { SheriffPlayingRoster } from './sheriff-playing-roster';
import { SheriffPlayingSpotlight } from './sheriff-playing-spotlight';
import { SheriffPlayingStatus } from './sheriff-playing-status';

@Component({
  imports: [
    SheriffPlayingDialogs,
    SheriffPlayingExcluded,
    SheriffPlayingRoster,
    SheriffPlayingSpotlight,
    SheriffPlayingStatus,
  ],
  styleUrl: './sheriff-playing.scss',
  template: `
    <section class="sheriff-playing page-enter">
      <div class="scene" aria-hidden="true"></div>
      @if (room(); as room) {
        <main class="playing-shell">
          <nav class="game-nav row between" aria-label="Game context">
            <span class="game-label gold-label">SHERIFF · SHOWDOWN</span>
            <div class="room-context row" aria-label="Current room code">
              <span class="gold-label">ROOM</span><strong>{{ room.roomId }}</strong>
            </div>
          </nav>

          <header class="game-heading row between mobile-stack compact-panel">
            <div>
              <p class="eyebrow gold-label">THE TOWN IS WATCHING</p>
              <h1>Trust carefully.</h1>
              <p class="muted-copy">
                Play the showdown together, then let the Game Master record who falls.
              </p>
            </div>
            <span class="role-chip dark-surface">{{ room.isGm ? 'Game Master' : 'Player' }}</span>
          </header>

          @if (publicSheriff(); as sheriff) {
            <dd-sheriff-playing-spotlight [sheriff]="sheriff" />
          }

          <div class="role-dashboard">
            <section
              class="role-vault western-panel role-panel compact-panel"
              aria-labelledby="your-role-title"
            >
              <div class="section-heading row between">
                <p class="eyebrow gold-label">YOUR ROLE</p>
                <h2 id="your-role-title">Your card</h2>
              </div>

              @if (currentParticipant(); as participant) {
                @if (faceUpOwnRole(); as role) {
                  <article
                    class="own-role-card face-up"
                    [class.dead-card]="participant.lifeStatus === 'DEAD'"
                  >
                    <img
                      [src]="roleArtwork(role)"
                      width="1024"
                      height="1536"
                      [alt]="roleLabel(role) + ' role artwork'"
                    />
                    <div class="role-caption">
                      <span>{{ participant.lifeStatus }}</span>
                      <strong>{{ roleLabel(role) }}</strong>
                    </div>
                  </article>
                  <p class="role-help muted-copy">
                    {{
                      role === 'SHERIFF'
                        ? 'Your role is public from the start.'
                        : 'Your revealed role is now public.'
                    }}
                  </p>
                } @else if (privateRole()) {
                  <button
                    class="own-role-card private-role-control"
                    type="button"
                    aria-label="Hold to reveal your private role"
                    [attr.aria-pressed]="revealingRole()"
                    (pointerdown)="revealPointer($event)"
                    (pointerup)="hideRole()"
                    (pointercancel)="hideRole()"
                    (pointerleave)="hideRole()"
                    (keydown)="revealKey($event)"
                    (keyup)="hideKey($event)"
                    (blur)="hideRole()"
                  >
                    @if (revealingRole(); as revealing) {
                      @if (privateRole(); as role) {
                        <img
                          [src]="roleArtwork(role)"
                          width="1024"
                          height="1536"
                          [alt]="roleLabel(role) + ' role artwork'"
                          draggable="false"
                        />
                        <div class="role-caption">
                          <span>RELEASE TO HIDE</span>
                          <strong>{{ roleLabel(role) }}</strong>
                        </div>
                      }
                    } @else {
                      <img
                        src="assets/drinkduel/games/sheriff/cards/card-back.png"
                        width="1536"
                        height="1024"
                        alt=""
                        draggable="false"
                      />
                      <div class="role-caption hidden-caption">
                        <span>PRIVATE</span>
                        <strong>Hold to Reveal</strong>
                      </div>
                    }
                  </button>
                  <p class="role-help muted-copy">Press and hold. Release to hide your role.</p>
                }
              } @else {
                <dd-sheriff-playing-excluded />
              }
            </section>

            <dd-sheriff-playing-status [game]="game()" />
          </div>

          <dd-sheriff-playing-roster
            [room]="room"
            [game]="game()"
            [entries]="roster()"
            (deathRequested)="openDeathDialog($event)"
            (endRequested)="openEndDialog()"
          />

          @if (client.notice()) {
            <p class="notice" role="status">{{ client.notice() }}</p>
          }
        </main>
      } @else {
        <main class="loading" role="status">Restoring the Sheriff showdown…</main>
      }

      @if (room(); as room) {
        <dd-sheriff-playing-dialogs
          [room]="room"
          [deathTarget]="deathTarget()"
          (deathConfirmed)="confirmDeath()"
          (deathClosed)="clearDeathTarget()"
          (endConfirmed)="confirmEnd()"
        />
      }
    </section>
  `,
})
export class SheriffPlaying implements OnDestroy {
  readonly client = inject(RoomClient);
  readonly roomId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  readonly dialogs = viewChild(SheriffPlayingDialogs);
  readonly revealingRole = signal(false);
  readonly deathTargetId = signal<string | null>(null);
  readonly room = computed(() => {
    const room = this.client.room();
    return room?.roomId === this.roomId && room.sheriff?.phase === 'PLAYING' ? room : null;
  });
  readonly game = computed(() => this.room()!.sheriff as SheriffPlayingView);
  readonly currentParticipant = computed(() => {
    const room = this.room();
    return room?.sheriff?.participants.find(
      (participant) => participant.playerId === room.currentPlayerId,
    );
  });
  readonly privateRole = computed(() => {
    const game = this.room()?.sheriff;
    const participant = this.currentParticipant();
    return game?.phase === 'PLAYING' &&
      participant?.lifeStatus === 'ALIVE' &&
      game.ownRole !== 'SHERIFF'
      ? game.ownRole
      : null;
  });
  readonly faceUpOwnRole = computed(() => {
    const game = this.room()?.sheriff;
    const participant = this.currentParticipant();
    if (game?.phase !== 'PLAYING' || !participant || !game.ownRole) return null;
    return game.ownRole === 'SHERIFF' || participant.lifeStatus === 'DEAD' ? game.ownRole : null;
  });
  readonly roster = computed(() => {
    const room = this.room();
    if (!room || room.sheriff?.phase !== 'PLAYING') return [];
    const players = new Map(room.players.map((player) => [player.playerId, player]));
    const roles = new Map(room.sheriff.publicRoles.map((entry) => [entry.playerId, entry.role]));
    return room.sheriff.participants.flatMap((participant) => {
      const player = players.get(participant.playerId);
      return player
        ? [{ player, lifeStatus: participant.lifeStatus, publicRole: roles.get(player.playerId) }]
        : [];
    });
  });
  readonly publicSheriff = computed(() => {
    const room = this.room();
    if (!room || room.sheriff?.phase !== 'PLAYING') return null;
    const player = room.players.find(
      (candidate) => candidate.playerId === room.sheriff!.sheriffPlayerId,
    );
    const participant = room.sheriff.participants.find(
      (candidate) => candidate.playerId === room.sheriff!.sheriffPlayerId,
    );
    return player && participant ? { player, lifeStatus: participant.lifeStatus } : null;
  });
  readonly deathTarget = computed<PlayerView | null>(() => {
    const targetId = this.deathTargetId();
    return this.room()?.players.find((player) => player.playerId === targetId) ?? null;
  });

  constructor() {
    this.client.resume(this.roomId);
    effect(() => {
      if (!this.privateRole()) this.hideRole();
      const targetId = this.deathTargetId();
      if (
        targetId &&
        (!this.room()?.allowedActions.includes('GM_MARK_SHERIFF_DEAD') ||
          !this.room()?.sheriff?.participants.some(
            (participant) =>
              participant.playerId === targetId && participant.lifeStatus === 'ALIVE',
          ))
      ) {
        this.clearDeathTarget();
        this.dialogs()?.closeDeath();
      }
    });
  }

  roleLabel(role: SheriffRole): string {
    return role.charAt(0) + role.slice(1).toLowerCase();
  }

  roleArtwork(role: SheriffRole): string {
    return `assets/drinkduel/games/sheriff/characters/${role.toLowerCase()}.png`;
  }

  revealPointer(event: PointerEvent): void {
    if (event.button !== 0 || !this.privateRole()) return;
    event.preventDefault();
    (event.currentTarget as HTMLElement | null)?.setPointerCapture?.(event.pointerId);
    this.revealingRole.set(true);
  }

  revealKey(event: KeyboardEvent): void {
    if ((event.key !== ' ' && event.key !== 'Enter') || event.repeat || !this.privateRole()) return;
    event.preventDefault();
    this.revealingRole.set(true);
  }

  hideKey(event: KeyboardEvent): void {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    this.hideRole();
  }

  hideRole(): void {
    this.revealingRole.set(false);
  }

  @HostListener('window:blur')
  onWindowBlur(): void {
    this.hideRole();
  }

  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'hidden') this.hideRole();
  }

  openDeathDialog(playerId: string): void {
    if (!this.client.can('GM_MARK_SHERIFF_DEAD')) return;
    const alive = this.room()?.sheriff?.participants.some(
      (participant) => participant.playerId === playerId && participant.lifeStatus === 'ALIVE',
    );
    if (!alive) return;
    this.hideRole();
    this.deathTargetId.set(playerId);
    this.dialogs()?.openDeath();
  }

  closeDeathDialog(): void {
    this.dialogs()?.closeDeath();
    this.clearDeathTarget();
  }

  clearDeathTarget(): void {
    this.deathTargetId.set(null);
  }

  confirmDeath(): void {
    const targetId = this.deathTargetId();
    if (!targetId || !this.client.can('GM_MARK_SHERIFF_DEAD')) return;
    this.client.markSheriffDead(targetId);
    this.closeDeathDialog();
  }

  openEndDialog(): void {
    if (!this.client.can('GM_END_SHERIFF')) return;
    this.hideRole();
    this.dialogs()?.openEnd();
  }

  closeEndDialog(): void {
    this.dialogs()?.closeEnd();
  }

  confirmEnd(): void {
    if (!this.client.can('GM_END_SHERIFF')) return;
    this.client.endSheriff();
    this.closeEndDialog();
  }

  ngOnDestroy(): void {
    this.hideRole();
    this.clearDeathTarget();
  }
}
