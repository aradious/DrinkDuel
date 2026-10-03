import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { LiarsDiceStart } from './liars-dice-start';

describe("Liar's Dice START screen", () => {
  const base: RoomView = {
    roomId: 'ABC234',
    roomRevision: 7,
    expiresAt: '',
    sessionId: 'session-1',
    lifecycle: 'IN_GAME',
    currentPlayerId: 'gm',
    gmPlayerId: 'gm',
    isGm: true,
    capacity: 20,
    joinable: false,
    allowedActions: ['GM_START_LIARS_DICE', 'GM_KICK_PLAYER'],
    players: [
      { playerId: 'gm', nickname: 'Aood', avatarId: 4, connectionStatus: 'CONNECTED' },
      { playerId: 'guest', nickname: 'Earth', avatarId: 30, connectionStatus: 'DISCONNECTED' },
    ],
    game: null,
    liarsDice: {
      gameType: 'LIARS_DICE',
      phase: 'START',
      participantIds: [],
      ownDice: null,
      revealedHands: [],
      revealCounts: [],
    },
  };
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    notice: ReturnType<typeof signal<string>>;
    busy: ReturnType<typeof signal<boolean>>;
    can: (action: string) => boolean;
    resume: ReturnType<typeof vi.fn>;
    startLiarsDice: ReturnType<typeof vi.fn>;
    action: ReturnType<typeof vi.fn>;
    chooseAnotherGame: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal<RoomView | null>(base),
      notice: signal(''),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      startLiarsDice: vi.fn(),
      action: vi.fn(),
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

  it('restores the room and renders the authoritative roster without dice state', () => {
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(root.textContent).toContain('ABC234');
    expect(root.textContent).toContain('Aood');
    expect(root.textContent).toContain('Earth');
    expect(root.textContent).toContain('Game Master');
    expect(root.textContent).toContain('Connected');
    expect(root.textContent).toContain('Disconnected');
    expect(root.querySelectorAll('dd-avatar')).toHaveLength(2);
    expect(root.textContent).not.toMatch(/ownDice|revealedHands|revealCounts/i);
  });

  it('lets the GM start once through the authoritative command', () => {
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const start = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Start Game'),
    )!;
    start.click();
    expect(client.startLiarsDice).toHaveBeenCalledOnce();
    client.busy.set(true);
    fixture.detectChanges();
    expect((start as HTMLButtonElement).disabled).toBe(true);
    start.click();
    expect(client.startLiarsDice).toHaveBeenCalledOnce();
  });

  it('shows the waiting state and no controls to a normal player', () => {
    client.room.set({
      ...base,
      currentPlayerId: 'guest',
      isGm: false,
      allowedActions: [],
    });
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Waiting for the Game Master to start');
    expect(root.textContent).not.toContain('Start Game');
    expect(root.querySelector('.remove-player')).toBeNull();
  });

  it('allows the GM to remove a non-GM player only through the existing command', () => {
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('button[aria-label="Remove Aood"]')).toBeNull();
    (root.querySelector('button[aria-label="Remove Earth"]') as HTMLButtonElement).click();
    expect(client.action).toHaveBeenCalledWith('GM_KICK_PLAYER', 'guest');
  });

  it('offers Choose Another Game only to the GM when advertised and prevents repeat activation', () => {
    client.room.update((room) => ({
      ...room!,
      allowedActions: [...room!.allowedActions, 'GM_BACK_TO_ROOM'],
    }));
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const chooseAnother = (fixture.nativeElement as HTMLElement).querySelector(
      'button.choose-another',
    ) as HTMLButtonElement;
    expect(chooseAnother.textContent).toContain('Choose Another Game');
    chooseAnother.click();
    fixture.detectChanges();
    expect(client.chooseAnotherGame).toHaveBeenCalledOnce();
    expect(chooseAnother.disabled).toBe(true);
    chooseAnother.click();
    expect(client.chooseAnotherGame).toHaveBeenCalledOnce();
    const start = (fixture.nativeElement as HTMLElement).querySelector(
      'button.start-game',
    ) as HTMLButtonElement;
    expect(start.disabled).toBe(true);

    client.busy.set(false);
    client.room.update((room) => ({
      ...room!,
      currentPlayerId: 'guest',
      isGm: false,
      allowedActions: ['GM_BACK_TO_ROOM'],
    }));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.choose-another')).toBeNull();
  });

  it('reacts to authoritative membership and connection updates without local roster state', () => {
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    client.room.update((room) => ({
      ...room!,
      players: [{ ...room!.players[0], connectionStatus: 'DISCONNECTED' }],
      roomRevision: 8,
    }));
    fixture.detectChanges();
    expect(root.querySelectorAll('.player-card')).toHaveLength(1);
    expect(root.textContent).not.toContain('Earth');
    expect(root.textContent).toContain('Disconnected');
  });

  it('disables Start Game when the server does not advertise the action', () => {
    client.room.update((room) => ({ ...room!, allowedActions: ['GM_KICK_PLAYER'] }));
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const start = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Start Game'),
    ) as HTMLButtonElement;
    expect(start.disabled).toBe(true);
    expect(root.textContent).toContain('Waiting for at least 2 connected players');
  });

  it('renders the full supported 20-player room roster', () => {
    client.room.update((room) => ({
      ...room!,
      players: Array.from({ length: 20 }, (_, index) => ({
        playerId: index === 0 ? 'gm' : `player-${index}`,
        nickname: `Player ${index + 1}`,
        avatarId: (index % 30) + 1,
        connectionStatus: index % 2 === 0 ? ('CONNECTED' as const) : ('DISCONNECTED' as const),
      })),
    }));
    const fixture = TestBed.createComponent(LiarsDiceStart);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.player-card')).toHaveLength(
      20,
    );
  });
});
