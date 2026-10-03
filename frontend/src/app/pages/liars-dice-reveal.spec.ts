import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { DiceHand, LiarsDiceFaceCountView, RoomView } from '../core/room.models';
import { LiarsDiceReveal } from './liars-dice-reveal';

describe("Liar's Dice REVEAL screen", () => {
  const gmId = '11111111-1111-4111-8111-111111111111';
  const playerId = '22222222-2222-4222-8222-222222222222';
  const counts: LiarsDiceFaceCountView[] = [
    { face: 1, rawCount: 2, effectiveCount: 2 },
    { face: 2, rawCount: 2, effectiveCount: 4 },
    { face: 3, rawCount: 2, effectiveCount: 4 },
    { face: 4, rawCount: 1, effectiveCount: 3 },
    { face: 5, rawCount: 1, effectiveCount: 3 },
    { face: 6, rawCount: 2, effectiveCount: 4 },
  ];
  const base: RoomView = {
    roomId: 'ABC234',
    roomRevision: 4,
    expiresAt: '',
    sessionId: '33333333-3333-4333-8333-333333333333',
    lifecycle: 'IN_GAME',
    currentPlayerId: playerId,
    gmPlayerId: gmId,
    isGm: false,
    capacity: 20,
    joinable: false,
    allowedActions: [],
    players: [
      { playerId: gmId, nickname: 'Aood', avatarId: 4, connectionStatus: 'CONNECTED' },
      { playerId, nickname: 'Earth', avatarId: 8, connectionStatus: 'CONNECTED' },
    ],
    game: null,
    liarsDice: {
      gameType: 'LIARS_DICE',
      phase: 'REVEAL',
      participantIds: [gmId, playerId],
      ownDice: null,
      revealedHands: [
        { playerId: gmId, dice: [1, 1, 2, 3, 4] },
        { playerId, dice: [2, 3, 5, 6, 6] },
      ],
      revealCounts: counts,
    },
  };
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    notice: ReturnType<typeof signal<string>>;
    busy: ReturnType<typeof signal<boolean>>;
    can: (action: string) => boolean;
    resume: ReturnType<typeof vi.fn>;
    restartLiarsDice: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal(base),
      notice: signal(''),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      restartLiarsDice: vi.fn(() => client.busy.set(true)),
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
    const fixture = TestBed.createComponent(LiarsDiceReveal);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  it('restores and renders every locked participant in authoritative order', () => {
    const { root } = create();
    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(
      [...root.querySelectorAll('.hand-card h3')].map((item) => item.textContent?.trim()),
    ).toEqual(['Aood', 'Earth']);
    expect(root.querySelectorAll('.hand-card')).toHaveLength(2);
    expect(root.querySelectorAll('.hand-card dd-avatar')).toHaveLength(2);
  });

  it('places Table Counts, round action, then locked player hands', () => {
    const { root } = create();
    const sections = [...root.querySelectorAll('.counts-panel, .reveal-actions, .hands-panel')];
    expect(sections.map((section) => section.className)).toEqual([
      'counts-panel',
      'reveal-actions',
      'hands-panel',
    ]);
    expect(sections[1].textContent).toContain('Waiting for the Game Master');
  });

  it('renders exactly each participant authoritative five-die hand', () => {
    const { root } = create();
    const hands = [...root.querySelectorAll('.hand-card')];
    expect(
      hands.map((card) =>
        [...card.querySelectorAll('dd-die-face')].map((die) => die.getAttribute('data-face')),
      ),
    ).toEqual([
      ['1', '1', '2', '3', '4'],
      ['2', '3', '5', '6', '6'],
    ]);
    expect(hands.every((card) => card.querySelectorAll('dd-die-face').length === 5)).toBe(true);
  });

  it('marks the current player and Game Master on their correct cards', () => {
    const { root } = create();
    const cards = [...root.querySelectorAll('.hand-card')];
    expect(cards[0].textContent).toContain('Game Master');
    expect(cards[0].textContent).not.toContain('YOU');
    expect(cards[1].textContent).toContain('YOU');
    expect(cards[1].textContent).not.toContain('Game Master');
  });

  it('does not insert an excluded Room member but lets them view the public reveal', () => {
    const excludedId = '99999999-9999-4999-8999-999999999999';
    client.room.update((room) => ({
      ...room!,
      currentPlayerId: excludedId,
      players: [
        ...room!.players,
        {
          playerId: excludedId,
          nickname: 'Late Guest',
          avatarId: 12,
          connectionStatus: 'CONNECTED',
        },
      ],
    }));
    const { root } = create();
    expect(root.querySelectorAll('.hand-card')).toHaveLength(2);
    expect(root.textContent).not.toContain('Late Guest');
    expect(root.querySelectorAll('.count-card')).toHaveLength(6);
    expect(root.querySelectorAll('.hand-card dd-die-face')).toHaveLength(10);
  });

  it('shows all six authoritative raw and Wild-1 effective counts', () => {
    const { root } = create();
    const cards = [...root.querySelectorAll('.count-card')];
    expect(cards).toHaveLength(6);
    expect(cards.map((card) => card.getAttribute('data-count-face'))).toEqual([
      '1',
      '2',
      '3',
      '4',
      '5',
      '6',
    ]);
    expect(
      cards.map((card) =>
        [...card.querySelectorAll('.count-values strong')].map((value) =>
          value.textContent?.trim(),
        ),
      ),
    ).toEqual([
      ['2', '2'],
      ['2', '4'],
      ['2', '4'],
      ['1', '3'],
      ['1', '3'],
      ['2', '4'],
    ]);
    expect(cards[0].textContent).toContain('no double counting');
    expect(
      cards.slice(1).every((card) => card.textContent?.includes('Includes every wild 1')),
    ).toBe(true);
  });

  it('reuses the approved dice assets with accessible face values', () => {
    const { root } = create();
    const one = root.querySelector('.count-card[data-count-face="1"] dd-die-face')!;
    const four = root.querySelector('.count-card[data-count-face="4"] dd-die-face')!;
    const six = root.querySelector('.count-card[data-count-face="6"] dd-die-face')!;
    expect(one.querySelector('img')?.getAttribute('src')).toContain('/dice/dice-1.png');
    expect(one.querySelector('img')?.getAttribute('aria-label')).toBe('Die showing 1');
    expect(four.querySelector('img')?.getAttribute('src')).toContain('/dice/dice-4.png');
    expect(four.querySelector('img')?.getAttribute('aria-label')).toBe('Die showing 4');
    expect(six.querySelector('img')?.getAttribute('src')).toContain('/dice/dice-6.png');
    expect(six.querySelector('img')?.getAttribute('aria-label')).toBe('Die showing 6');
  });

  it('contains no result invention UI', () => {
    const { root } = create();
    expect(root.textContent).not.toMatch(/winner|loser|score|ranking|points|bid history/i);
  });

  it('shows waiting copy and no Restart control to a normal Player', () => {
    const { root } = create();
    expect(root.textContent).toContain('Waiting for the Game Master to start another round');
    expect(root.querySelector('.play-again')).toBeNull();
  });

  it('shows Restart to the GM only when the server advertises it', () => {
    client.room.update((room) => ({
      ...room!,
      currentPlayerId: gmId,
      isGm: true,
      allowedActions: [],
    }));
    let view = create();
    expect(view.root.querySelector('.play-again')).toBeNull();
    view.fixture.destroy();
    client.room.update((room) => ({ ...room!, allowedActions: ['GM_RESTART_LIARS_DICE'] }));
    view = create();
    expect(view.root.querySelector('.play-again')).not.toBeNull();
  });

  it('sends exactly one Restart command and waits for authoritative START', () => {
    client.room.update((room) => ({
      ...room!,
      currentPlayerId: gmId,
      isGm: true,
      allowedActions: ['GM_RESTART_LIARS_DICE'],
    }));
    const { fixture, root } = create();
    const restart = root.querySelector('.play-again') as HTMLButtonElement;
    restart.click();
    fixture.detectChanges();
    restart.click();
    expect(client.restartLiarsDice).toHaveBeenCalledOnce();
    expect(client.room()?.liarsDice?.phase).toBe('REVEAL');
    expect(restart.disabled).toBe(true);
  });

  it('removes session A reveal content when authoritative session B START arrives', () => {
    const { fixture, root } = create();
    expect(root.querySelectorAll('.hand-card')).toHaveLength(2);
    client.room.set({
      ...base,
      roomRevision: 5,
      sessionId: '44444444-4444-4444-8444-444444444444',
      liarsDice: {
        gameType: 'LIARS_DICE',
        phase: 'START',
        participantIds: [],
        ownDice: null,
        revealedHands: [],
        revealCounts: [],
      },
    });
    fixture.detectChanges();
    expect(root.querySelector('.reveal-shell')).toBeNull();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    expect(root.textContent).not.toContain('Hands on the table');
  });

  it('restores directly into the same authoritative public REVEAL state', () => {
    const { root } = create();
    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(root.querySelectorAll('.hand-card dd-die-face')).toHaveLength(10);
    expect(root.querySelectorAll('.count-card')).toHaveLength(6);
  });

  it.each([10, 20])(
    'renders a %i-player locked roster without altering order or hand size',
    (playerCount) => {
      const ids = Array.from(
        { length: playerCount },
        (_, index) =>
          `${(index + 1).toString(16).padStart(8, '0')}-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}`,
      );
      const hand: DiceHand = [1, 1, 2, 3, 4];
      const scaleCounts: LiarsDiceFaceCountView[] = [
        { face: 1, rawCount: playerCount * 2, effectiveCount: playerCount * 2 },
        { face: 2, rawCount: playerCount, effectiveCount: playerCount * 3 },
        { face: 3, rawCount: playerCount, effectiveCount: playerCount * 3 },
        { face: 4, rawCount: playerCount, effectiveCount: playerCount * 3 },
        { face: 5, rawCount: 0, effectiveCount: playerCount * 2 },
        { face: 6, rawCount: 0, effectiveCount: playerCount * 2 },
      ];
      client.room.set({
        ...base,
        currentPlayerId: ids[5],
        gmPlayerId: ids[0],
        players: ids.map((id, index) => ({
          playerId: id,
          nickname: `Player ${index + 1}`,
          avatarId: (index % 30) + 1,
          connectionStatus: index % 3 ? ('CONNECTED' as const) : ('DISCONNECTED' as const),
        })),
        liarsDice: {
          gameType: 'LIARS_DICE',
          phase: 'REVEAL',
          participantIds: ids,
          ownDice: null,
          revealedHands: ids.map((id) => ({ playerId: id, dice: hand })),
          revealCounts: scaleCounts,
        },
      });
      const { root } = create();
      expect(root.querySelectorAll('.hand-card')).toHaveLength(playerCount);
      expect(root.querySelectorAll('.hand-card dd-die-face')).toHaveLength(playerCount * 5);
      expect(
        [...root.querySelectorAll('.hand-card h3')].map((item) => item.textContent?.trim()),
      ).toEqual(Array.from({ length: playerCount }, (_, index) => `Player ${index + 1}`));
    },
  );

  it('ships a reduced-motion settled-state override for reveal presentation', () => {
    create();
    const componentStyles = [...document.querySelectorAll('style')]
      .map((style) => style.textContent ?? '')
      .join('\n');
    expect(componentStyles).toContain('prefers-reduced-motion: reduce');
    expect(componentStyles).toContain('animation: none');
  });
});
