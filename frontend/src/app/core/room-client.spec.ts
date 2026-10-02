import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BROWSER } from './browser';
import { RoomClient } from './room-client';
import { RoomView, friendlyError } from './room.models';

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
  reply(accepted: boolean, code?: string): void {
    this.emit({
      type: 'COMMAND_RESULT',
      requestId: this.sent.at(-1)?.['requestId'],
      accepted,
      code,
    });
  }
}
export const snapshot = (overrides: Partial<RoomView> = {}): RoomView => ({
  roomId: 'ABC234',
  roomRevision: 1,
  expiresAt: '2026-10-01T00:00:00Z',
  sessionId: null,
  lifecycle: 'LOBBY',
  currentPlayerId: 'guest',
  gmPlayerId: 'gm',
  isGm: false,
  capacity: 20,
  joinable: true,
  allowedActions: ['GET_STATE', 'LEAVE_ROOM'],
  players: [
    { playerId: 'gm', nickname: 'Host', avatarId: 1, connectionStatus: 'CONNECTED' },
    { playerId: 'guest', nickname: 'Ken', avatarId: 25, connectionStatus: 'CONNECTED' },
  ],
  ...overrides,
});
const submitGame = {
  gameType: 'WHO_AM_I' as const,
  phase: 'SUBMIT_NAME' as const,
  participants: [
    { playerId: 'gm', submitted: false, resetSubmissionId: null },
    { playerId: 'guest', submitted: false, resetSubmissionId: null },
  ],
  submittedCount: 0,
  participantCount: 2,
  currentPlayerSubmitted: false,
  readyForShuffle: false,
  canShuffle: false,
  cards: [],
  roast: null,
  reveal: [],
};
describe('RoomClient', () => {
  let client: RoomClient,
    sockets: Socket[],
    fetchMock: ReturnType<typeof vi.fn>,
    navigate: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    sockets = [];
    fetchMock = vi.fn();
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
            request: fetchMock,
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
  function attach(view = snapshot()): Socket {
    client.join('abc234', 'Ken');
    const socket = sockets.at(-1)!;
    socket.open();
    socket.reply(false, 'NOT_AUTHORIZED');
    socket.emit({ type: 'STATE', room: view });
    socket.reply(true);
    return socket;
  }
  it('resumes a guest before trying a new join and never identifies by nickname', () => {
    client.join(' abc234 ', 'Ken');
    const socket = sockets[0];
    socket.open();
    expect(socket.sent[0]['type']).toBe('RESUME_ROOM');
    expect(socket.sent[0]['nickname']).toBeUndefined();
    socket.reply(false, 'NOT_AUTHORIZED');
    expect(socket.sent[1]['type']).toBe('JOIN_ROOM');
    expect(socket.sent[1]['guestToken']).toBe(socket.sent[0]['guestToken']);
  });
  it('accepts authoritative updates and ignores older revisions', () => {
    const socket = attach();
    socket.emit({ type: 'STATE', room: snapshot({ roomRevision: 3, players: [] }) });
    socket.emit({ type: 'STATE', room: snapshot({ roomRevision: 2 }) });
    expect(client.room()?.players).toEqual([]);
    expect(client.room()?.roomRevision).toBe(3);
  });
  it('only sends currently allowed controls and prevents duplicate clicks', () => {
    const socket = attach();
    client.action('GM_CLOSE_ROOM');
    expect(socket.sent).toHaveLength(2);
    client.action('LEAVE_ROOM');
    client.action('LEAVE_ROOM');
    expect(socket.sent).toHaveLength(3);
  });
  it('uses the verified GM snapshot and includes the current session guard', () => {
    const socket = attach(
      snapshot({ isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_KICK_PLAYER'] }),
    );
    client.action('GM_KICK_PLAYER', 'guest');
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_KICK_PLAYER',
      targetPlayerId: 'guest',
      sessionId: null,
    });
    expect(socket.sent.at(-1)?.['isGm']).toBeUndefined();
  });
  it('starts the selected game only when the authoritative snapshot allows it', () => {
    const socket = attach(
      snapshot({
        isGm: true,
        currentPlayerId: 'gm',
        allowedActions: ['GM_START_GAME'],
      }),
    );
    client.action('GM_START_GAME');
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_START_GAME',
      sessionId: null,
    });
  });
  it('moves every client to Submit Name only after the authoritative game snapshot arrives', () => {
    const socket = attach();
    navigate.mockClear();
    socket.emit({
      type: 'STATE',
      room: snapshot({
        roomRevision: 2,
        lifecycle: 'IN_GAME',
        sessionId: '11111111-1111-1111-1111-111111111111',
        allowedActions: ['SUBMIT_NAME'],
        game: submitGame,
      }),
    });
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234']);
    expect(client.room()?.game?.phase).toBe('SUBMIT_NAME');
  });
  it('keeps the current player assignment out of the frontend PLAYING model', () => {
    const socket = attach();
    socket.emit({
      type: 'STATE',
      room: snapshot({
        roomRevision: 2,
        lifecycle: 'IN_GAME',
        sessionId: '11111111-1111-1111-1111-111111111111',
        game: {
          ...submitGame,
          phase: 'PLAYING',
          cards: [
            { playerId: 'guest', nickname: 'Ken', avatarId: 25, connectionStatus: 'CONNECTED', gameStatus: 'PLAYING', statusVersion: 0, assignedName: 'MUST-NOT-SURVIVE' },
            { playerId: 'gm', nickname: 'Host', avatarId: 1, connectionStatus: 'CONNECTED', gameStatus: 'PLAYING', statusVersion: 0, assignedName: 'VISIBLE-OTHER' },
          ],
        },
      }),
    });
    expect(client.room()?.game?.cards[0].assignedName).toBeNull();
    expect(client.room()?.game?.cards[1].assignedName).toBe('VISIBLE-OTHER');
    expect(JSON.stringify(client.room())).not.toContain('MUST-NOT-SURVIVE');
  });
  it('sends End Game once and follows authoritative Roast or Reveal snapshots', () => {
    const playing = { ...submitGame, phase: 'PLAYING' as const, cards: [] };
    const socket = attach(snapshot({
      lifecycle: 'IN_GAME', sessionId: '33333333-3333-3333-3333-333333333333',
      isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_END_GAME'], game: playing,
    }));
    client.endGame();
    client.endGame();
    expect(socket.sent.filter((message) => message['type'] === 'GM_END_GAME')).toHaveLength(1);
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_END_GAME', sessionId: '33333333-3333-3333-3333-333333333333',
    });
    socket.reply(true);
    socket.emit({ type: 'STATE', room: snapshot({
      roomRevision: 2, lifecycle: 'IN_GAME', sessionId: '33333333-3333-3333-3333-333333333333',
      isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_CONTINUE_REVEAL'],
      game: { ...playing, phase: 'ROAST', roast: { kind: 'LAST_ONE', playingPlayerIds: ['gm'] } },
    }) });
    expect(client.room()?.game?.phase).toBe('ROAST');
    expect(client.room()?.game?.roast?.playingPlayerIds).toEqual(['gm']);
    client.continueReveal();
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_CONTINUE_REVEAL', sessionId: '33333333-3333-3333-3333-333333333333',
    });
    socket.reply(true);
    socket.emit({ type: 'STATE', room: snapshot({
      roomRevision: 3, lifecycle: 'IN_GAME', sessionId: '33333333-3333-3333-3333-333333333333',
      isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_PLAY_AGAIN'],
      game: { ...playing, phase: 'REVEAL', reveal: [
        { playerId: 'guest', nickname: 'Ken', avatarId: 25, gameStatus: 'GOT_IT', assignedName: 'Own Reveal', createdBy: 'Host' },
        { playerId: 'gm', nickname: 'Host', avatarId: 1, gameStatus: 'PLAYING', assignedName: 'Other Reveal', createdBy: 'Ken' },
      ] },
    }) });
    expect(client.room()?.game?.phase).toBe('REVEAL');
    expect(client.room()?.game?.reveal.map((card) => card.assignedName)).toEqual(['Own Reveal', 'Other Reveal']);
    expect(client.room()?.game?.reveal.map((card) => card.createdBy)).toEqual(['Host', 'Ken']);
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234']);
  });
  it('restores Submit Name from authoritative state after refresh or reconnect', () => {
    attach();
    client.home();
    navigate.mockClear();
    client.resume('ABC234');
    const socket = sockets.at(-1)!;
    socket.open();
    socket.emit({
      type: 'STATE',
      room: snapshot({
        lifecycle: 'IN_GAME',
        sessionId: '22222222-2222-2222-2222-222222222222',
        allowedActions: ['SUBMIT_NAME'],
        game: submitGame,
      }),
    });
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234']);
    expect(client.room()?.game?.phase).toBe('SUBMIT_NAME');
  });
  it('keeps post-game choices distinct while preserving the current room', () => {
    let socket = attach(
      snapshot({
        lifecycle: 'IN_GAME',
        sessionId: '11111111-1111-1111-1111-111111111111',
        isGm: true,
        currentPlayerId: 'gm',
        allowedActions: ['GM_PLAY_AGAIN', 'GM_BACK_TO_ROOM'],
      }),
    );
    navigate.mockClear();

    client.playAgain();
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'GM_PLAY_AGAIN',
      sessionId: '11111111-1111-1111-1111-111111111111',
    });
    socket.reply(true);
    expect(navigate).not.toHaveBeenCalledWith(['/room', 'ABC234']);

    client.chooseAnotherGame();
    expect(socket.sent.at(-1)?.['type']).toBe('GM_BACK_TO_ROOM');
    socket.reply(true);
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234', 'games']);

    client.home();
    socket = attach(
      snapshot({
        lifecycle: 'IN_GAME',
        sessionId: '22222222-2222-2222-2222-222222222222',
        isGm: true,
        currentPlayerId: 'gm',
        allowedActions: ['GM_BACK_TO_ROOM'],
      }),
    );
    navigate.mockClear();
    client.backToRoom();
    expect(socket.sent.at(-1)?.['type']).toBe('GM_BACK_TO_ROOM');
    socket.reply(true);
    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234']);
  });
  it('starts Play Again once and replaces Reveal data only after the authoritative Submit snapshot', () => {
    const originalPlayers = snapshot().players;
    const socket = attach(snapshot({
      lifecycle: 'IN_GAME',
      sessionId: '11111111-1111-1111-1111-111111111111',
      isGm: true,
      currentPlayerId: 'gm',
      allowedActions: ['GM_PLAY_AGAIN'],
      game: {
        ...submitGame,
        phase: 'REVEAL',
        submittedCount: 2,
        currentPlayerSubmitted: true,
        reveal: [
          { playerId: 'gm', nickname: 'Host', avatarId: 1, gameStatus: 'GOT_IT', assignedName: 'OLD-SECRET-A', createdBy: 'Ken' },
          { playerId: 'guest', nickname: 'Ken', avatarId: 25, gameStatus: 'PLAYING', assignedName: 'OLD-SECRET-B', createdBy: 'Host' },
        ],
      },
    }));
    navigate.mockClear();

    client.playAgain();
    client.playAgain();
    expect(socket.sent.filter((message) => message['type'] === 'GM_PLAY_AGAIN')).toHaveLength(1);
    expect(client.room()?.game?.phase).toBe('REVEAL');
    expect(navigate).not.toHaveBeenCalled();

    socket.reply(true);
    socket.emit({ type: 'STATE', room: snapshot({
      roomRevision: 10,
      lifecycle: 'IN_GAME',
      sessionId: '22222222-2222-2222-2222-222222222222',
      isGm: true,
      currentPlayerId: 'gm',
      allowedActions: ['SUBMIT_NAME'],
      players: originalPlayers,
      game: submitGame,
    }) });

    expect(navigate).toHaveBeenLastCalledWith(['/room', 'ABC234']);
    expect(client.room()?.roomId).toBe('ABC234');
    expect(client.room()?.players).toEqual(originalPlayers);
    expect(client.room()?.sessionId).toBe('22222222-2222-2222-2222-222222222222');
    expect(client.room()?.game).toMatchObject({
      phase: 'SUBMIT_NAME', submittedCount: 0, currentPlayerSubmitted: false,
      cards: [], reveal: [], roast: null,
    });
    expect(JSON.stringify(client.room())).not.toContain('OLD-SECRET');
  });
  it('reconnects with the same token and resyncs without replaying a pending mutation', () => {
    const socket = attach();
    const token = socket.sent[0]['guestToken'];
    client.action('LEAVE_ROOM');
    socket.onclose?.();
    expect(client.online()).toBe(false);
    expect(client.room()?.players[1].avatarId).toBe(25);
    vi.advanceTimersByTime(1000);
    const next = sockets[1];
    next.open();
    expect(next.sent).toHaveLength(1);
    expect(next.sent[0]).toMatchObject({ type: 'RESUME_ROOM', guestToken: token });
    next.emit({ type: 'STATE', room: snapshot({ roomRevision: 5 }) });
    next.reply(true);
    expect(client.online()).toBe(true);
    expect(client.room()?.players.map((p) => p.avatarId)).toEqual([1, 25]);
    expect(client.newAvatar()).toBe(false);
  });
  it('refresh restores saved membership without sending a new nickname', () => {
    attach();
    client.home();
    navigate.mockClear();
    client.resume('ABC234');
    const socket = sockets.at(-1)!;
    socket.open();
    expect(socket.sent[0]['type']).toBe('RESUME_ROOM');
    expect(socket.sent[0]['nickname']).toBeUndefined();
    socket.emit({ type: 'STATE', room: snapshot() });
    socket.reply(true);
    expect(client.room()?.players.map((p) => p.avatarId)).toEqual([1, 25]);
    expect(navigate).not.toHaveBeenCalled();
  });
  it('kicked tokens are retained and never replaced to bypass the kick', () => {
    const socket = attach();
    const token = localStorage.getItem('drinkduel.guest');
    socket.emit({ type: 'ACCESS_REVOKED', reason: 'PLAYER_KICKED' });
    expect(client.room()).toBeNull();
    expect(client.online()).toBe(false);
    expect(client.notice()).toContain('removed');
    client.join('ABC234', 'Different name');
    const next = sockets.at(-1)!;
    next.open();
    next.reply(false, 'PLAYER_KICKED');
    expect(next.sent).toHaveLength(1);
    expect(localStorage.getItem('drinkduel.guest')).toBe(token);
  });
  it('clears membership after voluntary leave but retains the browser credential', () => {
    const socket = attach();
    socket.emit({ type: 'ACCESS_REVOKED', reason: 'LEFT' });
    expect(client.savedRoom()).toBeNull();
    expect(localStorage.getItem('drinkduel.guest')).toBeTruthy();
    expect(navigate).toHaveBeenLastCalledWith(['/']);
  });
  it('room closure returns Home while expiration retains its themed state', () => {
    let socket = attach();
    socket.emit({ type: 'ROOM_ENDED', reason: 'CLOSED' });
    expect(navigate).toHaveBeenLastCalledWith(['/']);
    socket = attach();
    socket.emit({ type: 'ROOM_ENDED', reason: 'EXPIRED' });
    expect(client.terminal()).toBe('EXPIRED');
    expect(client.notice()).toContain('Party’s over');
  });
  it('stops on replaced access without fighting the newer tab', () => {
    const socket = attach();
    socket.emit({ type: 'ACCESS_REVOKED', reason: 'REPLACED' });
    vi.advanceTimersByTime(60000);
    expect(sockets).toHaveLength(1);
    expect(client.notice()).toContain('another tab');
  });
  it('never retains game payloads or credentials in snapshot storage', () => {
    const socket = attach();
    socket.emit({
      type: 'STATE',
      room: { ...snapshot({ roomRevision: 2 }), game: { secret: 'HIDDEN' } },
    });
    expect(JSON.stringify(client.room())).not.toContain('HIDDEN');
    expect(JSON.stringify(localStorage)).not.toContain('HIDDEN');
    expect(localStorage.getItem('drinkduel.rooms')).toBe('{"ABC234":"guest"}');
  });
  it('create logs in via local session then uses real room transport and cookie-based resume', async () => {
    fetchMock
      .mockResolvedValueOnce({ ok: true })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ roomId: 'ABC234' }) });
    await client.create('Host');
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/host/session', '/api/rooms']);
    sockets[0].open();
    expect(sockets[0].sent[0]['guestToken']).toBeUndefined();
    expect(sockets[0].sent[0]['type']).toBe('RESUME_ROOM');
    expect(localStorage.getItem('drinkduel.guest')).toBeNull();
  });
  it('does not create when local session login is disabled', async () => {
    fetchMock.mockResolvedValue({ ok: false });
    await client.create('Host');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(client.notice()).toContain('unavailable');
    expect(sockets).toHaveLength(0);
  });
  it('unknown server errors never become raw user-facing text', () => {
    client.join('ABC234', 'Ken');
    sockets[0].open();
    sockets[0].reply(false, 'STACK_TRACE_SECRET');
    expect(client.notice()).not.toContain('STACK_TRACE_SECRET');
    expect(friendlyError('SQL password secret')).toContain('try again');
  });
});
