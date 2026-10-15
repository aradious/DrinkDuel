import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView, SheriffPlayingView } from '../core/room.models';
import { SheriffPlaying } from './sheriff-playing';

const sheriffId = '11111111-1111-4111-8111-111111111111';
const deputyId = '22222222-2222-4222-8222-222222222222';
const outlawOneId = '33333333-3333-4333-8333-333333333333';
const outlawTwoId = '44444444-4444-4444-8444-444444444444';

const game: SheriffPlayingView = {
  gameType: 'SHERIFF',
  phase: 'PLAYING',
  participants: [
    { playerId: sheriffId, lifeStatus: 'ALIVE' },
    { playerId: deputyId, lifeStatus: 'ALIVE' },
    { playerId: outlawOneId, lifeStatus: 'ALIVE' },
    { playerId: outlawTwoId, lifeStatus: 'ALIVE' },
  ],
  sheriffPlayerId: sheriffId,
  ownRole: 'DEPUTY',
  publicRoles: [{ playerId: sheriffId, role: 'SHERIFF' }],
  roleStatus: [
    { role: 'SHERIFF', assigned: 1, alive: 1, dead: 0 },
    { role: 'DEPUTY', assigned: 1, alive: 1, dead: 0 },
    { role: 'OUTLAW', assigned: 2, alive: 2, dead: 0 },
    { role: 'RENEGADE', assigned: 0, alive: 0, dead: 0 },
  ],
  overallStatus: { total: 4, alive: 4, dead: 0 },
};

const room: RoomView = {
  roomId: 'ABC234',
  roomRevision: 5,
  expiresAt: '2026-10-20T00:00:00Z',
  sessionId: '55555555-5555-4555-8555-555555555555',
  lifecycle: 'IN_GAME',
  currentPlayerId: deputyId,
  gmPlayerId: sheriffId,
  isGm: false,
  capacity: 20,
  joinable: false,
  allowedActions: ['GET_STATE'],
  players: [
    {
      playerId: sheriffId,
      nickname: 'Aood',
      avatarId: 1,
      connectionStatus: 'CONNECTED',
    },
    {
      playerId: deputyId,
      nickname: 'Earth',
      avatarId: 2,
      connectionStatus: 'CONNECTED',
    },
    {
      playerId: outlawOneId,
      nickname: 'Mina',
      avatarId: 3,
      connectionStatus: 'CONNECTED',
    },
    {
      playerId: outlawTwoId,
      nickname: 'Ploy',
      avatarId: 4,
      connectionStatus: 'CONNECTED',
    },
  ],
  game: null,
  liarsDice: null,
  sheriff: game,
};

describe('Sheriff PLAYING screen', () => {
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    notice: ReturnType<typeof signal<string>>;
    busy: ReturnType<typeof signal<boolean>>;
    can: (action: string) => boolean;
    resume: ReturnType<typeof vi.fn>;
    markSheriffDead: ReturnType<typeof vi.fn>;
    endSheriff: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal<RoomView | null>({ ...room, players: [...room.players], sheriff: { ...game } }),
      notice: signal(''),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      markSheriffDead: vi.fn(() => client.busy.set(true)),
      endSheriff: vi.fn(() => client.busy.set(true)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: RoomClient, useValue: client },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'ABC234' }) } },
        },
      ],
    });
  });

  function create() {
    const fixture = TestBed.createComponent(SheriffPlaying);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  function pointer(type: string): Event {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperties(event, { button: { value: 0 }, pointerId: { value: 1 } });
    return event;
  }

  function hold(root: HTMLElement): HTMLButtonElement {
    const control = root.querySelector('.private-role-control') as HTMLButtonElement;
    control.dispatchEvent(pointer('pointerdown'));
    TestBed.flushEffects();
    return control;
  }

  it('restores the round and prominently shows the authoritative public Sheriff', () => {
    const { root } = create();
    const spotlight = root.querySelector('.sheriff-spotlight') as HTMLElement;

    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(spotlight.textContent).toContain('Aood is the Sheriff');
    expect(spotlight.textContent).toContain('ALIVE');
    expect(spotlight.querySelector('img')?.getAttribute('src')).toBe(
      'assets/drinkduel/games/sheriff/characters/sheriff.png',
    );
  });

  it('keeps a living private role out of the hidden card DOM and accessibility metadata', () => {
    const { root } = create();
    const control = root.querySelector('.private-role-control') as HTMLButtonElement;

    expect(control.getAttribute('aria-label')).toBe('Hold to reveal your private role');
    expect(control.getAttribute('aria-pressed')).toBe('false');
    expect(control.querySelector('img')?.getAttribute('src')).toBe(
      'assets/drinkduel/games/sheriff/cards/card-back.png',
    );
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');
    expect(control.outerHTML.toLowerCase()).not.toContain('outlaw');
    expect(control.outerHTML.toLowerCase()).not.toContain('renegade');
  });

  it('reveals only the authorized role while held and hides on release and cancellation', () => {
    const { fixture, root } = create();
    const control = hold(root);
    fixture.detectChanges();
    expect(control.textContent).toContain('Deputy');
    expect(control.querySelector('img')?.getAttribute('src')).toContain('/deputy.png');

    control.dispatchEvent(pointer('pointerup'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');

    control.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    control.dispatchEvent(pointer('pointercancel'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');
  });

  it('fails closed on pointer leave, control blur, and window blur', () => {
    const { fixture, root } = create();
    const control = hold(root);
    fixture.detectChanges();
    control.dispatchEvent(pointer('pointerleave'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');

    control.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    control.dispatchEvent(new FocusEvent('blur'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');

    control.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    window.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');
  });

  it.each([' ', 'Enter'])('supports hold-to-reveal with the %s key', (key) => {
    const { fixture, root } = create();
    const control = root.querySelector('.private-role-control') as HTMLButtonElement;
    control.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    fixture.detectChanges();
    expect(control.textContent).toContain('Deputy');
    control.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true }));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');
  });

  it('fails closed on page hiding, state loss, and component destruction', () => {
    const { fixture, root } = create();
    const control = hold(root);
    fixture.detectChanges();
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
    fixture.detectChanges();
    expect(control.outerHTML.toLowerCase()).not.toContain('deputy');
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });

    control.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    client.room.set(null);
    fixture.detectChanges();
    TestBed.flushEffects();
    expect(fixture.componentInstance.revealingRole()).toBe(false);
    fixture.destroy();
    expect(fixture.componentInstance.revealingRole()).toBe(false);
  });

  it('keeps the Sheriff own card face-up without a hold control', () => {
    client.room.update((current) => ({
      ...current!,
      currentPlayerId: sheriffId,
      isGm: true,
      sheriff: { ...game, ownRole: 'SHERIFF' },
    }));
    const { root } = create();
    const card = root.querySelector('.own-role-card.face-up') as HTMLElement;

    expect(card.textContent).toContain('Sheriff');
    expect(card.querySelector('img')?.getAttribute('src')).toContain('/sheriff.png');
    expect(root.querySelector('.private-role-control')).toBeNull();
  });

  it('renders all authoritative role and overall counts, including a zero role', () => {
    const { root } = create();
    const status = root.querySelector('.role-status') as HTMLElement;

    expect(status.querySelectorAll('.role-count')).toHaveLength(4);
    expect(status.textContent).toContain('Sheriff');
    expect(status.textContent).toContain('Deputy');
    expect(status.textContent).toContain('Outlaw');
    expect(status.textContent).toContain('Renegade');
    expect(status.querySelector('.overall-status')?.textContent).toMatch(/4\s*Total/);
    expect(status.querySelector('.overall-status')?.textContent).toMatch(/4\s*Alive/);
    expect(status.querySelector('.overall-status')?.textContent).toMatch(/0\s*Dead/);
  });

  it('renders an authoritative death and public role without changing another hidden role', () => {
    client.room.update((current) => ({
      ...current!,
      roomRevision: 6,
      sheriff: {
        ...game,
        participants: game.participants.map((participant) =>
          participant.playerId === outlawOneId
            ? { ...participant, lifeStatus: 'DEAD' as const }
            : participant,
        ),
        publicRoles: [...game.publicRoles, { playerId: outlawOneId, role: 'OUTLAW' as const }],
        roleStatus: game.roleStatus.map((status) =>
          status.role === 'OUTLAW' ? { ...status, alive: 1, dead: 1 } : status,
        ),
        overallStatus: { total: 4, alive: 3, dead: 1 },
      },
    }));
    const { root } = create();
    const mina = [...root.querySelectorAll<HTMLElement>('.player-card')].find((card) =>
      card.textContent?.includes('Mina'),
    )!;
    const ploy = [...root.querySelectorAll<HTMLElement>('.player-card')].find((card) =>
      card.textContent?.includes('Ploy'),
    )!;

    expect(mina.textContent).toContain('DEAD');
    expect(mina.textContent).toContain('Outlaw');
    expect(ploy.textContent).toContain('Role hidden');
    expect(ploy.textContent).not.toContain('Outlaw');
  });

  it('shows no GM actions to a Player or excluded observer', () => {
    client.room.update((current) => ({
      ...current!,
      currentPlayerId: '99999999-9999-4999-8999-999999999999',
      players: [
        ...current!.players,
        {
          playerId: '99999999-9999-4999-8999-999999999999',
          nickname: 'Observer',
          avatarId: 5,
          connectionStatus: 'CONNECTED' as const,
        },
      ],
      sheriff: { ...game, ownRole: null },
    }));
    const { root } = create();

    expect(root.textContent).toContain("You weren't in the locked roster");
    expect(root.querySelector('.private-role-control')).toBeNull();
    expect(root.querySelector('.mark-dead')).toBeNull();
    expect(root.querySelector('.end-game')).toBeNull();
    expect(root.querySelector('.confirm-death')).toBeNull();
    expect(root.querySelector('.confirm-end')).toBeNull();
    expect(root.querySelector('.sheriff-spotlight')?.textContent).toContain('Aood is the Sheriff');
  });

  it('confirms a GM death command, including self-targeting, without an optimistic update', () => {
    client.room.update((current) => ({
      ...current!,
      currentPlayerId: sheriffId,
      isGm: true,
      allowedActions: ['GET_STATE', 'GM_MARK_SHERIFF_DEAD', 'GM_END_SHERIFF'],
      sheriff: { ...game, ownRole: 'SHERIFF' },
    }));
    const { fixture, root } = create();
    const dialog = root.querySelector<HTMLDialogElement>('.confirm-dialog')!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();

    (root.querySelector(`[aria-label="Mark Aood dead"]`) as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(dialog.showModal).toHaveBeenCalledOnce();
    expect(dialog.textContent).toContain('Mark Aood dead?');
    (root.querySelector('.confirm-death') as HTMLButtonElement).click();
    fixture.detectChanges();
    (root.querySelector('.confirm-death') as HTMLButtonElement).click();

    expect(client.markSheriffDead).toHaveBeenCalledOnce();
    expect(client.markSheriffDead).toHaveBeenCalledWith(sheriffId);
    expect(client.room()?.sheriff?.participants[0].lifeStatus).toBe('ALIVE');
  });

  it('requires confirmation for End Game and leaves routing to the authoritative snapshot', () => {
    client.room.update((current) => ({
      ...current!,
      currentPlayerId: sheriffId,
      isGm: true,
      allowedActions: ['GM_END_SHERIFF'],
      sheriff: { ...game, ownRole: 'SHERIFF' },
    }));
    const { fixture, root } = create();
    const endDialog = root.querySelector<HTMLDialogElement>('.confirm-dialog')!;
    endDialog.showModal = vi.fn();
    endDialog.close = vi.fn();

    (root.querySelector('.end-game') as HTMLButtonElement).click();
    expect(endDialog.showModal).toHaveBeenCalledOnce();
    (root.querySelector('.confirm-end') as HTMLButtonElement).click();
    fixture.detectChanges();
    (root.querySelector('.confirm-end') as HTMLButtonElement).click();

    expect(client.endSheriff).toHaveBeenCalledOnce();
    expect(client.room()?.sheriff?.phase).toBe('PLAYING');
  });

  it('restores reconnect state covered and keeps disconnected locked players in the roster', () => {
    client.room.update((current) => ({
      ...current!,
      roomRevision: 7,
      players: current!.players.map((player) =>
        player.playerId === outlawTwoId
          ? { ...player, connectionStatus: 'DISCONNECTED' as const }
          : player,
      ),
    }));
    const { root } = create();

    expect(root.querySelector('.private-role-control')?.outerHTML.toLowerCase()).not.toContain(
      'deputy',
    );
    expect(root.querySelectorAll('.player-card')).toHaveLength(4);
    expect(root.textContent).toContain('Disconnected');
    expect(root.querySelector('.overall-status')?.textContent).toMatch(/4\s*Alive/);
  });

  it('ships reduced-motion overrides for private-card movement', () => {
    create();
    const styles = [...document.querySelectorAll('style')]
      .map((style) => style.textContent ?? '')
      .join('\n');
    expect(styles).toContain('prefers-reduced-motion: reduce');
    expect(styles).toContain('transition: none');
  });
});
