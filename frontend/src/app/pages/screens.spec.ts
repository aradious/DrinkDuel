import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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
  };
  beforeEach(() => {
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
  it('keeps game selection GM-only and starts Who Am I through the existing command', () => {
    client.room.set(view);
    let fixture = TestBed.createComponent(ChooseGame);
    fixture.detectChanges();
    let root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('a[href="/room/ABC234"]')?.textContent).toContain('Back to Room');
    const choose = [...root.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('Play This Game'),
    );
    choose?.click();
    expect(client.action).toHaveBeenCalledWith('GM_START_GAME');

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
