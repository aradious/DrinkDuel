import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BROWSER } from './browser';
import { RoomClient } from './room-client';
import { RoomView } from './room.models';

class Socket {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sent: Record<string, unknown>[] = [];
  send(value: string): void {
    this.sent.push(JSON.parse(value));
  }
  close(): void {}
  open(): void {
    this.onopen?.();
  }
  emit(value: unknown): void {
    this.onmessage?.({ data: JSON.stringify(value) });
  }
  reply(accepted = true): void {
    this.emit({
      type: 'COMMAND_RESULT',
      requestId: this.sent.at(-1)?.['requestId'],
      accepted,
    });
  }
}

const GM = '11111111-1111-4111-8111-111111111111';
const PLAYER = '22222222-2222-4222-8222-222222222222';
const THIRD = '33333333-3333-4333-8333-333333333333';
const FOURTH = '44444444-4444-4444-8444-444444444444';
const SESSION = '55555555-5555-4555-8555-555555555555';
const participants = [GM, PLAYER, THIRD, FOURTH].map((playerId) => ({
  playerId,
  lifeStatus: 'ALIVE',
}));
const roleStatus = [
  { role: 'SHERIFF', assigned: 1, alive: 1, dead: 0 },
  { role: 'DEPUTY', assigned: 1, alive: 1, dead: 0 },
  { role: 'OUTLAW', assigned: 2, alive: 2, dead: 0 },
  { role: 'RENEGADE', assigned: 0, alive: 0, dead: 0 },
];
const sheriff = (phase: 'START' | 'PLAYING' | 'REVEAL', ownRole: string | null = 'DEPUTY') =>
  phase === 'START'
    ? {
        gameType: 'SHERIFF',
        phase,
        participants: [],
        sheriffPlayerId: null,
        ownRole: null,
        publicRoles: [],
        roleStatus: [],
        overallStatus: null,
      }
    : {
        gameType: 'SHERIFF',
        phase,
        participants,
        sheriffPlayerId: GM,
        ownRole,
        publicRoles:
          phase === 'REVEAL'
            ? [
                { playerId: GM, role: 'SHERIFF' },
                { playerId: PLAYER, role: 'DEPUTY' },
                { playerId: THIRD, role: 'OUTLAW' },
                { playerId: FOURTH, role: 'OUTLAW' },
              ]
            : [{ playerId: GM, role: 'SHERIFF' }],
        roleStatus,
        overallStatus: { total: 4, alive: 4, dead: 0 },
      };

const room = (overrides: Partial<RoomView> = {}): RoomView => ({
  roomId: 'ABC234',
  roomRevision: 1,
  expiresAt: '2026-10-20T00:00:00Z',
  sessionId: null,
  lifecycle: 'LOBBY',
  currentPlayerId: PLAYER,
  gmPlayerId: GM,
  isGm: false,
  capacity: 20,
  joinable: true,
  allowedActions: ['GET_STATE'],
  players: [
    { playerId: GM, nickname: 'Host', avatarId: 1, connectionStatus: 'CONNECTED' },
    { playerId: PLAYER, nickname: 'Player', avatarId: 2, connectionStatus: 'CONNECTED' },
    { playerId: THIRD, nickname: 'Third', avatarId: 3, connectionStatus: 'CONNECTED' },
    { playerId: FOURTH, nickname: 'Fourth', avatarId: 4, connectionStatus: 'CONNECTED' },
  ],
  ...overrides,
});

describe('RoomClient Sheriff state and routing', () => {
  let client: RoomClient;
  let sockets: Socket[];
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    localStorage.setItem('drinkduel.rooms', JSON.stringify({ ABC234: 'guest' }));
    sockets = [];
    navigate = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        RoomClient,
        { provide: Router, useValue: { navigate } },
        {
          provide: BROWSER,
          useValue: {
            storage: localStorage,
            origin: 'http://localhost:4200',
            basePath: '/',
            request: vi.fn(),
            requestId: () => crypto.randomUUID(),
            randomToken: () => 'A'.repeat(43),
            socket: () => {
              const socket = new Socket();
              sockets.push(socket);
              return socket;
            },
          },
        },
      ],
    });
    client = TestBed.inject(RoomClient);
  });

  afterEach(() => {
    client.home();
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  function connect(view: Omit<RoomView, 'sheriff'> & { sheriff?: unknown }): Socket {
    client.resume('ABC234');
    const socket = sockets.at(-1)!;
    socket.open();
    socket.emit({ type: 'STATE', room: view });
    socket.reply();
    return socket;
  }

  function sheriffRoom(
    phase: 'START' | 'PLAYING' | 'REVEAL',
    overrides: Partial<RoomView> = {},
    ownRole: string | null = 'DEPUTY',
  ): Omit<RoomView, 'sheriff'> & { sheriff: unknown } {
    return {
      ...room({
        lifecycle: 'IN_GAME',
        sessionId: SESSION,
        joinable: false,
        ...overrides,
      }),
      sheriff: sheriff(phase, ownRole),
    };
  }

  it.each(['START', 'PLAYING', 'REVEAL'] as const)(
    'restores and routes the authoritative %s phase',
    (phase) => {
      connect(sheriffRoom(phase));

      expect(client.room()?.sheriff?.phase).toBe(phase);
      expect(navigate).toHaveBeenLastCalledWith([
        '/room',
        'ABC234',
        'sheriff',
        phase.toLowerCase(),
      ]);
    },
  );

  it('routes every client only after the authoritative End Game snapshot enters REVEAL', () => {
    const socket = connect(sheriffRoom('PLAYING', { roomRevision: 1 }));
    navigate.mockClear();

    socket.emit({
      type: 'STATE',
      room: sheriffRoom('REVEAL', { roomRevision: 2 }),
    });

    expect(client.room()?.sheriff?.phase).toBe('REVEAL');
    expect(client.room()?.sheriff?.publicRoles).toHaveLength(4);
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234', 'sheriff', 'reveal']);
  });

  it('restores the same private role after reconnect without persisting it in browser storage', () => {
    connect(sheriffRoom('PLAYING'));
    expect(client.room()?.sheriff?.ownRole).toBe('DEPUTY');
    expect(JSON.stringify(localStorage)).not.toContain('DEPUTY');

    client.home();
    navigate.mockClear();
    connect(sheriffRoom('PLAYING', { roomRevision: 2 }));

    expect(client.room()?.sheriff?.ownRole).toBe('DEPUTY');
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234', 'sheriff', 'playing']);
  });

  it('keeps an excluded member on PLAYING without manufacturing a private role', () => {
    const excluded = '66666666-6666-4666-8666-666666666666';
    connect(
      sheriffRoom(
        'PLAYING',
        {
          currentPlayerId: excluded,
          players: [
            ...room().players,
            { playerId: excluded, nickname: 'Late', avatarId: 5, connectionStatus: 'CONNECTED' },
          ],
        },
        null,
      ),
    );

    expect(client.room()?.sheriff?.ownRole).toBeNull();
    expect(client.room()?.sheriff?.participants.some((item) => item.playerId === excluded)).toBe(
      false,
    );
  });

  it('uses only authoritative allowedActions for GM commands and includes session guards', () => {
    const socket = connect(
      sheriffRoom(
        'PLAYING',
        {
          currentPlayerId: GM,
          isGm: true,
          allowedActions: ['GM_MARK_SHERIFF_DEAD', 'GM_END_SHERIFF'],
        },
        'SHERIFF',
      ),
    );

    client.markSheriffDead(PLAYER);
    client.markSheriffDead(PLAYER);
    expect(
      socket.sent.filter((message) => message['type'] === 'GM_MARK_SHERIFF_DEAD'),
    ).toHaveLength(1);
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_MARK_SHERIFF_DEAD',
      sessionId: SESSION,
      targetPlayerId: PLAYER,
    });
    socket.reply();
    client.endSheriff();
    expect(socket.sent.at(-1)).toMatchObject({ type: 'GM_END_SHERIFF', sessionId: SESSION });
  });

  it('does not send forged GM commands for a Player snapshot', () => {
    const socket = connect(sheriffRoom('PLAYING', { allowedActions: ['GET_STATE'] }));
    const before = socket.sent.length;

    client.startSheriff();
    client.markSheriffDead(GM);
    client.endSheriff();
    client.restartSheriff();

    expect(socket.sent).toHaveLength(before);
  });

  it('sends select, start and restart only when each authoritative action is advertised', () => {
    let socket = connect(
      room({
        currentPlayerId: GM,
        isGm: true,
        allowedActions: ['GM_SELECT_SHERIFF'],
      }),
    );
    client.selectSheriff();
    expect(socket.sent.at(-1)?.['type']).toBe('GM_SELECT_SHERIFF');

    client.home();
    socket = connect(
      sheriffRoom('START', {
        currentPlayerId: GM,
        isGm: true,
        allowedActions: ['GM_START_SHERIFF'],
      }),
    );
    client.startSheriff();
    expect(socket.sent.at(-1)).toMatchObject({ type: 'GM_START_SHERIFF', sessionId: SESSION });

    client.home();
    socket = connect(
      sheriffRoom(
        'REVEAL',
        {
          currentPlayerId: GM,
          isGm: true,
          allowedActions: ['GM_RESTART_SHERIFF'],
        },
        'SHERIFF',
      ),
    );
    client.restartSheriff();
    expect(socket.sent.at(-1)).toMatchObject({ type: 'GM_RESTART_SHERIFF', sessionId: SESSION });
  });

  it('rejects a malformed or privacy-leaking Sheriff view and does not route to Sheriff', () => {
    const leaking = sheriffRoom('PLAYING');
    const sheriffView = leaking.sheriff as Record<string, unknown>;
    const publicRoles = sheriffView['publicRoles'] as unknown[];
    leaking.sheriff = {
      ...sheriffView,
      publicRoles: [...publicRoles, { playerId: THIRD, role: 'OUTLAW' }],
    };

    connect(leaking);

    expect(client.room()?.sheriff).toBeNull();
    expect(navigate).not.toHaveBeenCalledWith(['/room', 'ABC234', 'sheriff', 'playing']);
  });
});
