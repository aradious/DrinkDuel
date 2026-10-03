import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { BROWSER } from './browser';
import { Attachment, RoomView, friendlyError, roomCode } from './room.models';
import { normalizeLiarsDiceView } from './liars-dice-state';

@Injectable({ providedIn: 'root' })
export class RoomClient {
  private readonly browser = inject(BROWSER);
  private readonly router = inject(Router);
  readonly room = signal<RoomView | null>(null);
  readonly connection = signal<'idle' | 'connecting' | 'connected' | 'reconnecting' | 'stopped'>(
    'idle',
  );
  readonly notice = signal('');
  readonly terminal = signal('');
  readonly busy = signal(false);
  readonly newAvatar = signal(false);
  readonly online = computed(() => this.connection() === 'connected');
  private socket?: WebSocket;
  private attachment?: Attachment;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private timeout?: ReturnType<typeof setTimeout>;
  private avatarTimer?: ReturnType<typeof setTimeout>;
  private attempts = 0;
  private attaching = false;
  private navigateAfterAttach = true;
  private joining = false;
  private pendingId?: string;
  private pendingNavigation?: string[];
  private lobbyNavigation?: string[];
  private readonly identityKey = 'drinkduel.guest';
  private readonly roomsKey = 'drinkduel.rooms';

  can(action: string): boolean {
    return this.online() && !this.busy() && (this.room()?.allowedActions.includes(action) ?? false);
  }
  savedRoom(): string | null {
    try {
      return this.browser.storage.getItem('drinkduel.lastRoom');
    } catch {
      return null;
    }
  }
  private memberships(): Record<string, 'guest' | 'gm'> {
    try {
      return JSON.parse(this.browser.storage.getItem(this.roomsKey) ?? '{}');
    } catch {
      return {};
    }
  }
  private token(): string {
    let token = this.browser.storage.getItem(this.identityKey);
    if (!token) {
      token = this.browser.randomToken();
      this.browser.storage.setItem(this.identityKey, token);
    }
    return token;
  }
  private remember(attachment: Attachment): void {
    this.browser.storage.setItem(
      this.roomsKey,
      JSON.stringify({ ...this.memberships(), [attachment.roomId]: attachment.role }),
    );
    this.browser.storage.setItem('drinkduel.lastRoom', attachment.roomId);
  }
  private forgetRoom(): void {
    const id = this.attachment?.roomId;
    if (!id) return;
    try {
      const rooms = this.memberships();
      delete rooms[id];
      this.browser.storage.setItem(this.roomsKey, JSON.stringify(rooms));
      if (this.savedRoom() === id) this.browser.storage.removeItem('drinkduel.lastRoom');
    } catch {
      /* Credentials are never replaced to work around a denied membership. */
    }
  }
  async hostIdentityAvailable(): Promise<boolean> {
    try {
      return (
        await this.browser.request('/api/host/session', {
          cache: 'no-store',
          credentials: 'same-origin',
        })
      ).ok;
    } catch {
      return false;
    }
  }
  async create(nickname: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.notice.set('');
    try {
      const login = await this.browser.request('/api/host/session', {
        method: 'POST',
        credentials: 'same-origin',
      });
      if (!login.ok)
        throw new Error('Room creation is unavailable. Check your connection and try again.');
      const response = await this.browser.request('/api/rooms', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nickname }),
      });
      if (!response.ok) {
        const data = await response.json().catch(() => ({}));
        throw new Error(friendlyError(data.code ?? ''));
      }
      const data = await response.json();
      this.begin({ roomId: data.roomId, role: 'gm' });
    } catch (error) {
      this.notice.set(
        error instanceof Error && error.message.startsWith('Room creation')
          ? error.message
          : 'Couldn’t create the room. Check your connection and try again.',
      );
      this.busy.set(false);
    }
  }
  join(code: string, nickname: string): void {
    this.begin({
      roomId: roomCode(code),
      role: this.memberships()[roomCode(code)] ?? 'guest',
      nickname,
    });
  }
  resume(code: string): void {
    const id = roomCode(code);
    if (this.attachment?.roomId === id && this.online()) return;
    const role = this.memberships()[id];
    if (!role) {
      void this.router.navigate(['/join'], { queryParams: { room: id } });
      return;
    }
    this.begin({ roomId: id, role }, false);
  }
  retry(): void {
    if (this.attachment) this.begin({ ...this.attachment, nickname: undefined }, false);
  }
  private begin(attachment: Attachment, navigateAfterAttach = true): void {
    this.stopSocket();
    this.lobbyNavigation = undefined;
    this.room.set(null);
    this.terminal.set('');
    this.notice.set('');
    this.newAvatar.set(false);
    this.attachment = attachment;
    this.navigateAfterAttach = navigateAfterAttach;
    this.attempts = 0;
    this.joining = false;
    try {
      if (attachment.role === 'guest') this.token();
      this.remember(attachment);
    } catch {
      this.notice.set('Allow browser storage so we can keep your place in the room.');
      this.connection.set('stopped');
      this.busy.set(false);
      return;
    }
    this.open(false);
  }
  private open(reconnect: boolean): void {
    if (!this.attachment) return;
    this.connection.set(reconnect ? 'reconnecting' : 'connecting');
    this.busy.set(true);
    this.attaching = true;
    const socket = this.browser.socket(this.browser.origin.replace(/^http/, 'ws') + '/ws/rooms');
    this.socket = socket;
    this.armTimeout();
    socket.onopen = () => {
      if (socket !== this.socket) return;
      this.write(
        'RESUME_ROOM',
        this.attachment?.role === 'guest' ? { guestToken: this.token() } : {},
      );
    };
    socket.onmessage = (event) => {
      if (socket !== this.socket) return;
      try {
        this.receive(JSON.parse(event.data));
      } catch {
        this.connectionLost();
      }
    };
    socket.onclose = () => {
      if (socket === this.socket) this.connectionLost();
    };
    socket.onerror = () => {
      if (socket === this.socket) this.connectionLost();
    };
  }
  private receive(message: {
    type: string;
    room?: unknown;
    requestId?: string;
    accepted?: boolean;
    code?: string;
    reason?: string;
  }): void {
    if (
      message.type === 'STATE' &&
      message.room &&
      typeof message.room === 'object' &&
      'roomId' in message.room &&
      message.room.roomId === this.attachment?.roomId
    ) {
      const next = message.room as RoomView & { liarsDice?: unknown },
        previous = this.room();
      if (
        !this.attaching &&
        previous &&
        previous.gmPlayerId === next.gmPlayerId &&
        next.roomRevision < previous.roomRevision
      )
        return;
      // Store only the recipient-safe wire view. Submitted names are not present during collection.
      const {
        roomId,
        roomRevision,
        expiresAt,
        sessionId,
        lifecycle,
        currentPlayerId,
        gmPlayerId,
        isGm,
        capacity,
        joinable,
        allowedActions,
        players,
        game,
        liarsDice,
      } = next;
      const normalizedLiarsDice = normalizeLiarsDiceView(liarsDice, currentPlayerId);
      const validSessionId =
        typeof sessionId === 'string' &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
          sessionId,
        );
      const compatibleLiarsDice =
        lifecycle === 'IN_GAME' && validSessionId && !game && liarsDice && normalizedLiarsDice
          ? normalizedLiarsDice
          : null;
      const safeGame =
        !liarsDice &&
        game?.gameType === 'WHO_AM_I' &&
        ['SUBMIT_NAME', 'PLAYING', 'ROAST', 'REVEAL'].includes(game.phase)
          ? {
              gameType: game.gameType,
              phase: game.phase,
              participants: Array.isArray(game.participants)
                ? game.participants.map(({ playerId, submitted, resetSubmissionId }) => ({
                    playerId,
                    submitted: Boolean(submitted),
                    resetSubmissionId: resetSubmissionId ?? null,
                  }))
                : [],
              submittedCount: Number(game.submittedCount) || 0,
              participantCount: Number(game.participantCount) || 0,
              currentPlayerSubmitted: Boolean(game.currentPlayerSubmitted),
              readyForShuffle: Boolean(game.readyForShuffle),
              canShuffle: Boolean(game.canShuffle),
              cards: Array.isArray(game.cards)
                ? game.cards.map(
                    ({
                      playerId,
                      nickname,
                      avatarId,
                      connectionStatus,
                      gameStatus,
                      statusVersion,
                      assignedName,
                    }) => ({
                      playerId,
                      nickname,
                      avatarId,
                      connectionStatus,
                      gameStatus,
                      statusVersion,
                      assignedName: playerId === currentPlayerId ? null : (assignedName ?? null),
                    }),
                  )
                : [],
              roast:
                game.phase === 'ROAST' &&
                game.roast &&
                game.roast.kind === 'LAST_ONE' &&
                Array.isArray(game.roast.playingPlayerIds) &&
                game.roast.playingPlayerIds.length === 1
                  ? {
                      kind: 'LAST_ONE' as const,
                      playingPlayerIds: [String(game.roast.playingPlayerIds[0])],
                    }
                  : null,
              reveal:
                game.phase === 'REVEAL' && Array.isArray(game.reveal)
                  ? game.reveal.map(
                      ({ playerId, nickname, avatarId, gameStatus, assignedName, createdBy }) => ({
                        playerId: String(playerId),
                        nickname: String(nickname),
                        avatarId: Number(avatarId),
                        gameStatus,
                        assignedName: String(assignedName),
                        createdBy: String(createdBy),
                      }),
                    )
                  : [],
            }
          : null;
      this.room.set({
        roomId,
        roomRevision,
        expiresAt,
        sessionId,
        lifecycle,
        currentPlayerId,
        gmPlayerId,
        isGm,
        capacity,
        joinable,
        allowedActions,
        players,
        game: safeGame,
        liarsDice: compatibleLiarsDice,
      });
      let authoritativeRoute = compatibleLiarsDice
        ? ['/room', roomId, 'liars-dice', compatibleLiarsDice.phase.toLowerCase()]
        : lifecycle === 'IN_GAME' &&
            (safeGame?.phase === 'SUBMIT_NAME' || safeGame?.phase === 'PLAYING')
          ? ['/room', roomId]
          : null;
      if (!authoritativeRoute && lifecycle === 'LOBBY' && previous?.lifecycle === 'IN_GAME') {
        authoritativeRoute = this.lobbyNavigation ?? ['/room', roomId];
        this.lobbyNavigation = undefined;
        clearTimeout(this.timeout);
        this.busy.set(false);
      }
      if (authoritativeRoute) void this.router.navigate(authoritativeRoute);
      if (this.attaching) {
        this.attaching = false;
        this.attempts = 0;
        this.connection.set('connected');
        this.notice.set('');
        if (this.joining) {
          this.newAvatar.set(true);
          clearTimeout(this.avatarTimer);
          this.avatarTimer = setTimeout(() => this.newAvatar.set(false), 850);
        }
        this.joining = false;
        if (this.attachment) this.attachment.nickname = undefined;
        if (this.navigateAfterAttach && !authoritativeRoute)
          void this.router.navigate(['/room', roomId]);
      }
      return;
    }
    if (message.type === 'COMMAND_RESULT' && message.requestId === this.pendingId) {
      clearTimeout(this.timeout);
      this.busy.set(false);
      if (message.accepted) {
        const destination = this.pendingNavigation;
        this.pendingId = undefined;
        this.pendingNavigation = undefined;
        if (this.lobbyNavigation) {
          this.busy.set(true);
          this.armTimeout();
        }
        if (destination) void this.router.navigate(destination);
        return;
      }
      this.pendingId = undefined;
      this.pendingNavigation = undefined;
      this.lobbyNavigation = undefined;
      if (
        this.attaching &&
        message.code === 'NOT_AUTHORIZED' &&
        this.attachment?.role === 'guest' &&
        this.attachment.nickname !== undefined &&
        !this.joining
      ) {
        this.joining = true;
        this.busy.set(true);
        this.write('JOIN_ROOM', { guestToken: this.token(), nickname: this.attachment.nickname });
        this.armTimeout();
        return;
      }
      // An uncertain initial JOIN may have committed before the socket failed; resume instead of resending it.
      if (this.attaching && message.code === 'IDENTITY_ALREADY_JOINED' && this.joining) {
        this.write('RESUME_ROOM', { guestToken: this.token() });
        this.armTimeout();
        return;
      }
      this.notice.set(friendlyError(message.code ?? ''));
      if (this.attaching) {
        if (message.code === 'PLAYER_KICKED') this.terminal.set('PLAYER_KICKED');
        if (message.code === 'ROOM_EXPIRED') {
          this.terminal.set('EXPIRED');
          this.forgetRoom();
        }
        if (message.code === 'ROOM_NOT_FOUND') {
          this.terminal.set('UNAVAILABLE');
          this.forgetRoom();
        }
        this.stopSocket();
        this.connection.set('stopped');
      }
      return;
    }
    if (message.type === 'ACCESS_REVOKED' || message.type === 'ROOM_ENDED') {
      const reason = message.reason ?? '';
      this.stopSocket();
      this.room.set(null);
      this.busy.set(false);
      this.connection.set('stopped');
      this.terminal.set(reason);
      if (['CLOSED', 'EXPIRED', 'LEFT'].includes(reason)) this.forgetRoom();
      this.notice.set(
        reason === 'CLOSED'
          ? 'The Game Master closed the room. Until next time! 🍻'
          : reason === 'LEFT'
            ? ''
            : friendlyError(reason === 'EXPIRED' ? 'ROOM_EXPIRED' : reason),
      );
      if (reason === 'CLOSED' || reason === 'LEFT') void this.router.navigate(['/']);
    }
  }
  private write(type: string, fields: Record<string, unknown> = {}): void {
    this.pendingId = this.browser.requestId();
    this.socket?.send(
      JSON.stringify({
        type,
        requestId: this.pendingId,
        roomId: this.attachment?.roomId,
        ...fields,
      }),
    );
  }
  action(
    type:
      | 'GM_KICK_PLAYER'
      | 'GM_START_GAME'
      | 'GM_END_GAME'
      | 'GM_PLAY_AGAIN'
      | 'GM_BACK_TO_ROOM'
      | 'GM_CLOSE_ROOM'
      | 'LEAVE_ROOM',
    targetPlayerId?: string,
    navigateOnSuccess?: string[],
  ): void {
    if (!this.can(type)) return;
    this.busy.set(true);
    this.notice.set('');
    this.write(type, {
      ...(targetPlayerId ? { targetPlayerId } : {}),
      ...(type.startsWith('GM_') ? { sessionId: this.room()?.sessionId ?? null } : {}),
    });
    this.pendingNavigation = navigateOnSuccess;
    this.armTimeout();
  }
  submitName(secretName: string): void {
    if (!this.can('SUBMIT_NAME')) return;
    this.busy.set(true);
    this.notice.set('');
    this.write('SUBMIT_NAME', {
      sessionId: this.room()?.sessionId ?? null,
      secretName,
    });
    this.armTimeout();
  }
  resetSubmission(targetPlayerId: string, expectedSubmissionId: string): void {
    if (!this.can('GM_RESET_SUBMISSION')) return;
    this.busy.set(true);
    this.notice.set('');
    this.write('GM_RESET_SUBMISSION', {
      sessionId: this.room()?.sessionId ?? null,
      targetPlayerId,
      expectedSubmissionId,
    });
    this.armTimeout();
  }
  shuffle(): void {
    if (!this.can('GM_SHUFFLE')) return;
    this.busy.set(true);
    this.notice.set('');
    this.write('GM_SHUFFLE', { sessionId: this.room()?.sessionId ?? null });
    this.armTimeout();
  }
  markGotIt(targetPlayerId: string): void {
    if (!this.can('GM_MARK_GOT_IT')) return;
    this.gameCommand('GM_MARK_GOT_IT', { targetPlayerId });
  }
  resetPlayerStatus(targetPlayerId: string, expectedStatusVersion: number): void {
    if (!this.can('GM_RESET_PLAYER_STATUS')) return;
    this.gameCommand('GM_RESET_PLAYER_STATUS', { targetPlayerId, expectedStatusVersion });
  }
  endGame(): void {
    if (!this.can('GM_END_GAME')) return;
    this.gameCommand('GM_END_GAME');
  }
  continueReveal(): void {
    if (!this.can('GM_CONTINUE_REVEAL')) return;
    this.gameCommand('GM_CONTINUE_REVEAL');
  }
  selectLiarsDice(): void {
    if (!this.can('GM_SELECT_LIARS_DICE')) return;
    this.busy.set(true);
    this.notice.set('');
    this.write('GM_SELECT_LIARS_DICE');
    this.armTimeout();
  }
  startLiarsDice(): void {
    if (!this.can('GM_START_LIARS_DICE')) return;
    this.gameCommand('GM_START_LIARS_DICE');
  }
  endLiarsDice(): void {
    if (!this.can('GM_END_LIARS_DICE')) return;
    this.gameCommand('GM_END_LIARS_DICE');
  }
  restartLiarsDice(): void {
    if (!this.can('GM_RESTART_LIARS_DICE')) return;
    this.gameCommand('GM_RESTART_LIARS_DICE');
  }
  private gameCommand(type: string, fields: Record<string, unknown> = {}): void {
    this.busy.set(true);
    this.notice.set('');
    this.write(type, { sessionId: this.room()?.sessionId ?? null, ...fields });
    this.armTimeout();
  }
  playAgain(): void {
    this.action('GM_PLAY_AGAIN');
  }
  chooseAnotherGame(): void {
    const roomId = this.room()?.roomId;
    if (!roomId || !this.can('GM_BACK_TO_ROOM')) return;
    this.lobbyNavigation = ['/room', roomId, 'games'];
    this.action('GM_BACK_TO_ROOM');
  }
  backToRoom(): void {
    const roomId = this.room()?.roomId;
    if (!roomId || !this.can('GM_BACK_TO_ROOM')) return;
    this.lobbyNavigation = ['/room', roomId];
    this.action('GM_BACK_TO_ROOM');
  }
  private armTimeout(): void {
    clearTimeout(this.timeout);
    this.timeout = setTimeout(() => this.connectionLost(), 12000);
  }
  private connectionLost(): void {
    this.stopSocket();
    this.busy.set(false);
    this.notice.set('Reconnecting to your crew…');
    this.connection.set('reconnecting');
    if (++this.attempts > 5) {
      this.connection.set('stopped');
      this.notice.set('We couldn’t reach your crew. Check your connection, then reconnect.');
      return;
    }
    this.retryTimer = setTimeout(
      () => this.open(true),
      Math.min(1000 * 2 ** (this.attempts - 1), 8000),
    );
  }
  private stopSocket(): void {
    clearTimeout(this.retryTimer);
    clearTimeout(this.timeout);
    this.pendingId = undefined;
    this.pendingNavigation = undefined;
    const socket = this.socket;
    this.socket = undefined;
    socket?.close();
  }
  home(): void {
    this.stopSocket();
    this.room.set(null);
    this.connection.set('idle');
    this.lobbyNavigation = undefined;
    this.busy.set(false);
    this.notice.set('');
    void this.router.navigate(['/']);
  }
}
