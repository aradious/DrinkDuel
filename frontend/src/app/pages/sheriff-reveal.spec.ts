import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView, SheriffRevealView } from '../core/room.models';
import { SheriffReveal } from './sheriff-reveal';

const sheriffId = '11111111-1111-4111-8111-111111111111';
const deputyId = '22222222-2222-4222-8222-222222222222';
const outlawOneId = '33333333-3333-4333-8333-333333333333';
const outlawTwoId = '44444444-4444-4444-8444-444444444444';
const renegadeId = '55555555-5555-4555-8555-555555555555';
const excludedId = '66666666-6666-4666-8666-666666666666';

const game: SheriffRevealView = {
  gameType: 'SHERIFF',
  phase: 'REVEAL',
  participants: [
    { playerId: sheriffId, lifeStatus: 'ALIVE' },
    { playerId: deputyId, lifeStatus: 'DEAD' },
    { playerId: outlawOneId, lifeStatus: 'ALIVE' },
    { playerId: outlawTwoId, lifeStatus: 'DEAD' },
    { playerId: renegadeId, lifeStatus: 'ALIVE' },
  ],
  sheriffPlayerId: sheriffId,
  ownRole: 'SHERIFF',
  publicRoles: [
    { playerId: sheriffId, role: 'SHERIFF' },
    { playerId: deputyId, role: 'DEPUTY' },
    { playerId: outlawOneId, role: 'OUTLAW' },
    { playerId: outlawTwoId, role: 'OUTLAW' },
    { playerId: renegadeId, role: 'RENEGADE' },
  ],
  roleStatus: [
    { role: 'SHERIFF', assigned: 1, alive: 1, dead: 0 },
    { role: 'DEPUTY', assigned: 1, alive: 0, dead: 1 },
    { role: 'OUTLAW', assigned: 2, alive: 1, dead: 1 },
    { role: 'RENEGADE', assigned: 1, alive: 1, dead: 0 },
  ],
  overallStatus: { total: 5, alive: 3, dead: 2 },
};

const room: RoomView = {
  roomId: 'ABC234',
  roomRevision: 9,
  expiresAt: '2026-10-20T00:00:00Z',
  sessionId: '77777777-7777-4777-8777-777777777777',
  lifecycle: 'IN_GAME',
  currentPlayerId: sheriffId,
  gmPlayerId: sheriffId,
  isGm: true,
  capacity: 20,
  joinable: false,
  allowedActions: ['GET_STATE', 'GM_RESTART_SHERIFF', 'GM_BACK_TO_ROOM'],
  players: [
    { playerId: sheriffId, nickname: 'Aood', avatarId: 1, connectionStatus: 'CONNECTED' },
    { playerId: deputyId, nickname: 'Earth', avatarId: 2, connectionStatus: 'DISCONNECTED' },
    { playerId: outlawOneId, nickname: 'Mina', avatarId: 3, connectionStatus: 'CONNECTED' },
    { playerId: outlawTwoId, nickname: 'Ploy', avatarId: 4, connectionStatus: 'CONNECTED' },
    { playerId: renegadeId, nickname: 'Beam', avatarId: 5, connectionStatus: 'CONNECTED' },
    { playerId: excludedId, nickname: 'Late Friend', avatarId: 6, connectionStatus: 'CONNECTED' },
  ],
  game: null,
  liarsDice: null,
  sheriff: game,
};

describe('Sheriff REVEAL screen', () => {
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    notice: ReturnType<typeof signal<string>>;
    busy: ReturnType<typeof signal<boolean>>;
    can: (action: string) => boolean;
    resume: ReturnType<typeof vi.fn>;
    restartSheriff: ReturnType<typeof vi.fn>;
    backToRoom: ReturnType<typeof vi.fn>;
    chooseAnotherGame: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal<RoomView | null>({ ...room, players: [...room.players], sheriff: { ...game } }),
      notice: signal(''),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      restartSheriff: vi.fn(),
      backToRoom: vi.fn(),
      chooseAnotherGame: vi.fn(),
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
    const fixture = TestBed.createComponent(SheriffReveal);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  it('restores REVEAL and renders every locked participant in authoritative order', () => {
    const { root } = create();
    const cards = [...root.querySelectorAll<HTMLElement>('.role-card')];

    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(cards).toHaveLength(5);
    expect(cards.map((card) => card.querySelector('h3')?.textContent?.trim())).toEqual([
      'Aood',
      'Earth',
      'Mina',
      'Ploy',
      'Beam',
    ]);
    expect(cards.map((card) => card.querySelector('.role-name')?.textContent?.trim())).toEqual([
      'Sheriff',
      'Deputy',
      'Outlaw',
      'Outlaw',
      'Renegade',
    ]);
  });

  it('shows role artwork, final life state, avatars, and disconnected state', () => {
    const { root } = create();
    const deputy = [...root.querySelectorAll<HTMLElement>('.role-card')].find((card) =>
      card.textContent?.includes('Earth'),
    )!;

    expect(deputy.classList.contains('dead')).toBe(true);
    expect(deputy.textContent).toContain('DEAD');
    expect(deputy.textContent).toContain('Disconnected');
    expect(deputy.querySelector('.role-art img')?.getAttribute('src')).toBe(
      'assets/drinkduel/games/sheriff/characters/deputy.png',
    );
    expect(root.querySelectorAll('dd-avatar')).toHaveLength(6);
  });

  it('keeps excluded Room members visible without inventing an assignment', () => {
    const { root } = create();
    const excluded = root.querySelector('.excluded-panel') as HTMLElement;

    expect(excluded.textContent).toContain('Late Friend');
    expect(excluded.textContent).toContain('Not in this round · No role assigned');
    expect(excluded.querySelector('.role-art')).toBeNull();
    expect(root.querySelectorAll('.role-card')).toHaveLength(game.participants.length);
  });

  it('shows final authoritative Role Status and does not announce a winner', () => {
    const { root } = create();
    const status = root.querySelector('.role-status') as HTMLElement;

    expect(status.textContent).toContain('FINAL ROLE STATUS');
    expect(status.textContent).toMatch(/5\s*Total/);
    expect(status.textContent).toMatch(/3\s*Alive/);
    expect(status.textContent).toMatch(/2\s*Dead/);
    expect(status.querySelectorAll('.role-count')).toHaveLength(4);
    expect(root.textContent).toContain('No winner calculated');
    expect(root.textContent?.toLowerCase()).not.toContain('wins the game');
  });

  it('exposes only authoritative GM lifecycle actions', () => {
    const { root } = create();
    const buttons = [...root.querySelectorAll<HTMLButtonElement>('.round-actions button')];

    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'Play Again',
      'Back to Room',
      'Change Game',
    ]);
    buttons[0].click();
    buttons[1].click();
    buttons[2].click();
    expect(client.restartSheriff).toHaveBeenCalledOnce();
    expect(client.backToRoom).toHaveBeenCalledOnce();
    expect(client.chooseAnotherGame).toHaveBeenCalledOnce();
    expect(client.room()?.sheriff?.phase).toBe('REVEAL');
  });

  it('shows Players a waiting state without GM actions', () => {
    client.room.update((current) => ({
      ...current!,
      currentPlayerId: deputyId,
      isGm: false,
      allowedActions: ['GET_STATE'],
      sheriff: { ...game, ownRole: 'DEPUTY' },
    }));
    const { root } = create();

    expect(root.querySelector('.round-actions button')).toBeNull();
    expect(root.querySelector('.waiting')?.textContent).toContain('Waiting for the Game Master');
    expect(root.querySelectorAll('.role-card')).toHaveLength(5);
  });

  it('does not render final roles before the authoritative REVEAL snapshot', () => {
    client.room.update((current) => ({
      ...current!,
      sheriff: {
        ...game,
        phase: 'PLAYING' as const,
        publicRoles: [{ playerId: sheriffId, role: 'SHERIFF' as const }],
      },
    }));
    const { root } = create();

    expect(root.querySelector('.role-card')).toBeNull();
    expect(root.textContent).toContain('Restoring the final reveal');
    expect(root.textContent).not.toContain('Deputy');
    expect(root.textContent).not.toContain('Renegade');
  });
});
