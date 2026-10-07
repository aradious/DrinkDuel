import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import QRCode from 'qrcode';
import { BROWSER, BrowserPort } from '../core/browser';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { Home } from './home';
import { Join } from './join';
import { Lobby } from './lobby';
import { Create } from './create';
import { ChooseGame } from './choose-game';
vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,') },
}));
describe('Step 8A screens', () => {
  it.each([true, false])(
    'renders identical assigned artwork for GM and Player views (GM=%s)',
    async (isGm) => {
      client.room.set({
        ...view,
        isGm,
        currentPlayerId: isGm ? 'gm' : 'guest',
        players: [
          { ...view.players[0], avatarId: 4 },
          { ...view.players[1], avatarId: 30 },
        ],
      });
      const fixture = TestBed.createComponent(Lobby);
      fixture.detectChanges();
      await fixture.whenStable();
      const sources = () =>
        [...(fixture.nativeElement as HTMLElement).querySelectorAll('dd-avatar img')].map((image) =>
          image.getAttribute('src'),
        );
      expect(sources()).toEqual([
        'assets/drinkduel/avatars/avatar-04.png',
        'assets/drinkduel/avatars/avatar-30.webp',
      ]);
      client.room.update((room) => ({ ...room!, roomRevision: 2 }));
      fixture.detectChanges();
      expect(sources()).toEqual([
        'assets/drinkduel/avatars/avatar-04.png',
        'assets/drinkduel/avatars/avatar-30.webp',
      ]);
    },
  );
  const view: RoomView = {
    roomId: 'ABC234',
    roomRevision: 1,
    expiresAt: '',
    sessionId: null,
    lifecycle: 'LOBBY',
    currentPlayerId: 'gm',
    gmPlayerId: 'gm',
    isGm: true,
    capacity: 20,
    joinable: true,
    allowedActions: ['GM_KICK_PLAYER', 'GM_START_GAME', 'GM_CLOSE_ROOM'],
    players: [
      { playerId: 'gm', nickname: 'Aood', avatarId: 1, connectionStatus: 'CONNECTED' },
      { playerId: 'guest', nickname: 'Ken', avatarId: 25, connectionStatus: 'CONNECTED' },
    ],
  };
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    connection: ReturnType<typeof signal<string>>;
    notice: ReturnType<typeof signal<string>>;
    terminal: ReturnType<typeof signal<string>>;
    busy: ReturnType<typeof signal<boolean>>;
    newAvatar: ReturnType<typeof signal<boolean>>;
    savedRoom: () => null;
    can: (action: string) => boolean;
    join: ReturnType<typeof vi.fn>;
    resume: ReturnType<typeof vi.fn>;
    action: ReturnType<typeof vi.fn>;
    hostIdentityAvailable: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    selectLiarsDice: ReturnType<typeof vi.fn>;
    startLiarsDice: ReturnType<typeof vi.fn>;
  };
  let clipboardWriteText: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    clipboardWriteText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: clipboardWriteText },
    });
    vi.mocked(QRCode.toDataURL).mockClear();
    client = {
      room: signal<RoomView | null>(null),
      connection: signal('connected'),
      notice: signal(''),
      terminal: signal(''),
      busy: signal(false),
      newAvatar: signal(false),
      savedRoom: () => null,
      can: (action) => client.room()?.allowedActions.includes(action) ?? false,
      join: vi.fn(),
      resume: vi.fn(),
      action: vi.fn(),
      hostIdentityAvailable: vi.fn().mockResolvedValue(true),
      create: vi.fn(),
      selectLiarsDice: vi.fn(),
      startLiarsDice: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: RoomClient, useValue: client },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap({}),
              paramMap: convertToParamMap({ id: 'ABC234' }),
            },
          },
        },
      ],
    });
  });
  it('Home renders the two primary routes', () => {
    const fixture = TestBed.createComponent(Home);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('a[href="/create"]')?.textContent).toContain('Create Room');
    expect(root.querySelector('a[href="/join"]')?.textContent).toContain('Join Room');
  });
  it('Join validates room code and nickname before connecting', () => {
    const fixture = TestBed.createComponent(Join);
    fixture.detectChanges();
    fixture.componentInstance.join();
    fixture.detectChanges();
    expect(client.join).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('six-character');
    fixture.componentInstance.code = 'ABC234';
    fixture.componentInstance.nickname = 'เคน';
    fixture.componentInstance.join();
    expect(client.join).toHaveBeenCalledWith('ABC234', 'เคน');
  });
  it('Create checks availability and nickname', async () => {
    const fixture = TestBed.createComponent(Create);
    await fixture.whenStable();
    fixture.componentInstance.create();
    expect(client.create).not.toHaveBeenCalled();
    fixture.componentInstance.nickname = 'Aood';
    fixture.componentInstance.create();
    expect(client.create).toHaveBeenCalledWith('Aood');
  });
  it('GM sees room controls and continues to the separate game catalog', async () => {
    client.room.set(view);
    const fixture = TestBed.createComponent(Lobby);
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Close Room');
    expect(root.textContent).not.toContain('Leave Room');
    expect(root.querySelector('button[aria-label="Remove Ken"]')).not.toBeNull();
    expect(root.querySelector('button[aria-label="Remove Aood"]')).toBeNull();
    expect(root.textContent).not.toContain('Who Am I?');
    expect(root.querySelector('a[href="/room/ABC234/games"]')?.textContent).toContain(
      'Choose Game',
    );
  });
  it.each([
    ['root', 'https://root.example', '/', 'https://root.example/join?room=ABC234'],
    [
      'subpath',
      'https://party.example',
      '/drinkduel/',
      'https://party.example/drinkduel/join?room=ABC234',
    ],
  ])(
    'copies the same base-aware %s join URL represented by the QR code',
    async (_deployment, origin, basePath, expectedUrl) => {
      TestBed.overrideProvider(BROWSER, {
        useValue: {
          storage: localStorage,
          socket: vi.fn(),
          request: vi.fn(),
          origin,
          basePath,
          randomToken: vi.fn(),
          requestId: vi.fn(),
        } as unknown as BrowserPort,
      });
      client.room.set(view);
      const fixture = TestBed.createComponent(Lobby);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(QRCode.toDataURL).toHaveBeenCalledWith(expectedUrl, expect.any(Object));
      await fixture.componentInstance.copyLink();
      fixture.detectChanges();
      expect(clipboardWriteText).toHaveBeenCalledWith(expectedUrl);
      expect(fixture.nativeElement.textContent).toContain('Copied!');
    },
  );
  it('keeps Copy Code limited to the room code', async () => {
    client.room.set(view);
    const fixture = TestBed.createComponent(Lobby);
    fixture.detectChanges();
    await fixture.componentInstance.copyCode(view.roomId);
    expect(clipboardWriteText).toHaveBeenCalledWith('ABC234');
  });
  it('Player sees Leave without GM controls and updates live membership', async () => {
    client.room.set({
      ...view,
      currentPlayerId: 'guest',
      isGm: false,
      allowedActions: ['LEAVE_ROOM'],
    });
    const fixture = TestBed.createComponent(Lobby);
    fixture.detectChanges();
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Leave Room');
    expect(root.textContent).not.toContain('Close Room');
    expect(root.textContent).toContain('Waiting for Game Master');
    expect(root.textContent).not.toContain('Choose Game');
    expect(root.querySelector('.kick')).toBeNull();
    client.room.update((room) => ({ ...room!, players: [view.players[0]] }));
    fixture.detectChanges();
    expect(root.querySelectorAll('.player-card')).toHaveLength(1);
  });
  it('keeps both game selections GM-only and uses their authoritative commands', () => {
    client.room.set({
      ...view,
      allowedActions: [...view.allowedActions, 'GM_SELECT_LIARS_DICE'],
    });
    let fixture = TestBed.createComponent(ChooseGame);
    fixture.detectChanges();
    let root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('a[href="/room/ABC234"]')?.textContent).toContain('Back to Room');
    const choose = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Play This Game'),
    );
    choose?.click();
    expect(client.action).toHaveBeenCalledWith('GM_START_GAME');
    const liarCard = [...root.querySelectorAll('.game-card')].find((card) =>
      card.textContent?.includes("Liar's Dice"),
    );
    (liarCard?.querySelector('button') as HTMLButtonElement).click();
    expect(client.selectLiarsDice).toHaveBeenCalledOnce();

    client.action.mockClear();
    client.room.set({
      ...view,
      isGm: false,
      currentPlayerId: 'guest',
      allowedActions: ['LEAVE_ROOM'],
    });
    fixture.destroy();
    fixture = TestBed.createComponent(ChooseGame);
    fixture.detectChanges();
    root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Waiting for Game Master');
    expect(root.textContent).not.toContain('Play This Game');
    expect(client.action).not.toHaveBeenCalled();
    expect(client.selectLiarsDice).not.toHaveBeenCalledTimes(2);
  });
  it('keeps both games disabled until two connected players are present', () => {
    client.room.set({
      ...view,
      allowedActions: [...view.allowedActions, 'GM_SELECT_LIARS_DICE'],
      players: [view.players[0], { ...view.players[1], connectionStatus: 'DISCONNECTED' }],
    });
    const fixture = TestBed.createComponent(ChooseGame);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const cards = [...root.querySelectorAll<HTMLElement>('.game-card')];
    const whoButton = cards.find((card) => card.textContent?.includes('Who Am I?'))!
      .querySelector('button') as HTMLButtonElement;
    const liarButton = cards.find((card) => card.textContent?.includes("Liar's Dice"))!
      .querySelector('button') as HTMLButtonElement;
    expect(whoButton.disabled).toBe(true);
    expect(liarButton.disabled).toBe(true);
    expect(root.textContent?.match(/At least two connected players are needed to start\./g)).toHaveLength(2);
    liarButton.click();
    expect(client.selectLiarsDice).not.toHaveBeenCalled();

    client.room.update((room) => ({
      ...room!,
      roomRevision: 2,
      players: room!.players.map((player) => ({ ...player, connectionStatus: 'CONNECTED' })),
    }));
    fixture.detectChanges();
    expect(whoButton.disabled).toBe(false);
    expect(liarButton.disabled).toBe(false);

    client.room.update((room) => ({
      ...room!,
      roomRevision: 3,
      players: room!.players.map((player, index) =>
        index === 0 ? player : { ...player, connectionStatus: 'DISCONNECTED' },
      ),
    }));
    fixture.detectChanges();
    expect(whoButton.disabled).toBe(true);
    expect(liarButton.disabled).toBe(true);
  });
  it('GM absence is a friendly waiting state and names render as text', async () => {
    client.room.set({
      ...view,
      players: [
        {
          ...view.players[0],
          nickname: '<script>bad()</script>',
          connectionStatus: 'DISCONNECTED',
        },
      ],
    });
    const fixture = TestBed.createComponent(Lobby);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Waiting for Game Master');
    expect(fixture.nativeElement.querySelector('script')).toBeNull();
  });
});
