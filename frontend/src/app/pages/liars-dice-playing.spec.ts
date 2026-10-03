import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { LiarsDicePlaying } from './liars-dice-playing';

describe("Liar's Dice PLAYING screen", () => {
  const participantRoom: RoomView = {
    roomId: 'ABC234',
    roomRevision: 3,
    expiresAt: '',
    sessionId: '33333333-3333-4333-8333-333333333333',
    lifecycle: 'IN_GAME',
    currentPlayerId: '22222222-2222-4222-8222-222222222222',
    gmPlayerId: '11111111-1111-4111-8111-111111111111',
    isGm: false,
    capacity: 20,
    joinable: false,
    allowedActions: [],
    players: [
      {
        playerId: '11111111-1111-4111-8111-111111111111',
        nickname: 'Aood',
        avatarId: 4,
        connectionStatus: 'CONNECTED',
      },
      {
        playerId: '22222222-2222-4222-8222-222222222222',
        nickname: 'Earth',
        avatarId: 8,
        connectionStatus: 'CONNECTED',
      },
    ],
    game: null,
    liarsDice: {
      gameType: 'LIARS_DICE',
      phase: 'PLAYING',
      participantIds: [
        '11111111-1111-4111-8111-111111111111',
        '22222222-2222-4222-8222-222222222222',
      ],
      ownDice: [1, 2, 3, 4, 6],
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
    endLiarsDice: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal(participantRoom),
      notice: signal(''),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      resume: vi.fn(),
      endLiarsDice: vi.fn(() => client.busy.set(true)),
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
    const fixture = TestBed.createComponent(LiarsDicePlaying);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  function pointer(type: string): Event {
    const event = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperties(event, { button: { value: 0 }, pointerId: { value: 1 } });
    return event;
  }

  function openWithPointer(root: HTMLElement): HTMLButtonElement {
    const cup = root.querySelector('.cup-control') as HTMLButtonElement;
    cup.dispatchEvent(pointer('pointerdown'));
    TestBed.flushEffects();
    return cup;
  }

  it('restores the authoritative room with five dice covered by default', () => {
    const { root } = create();
    expect(client.resume).toHaveBeenCalledWith('ABC234');
    expect(root.textContent).toContain('ABC234');
    expect(root.textContent).toContain('2 players are at this table');
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    expect(root.textContent).not.toMatch(/Die showing [1-6]/);
    expect(root.querySelector('[aria-label*="Die showing"]')).toBeNull();
  });

  it('renders exactly the five authoritative values while held and no other hand', () => {
    const { fixture, root } = create();
    openWithPointer(root);
    fixture.detectChanges();
    expect(
      [...root.querySelectorAll('dd-die-face')].map((die) => die.getAttribute('data-face')),
    ).toEqual(['1', '2', '3', '4', '6']);
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    expect(
      [...root.querySelectorAll<HTMLImageElement>('dd-die-face img')].map((image) =>
        image.getAttribute('src'),
      ),
    ).toEqual(
      [1, 2, 3, 4, 6].map(
        (face) => `/assets/drinkduel/games/liars-dice/dice/dice-${face}.png`,
      ),
    );
  });

  it('uses five deterministic scatter slots without runtime randomness', () => {
    const { fixture, root } = create();
    const random = vi.spyOn(Math, 'random');
    openWithPointer(root);
    fixture.detectChanges();
    const slots = [...root.querySelectorAll('.die-slot')];
    expect(slots).toHaveLength(5);
    expect(slots.map((slot) => slot.className)).toEqual([
      'die-slot slot-1',
      'die-slot slot-2',
      'die-slot slot-3',
      'die-slot slot-4',
      'die-slot slot-5',
    ]);
    expect(random).not.toHaveBeenCalled();
    random.mockRestore();
  });

  it('maps authoritative face values to the approved assets', () => {
    client.room.update((room) => ({
      ...room!,
      liarsDice: { ...room!.liarsDice!, phase: 'PLAYING', ownDice: [1, 2, 3, 4, 5] } as never,
    }));
    const { fixture, root } = create();
    openWithPointer(root);
    fixture.detectChanges();
    expect(
      [...root.querySelectorAll<HTMLImageElement>('img.die-art')].map((image) =>
        image.getAttribute('src'),
      ),
    ).toEqual(
      [1, 2, 3, 4, 5].map(
        (face) => `/assets/drinkduel/games/liars-dice/dice/dice-${face}.png`,
      ),
    );
  });

  it('opens for pointer hold and fails closed on release and cancel', () => {
    const { fixture, root } = create();
    const cup = openWithPointer(root);
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    cup.dispatchEvent(pointer('pointerup'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    cup.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    cup.dispatchEvent(pointer('pointercancel'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
  });

  it('fails closed on pointer leave, control blur and window blur', () => {
    const { fixture, root } = create();
    const cup = openWithPointer(root);
    fixture.detectChanges();
    cup.dispatchEvent(pointer('pointerleave'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    cup.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    cup.dispatchEvent(new FocusEvent('blur'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    cup.dispatchEvent(pointer('pointerdown'));
    fixture.detectChanges();
    window.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
  });

  it('fails closed when the document becomes hidden', () => {
    const { fixture, root } = create();
    openWithPointer(root);
    fixture.detectChanges();
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  });

  it.each([' ', 'Enter'])('opens on %s keydown and closes on keyup', (key) => {
    const { fixture, root } = create();
    const cup = root.querySelector('.cup-control') as HTMLButtonElement;
    cup.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    cup.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
  });

  it('does not toggle closed on repeated keydown and sends no game command to peek', () => {
    const { fixture, root } = create();
    const cup = root.querySelector('.cup-control') as HTMLButtonElement;
    cup.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    fixture.detectChanges();
    cup.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', repeat: true, bubbles: true }));
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    expect(client.endLiarsDice).not.toHaveBeenCalled();
  });

  it('restores refresh/reconnect directly in the same covered state', () => {
    const { root } = create();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
    expect(client.room()?.liarsDice?.phase).toBe('PLAYING');
  });

  it('renders a deliberate excluded-member state with no hand or peek control', () => {
    client.room.update((room) => ({
      ...room!,
      currentPlayerId: 'excluded',
      liarsDice: { ...room!.liarsDice!, phase: 'PLAYING', ownDice: null } as never,
    }));
    const { root } = create();
    expect(root.textContent).toContain("You weren't in this round");
    expect(root.textContent).toContain('Wait for the Game Master to finish the round');
    expect(root.querySelector('.cup-control')).toBeNull();
    expect(root.querySelector('dd-die-face')).toBeNull();
  });

  it('never exposes End Game to a normal player', () => {
    const { root } = create();
    expect(root.querySelector('.end-game')).toBeNull();
  });

  it('shows End Game to the GM only when advertised', () => {
    client.room.update((room) => ({ ...room!, isGm: true, allowedActions: [] }));
    let view = create();
    expect(view.root.querySelector('.end-game')).toBeNull();
    view.fixture.destroy();
    client.room.update((room) => ({ ...room!, allowedActions: ['GM_END_LIARS_DICE'] }));
    view = create();
    expect(view.root.querySelector('.end-game')).not.toBeNull();
  });

  it('sends nothing when End Game confirmation is cancelled', () => {
    client.room.update((room) => ({ ...room!, isGm: true, allowedActions: ['GM_END_LIARS_DICE'] }));
    const { fixture, root } = create();
    const dialog = root.querySelector('dialog')!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    (root.querySelector('.end-game') as HTMLButtonElement).click();
    expect(dialog.showModal).toHaveBeenCalledOnce();
    (root.querySelector('.cancel-end') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(client.endLiarsDice).not.toHaveBeenCalled();
  });

  it('sends exactly one authoritative End Game command and does not route locally', () => {
    client.room.update((room) => ({ ...room!, isGm: true, allowedActions: ['GM_END_LIARS_DICE'] }));
    const { fixture, root } = create();
    const dialog = root.querySelector('dialog')!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    (root.querySelector('.confirm-end') as HTMLButtonElement).click();
    fixture.detectChanges();
    (root.querySelector('.confirm-end') as HTMLButtonElement).click();
    expect(client.endLiarsDice).toHaveBeenCalledOnce();
    expect(client.room()?.liarsDice?.phase).toBe('PLAYING');
  });

  it('removes all private presentation when the component is destroyed', () => {
    const { fixture, root } = create();
    openWithPointer(root);
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    fixture.destroy();
    expect(fixture.componentInstance.peeking()).toBe(false);
  });

  it('fails closed immediately when authoritative PLAYING state disappears', () => {
    const { fixture, root } = create();
    openWithPointer(root);
    fixture.detectChanges();
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(5);
    client.room.set(null);
    fixture.detectChanges();
    TestBed.flushEffects();
    expect(fixture.componentInstance.peeking()).toBe(false);
    expect(root.querySelectorAll('dd-die-face')).toHaveLength(0);
  });

  it('ships reduced-motion overrides for dice settle and cup movement', () => {
    create();
    const componentStyles = [...document.querySelectorAll('style')]
      .map((style) => style.textContent ?? '')
      .join('\n');
    expect(componentStyles).toContain('prefers-reduced-motion: reduce');
    expect(componentStyles).toContain('animation: none');
    expect(componentStyles).toContain('transition: none');
  });
});
