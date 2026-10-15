import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { SheriffStart } from './sheriff-start';

const players: RoomView['players'] = [
  { playerId: 'gm', nickname: 'Aood', avatarId: 1, connectionStatus: 'CONNECTED' },
  { playerId: 'p1', nickname: 'Earth', avatarId: 2, connectionStatus: 'CONNECTED' },
  { playerId: 'p2', nickname: 'Mina', avatarId: 3, connectionStatus: 'CONNECTED' },
  { playerId: 'p3', nickname: 'Ploy', avatarId: 4, connectionStatus: 'CONNECTED' },
];

const base: RoomView = {
  roomId: 'ABC234',
  roomRevision: 1,
  expiresAt: '2026-10-20T00:00:00Z',
  sessionId: '55555555-5555-4555-8555-555555555555',
  lifecycle: 'IN_GAME',
  currentPlayerId: 'gm',
  gmPlayerId: 'gm',
  isGm: true,
  capacity: 20,
  joinable: false,
  allowedActions: ['GET_STATE', 'GM_KICK_PLAYER', 'GM_BACK_TO_ROOM', 'GM_START_SHERIFF'],
  players,
  sheriff: {
    gameType: 'SHERIFF',
    phase: 'START',
    participants: [],
    sheriffPlayerId: null,
    ownRole: null,
    publicRoles: [],
    roleStatus: [],
    overallStatus: null,
  },
};

describe('SheriffStart', () => {
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    busy: ReturnType<typeof signal<boolean>>;
    notice: ReturnType<typeof signal<string>>;
    can: (action: string) => boolean;
    resume: ReturnType<typeof vi.fn>;
    action: ReturnType<typeof vi.fn>;
    startSheriff: ReturnType<typeof vi.fn>;
    backToRoom: ReturnType<typeof vi.fn>;
    chooseAnotherGame: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal<RoomView | null>({ ...base, players: [...players] }),
      busy: signal(false),
      notice: signal(''),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      action: vi.fn(),
      startSheriff: vi.fn(),
      backToRoom: vi.fn(() => client.busy.set(true)),
      chooseAnotherGame: vi.fn(() => client.busy.set(true)),
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

  it('restores the room and renders the authoritative roster with identity states', () => {
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(root.querySelectorAll('.player-card')).toHaveLength(4);
    expect(root.textContent).toContain('Aood');
    expect(root.textContent).toContain('Earth');
    expect(root.textContent).toContain('Game Master');
    expect(root.textContent).toContain('YOU');
    expect(root.querySelector('.roster-counts strong')?.textContent).toContain('4');
    expect(root.querySelector('.roster-counts span')?.textContent).toContain('connected');
  });

  it('starts once through the Sheriff command while the server advertises it', () => {
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const start = (fixture.nativeElement as HTMLElement).querySelector(
      'button.start-game',
    ) as HTMLButtonElement;

    expect(start.disabled).toBe(false);
    start.click();
    expect(client.startSheriff).toHaveBeenCalledOnce();
    client.busy.set(true);
    fixture.detectChanges();
    expect(start.disabled).toBe(true);
    start.click();
    expect(client.startSheriff).toHaveBeenCalledOnce();
  });

  it('shows only a waiting state to a normal player', () => {
    client.room.set({
      ...base,
      currentPlayerId: 'p1',
      isGm: false,
      allowedActions: ['GET_STATE'],
    });
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Waiting for the Game Master to start');
    expect(root.querySelector('.start-game')).toBeNull();
    expect(root.querySelector('.remove-player')).toBeNull();
    expect(root.querySelector('.secondary-actions')).toBeNull();
  });

  it('allows START-only removal of non-GM members through the existing command', () => {
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('button[aria-label="Remove Aood"]')).toBeNull();
    (root.querySelector('button[aria-label="Remove Earth"]') as HTMLButtonElement).click();
    expect(client.action).toHaveBeenCalledWith('GM_KICK_PLAYER', 'p1');

    client.room.update((room) => ({ ...room!, allowedActions: ['GET_STATE'] }));
    fixture.detectChanges();
    expect(root.querySelector('.remove-player')).toBeNull();
  });

  it('reacts to authoritative presence and action changes without local roster state', () => {
    client.room.set({
      ...base,
      allowedActions: ['GET_STATE', 'GM_KICK_PLAYER', 'GM_BACK_TO_ROOM'],
      players: players.map((player, index) =>
        index === 3 ? { ...player, connectionStatus: 'DISCONNECTED' } : player,
      ),
    });
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const start = root.querySelector('button.start-game') as HTMLButtonElement;

    expect(start.disabled).toBe(true);
    expect(root.querySelector('.roster-counts strong')?.textContent).toContain('3');
    expect(root.textContent).toContain('At least 4 connected players are needed to start.');
    expect(root.textContent).toContain('Disconnected');

    client.room.update((room) => ({
      ...room!,
      roomRevision: 2,
      allowedActions: [...room!.allowedActions, 'GM_START_SHERIFF'],
      players: room!.players.map((player) => ({ ...player, connectionStatus: 'CONNECTED' })),
    }));
    fixture.detectChanges();
    expect(root.querySelector('.roster-counts strong')?.textContent).toContain('4');
    expect(start.disabled).toBe(false);
  });

  it('offers authoritative Back to Room and Choose Another Game without optimistic routing', () => {
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const back = root.querySelector('button.back-to-room') as HTMLButtonElement;
    const choose = root.querySelector('button.choose-another') as HTMLButtonElement;

    back.click();
    fixture.detectChanges();
    expect(client.backToRoom).toHaveBeenCalledOnce();
    expect(back.disabled).toBe(true);
    expect(choose.disabled).toBe(true);
    expect(client.chooseAnotherGame).not.toHaveBeenCalled();

    client.busy.set(false);
    fixture.detectChanges();
    choose.click();
    expect(client.chooseAnotherGame).toHaveBeenCalledOnce();
  });

  it('renders a 20-player roster and safe long nicknames without changing the roster', () => {
    const twenty = Array.from({ length: 20 }, (_, index) => ({
      playerId: index === 0 ? 'gm' : `player-${index}`,
      nickname:
        index === 19
          ? 'A very long player nickname that must stay inside the card'
          : `Player ${index + 1}`,
      avatarId: (index % 30) + 1,
      connectionStatus: index % 3 === 0 ? ('DISCONNECTED' as const) : ('CONNECTED' as const),
    }));
    client.room.update((room) => ({ ...room!, players: twenty }));
    const fixture = TestBed.createComponent(SheriffStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('.player-card')).toHaveLength(20);
    expect(root.textContent).toContain('A very long player nickname');
    expect(client.room()?.players).toEqual(twenty);
  });
});
