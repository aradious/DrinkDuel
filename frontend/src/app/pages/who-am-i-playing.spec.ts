import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView, WhoAmIGameCardView } from '../core/room.models';
import { WhoAmIPlaying } from './who-am-i-playing';

describe('WhoAmIPlaying', () => {
  const longNickname = 'A very long player nickname that must remain inside the card';
  const longAssigned = 'A very long assigned character name that should be clamped consistently';
  const room: RoomView = {
    roomId: 'ABC234', roomRevision: 5, expiresAt: '', sessionId: 'session', lifecycle: 'IN_GAME',
    currentPlayerId: 'guest', gmPlayerId: 'gm', isGm: false, capacity: 20, joinable: false,
    allowedActions: [], players: [],
    game: {
      gameType: 'WHO_AM_I', phase: 'PLAYING', participants: [], submittedCount: 0,
      participantCount: 3, currentPlayerSubmitted: false, readyForShuffle: false, canShuffle: false,
      roast: null, reveal: [], cards: [
        { playerId: 'gm', nickname: longNickname, avatarId: 1, connectionStatus: 'CONNECTED', gameStatus: 'PLAYING', statusVersion: 0, assignedName: longAssigned },
        { playerId: 'guest', nickname: 'Tree', avatarId: 2, connectionStatus: 'CONNECTED', gameStatus: 'PLAYING', statusVersion: 0, assignedName: null },
        { playerId: 'done', nickname: 'Ken', avatarId: 3, connectionStatus: 'CONNECTED', gameStatus: 'GOT_IT', statusVersion: 1, assignedName: 'Batman' },
      ],
    },
  };
  let client: any;
  beforeEach(() => {
    client = {
      room: signal<RoomView | null>(room),
      busy: signal(false),
      can: (action: string) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      markGotIt: vi.fn(), resetPlayerStatus: vi.fn(), endGame: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: RoomClient, useValue: client }] });
  });

  it('renders YOU first, hides the own name, and exposes visible names with native titles', () => {
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    const cards = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.game-card')];
    expect(cards[0].textContent).toContain('Tree');
    expect(cards[0].textContent).toContain('Your name is hidden!');
    expect(cards[0].textContent).not.toContain(longAssigned);
    expect(cards[1].textContent).toContain(longAssigned);
    expect(cards[1].querySelector('.identity h3')?.getAttribute('title')).toBe(longNickname);
    expect(cards[1].querySelector('.name-card')?.getAttribute('title')).toBe(longAssigned);
  });

  it('filters guessed state and searches nickname or only visible assigned names', () => {
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    expect(fixture.componentInstance.filteredCards().map((c) => c.playerId)).toEqual(['guest', 'gm']);
    fixture.componentInstance.filter.set('GUESSED');
    expect(fixture.componentInstance.filteredCards().map((c) => c.playerId)).toEqual(['done']);
    fixture.componentInstance.filter.set('NOT_GUESSED'); fixture.componentInstance.query.set('character name');
    expect(fixture.componentInstance.filteredCards().map((c) => c.playerId)).toEqual(['gm']);
    fixture.componentInstance.query.set('Tree');
    expect(fixture.componentInstance.filteredCards().map((c) => c.playerId)).toEqual(['guest']);
  });

  it('has no player-result action while keeping GM controls scoped', () => {
    let fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    let root = fixture.nativeElement as HTMLElement;
    expect(root.querySelectorAll('.card-footer button')).toHaveLength(0);

    client.room.set({ ...room, isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_MARK_GOT_IT', 'GM_RESET_PLAYER_STATUS'] });
    fixture.destroy(); fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.self .self-name')?.textContent).toContain('Your name is hidden!');
    root.querySelector<HTMLButtonElement>('.got-it')?.click();
    expect(client.markGotIt).toHaveBeenCalled();
    fixture.componentInstance.filter.set('GUESSED'); fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('.undo-result')?.click();
    expect(client.resetPlayerStatus).toHaveBeenCalledWith('done', 1);
  });

  it('keeps a newly Got It card visible with authoritative status and immediate Undo', () => {
    client.room.set({
      ...room,
      isGm: true,
      currentPlayerId: 'gm',
      allowedActions: ['GM_MARK_GOT_IT', 'GM_RESET_PLAYER_STATUS'],
      game: {
        ...room.game!,
        cards: room.game!.cards.map((card) =>
          card.playerId === 'guest' ? { ...card, assignedName: 'Visible Secret' } : card,
        ),
      },
    });
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    fixture.componentInstance.markGotIt('guest');
    expect(client.markGotIt).toHaveBeenCalledWith('guest');

    const updated = client.room()!;
    client.room.set({
      ...updated,
      game: {
        ...updated.game!,
        cards: updated.game!.cards.map((card: WhoAmIGameCardView) =>
          card.playerId === 'guest'
            ? { ...card, gameStatus: 'GOT_IT' as const, statusVersion: 1 }
            : card,
        ),
      },
    });
    fixture.detectChanges();
    const rendered = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.game-card')];
    const retained = rendered.find((card) => card.textContent?.includes('Tree'))!;
    expect(retained.textContent).toContain('✓ Got It');
    expect(retained.textContent).toContain('Visible Secret');
    expect((fixture.nativeElement as HTMLElement).querySelector('.game-card.self')?.textContent).toContain('Your name is hidden!');
    expect(retained.querySelector('.undo-result')).not.toBeNull();
    expect(fixture.componentInstance.notGuessedCount()).toBe(1);
    expect(fixture.componentInstance.guessedCount()).toBe(2);

    retained.querySelector<HTMLButtonElement>('.undo-result')!.click();
    expect(client.resetPlayerStatus).toHaveBeenCalledWith('guest', 1);
    fixture.componentInstance.applyFilter('NOT_GUESSED');
    fixture.detectChanges();
    expect([...(fixture.nativeElement as HTMLElement).querySelectorAll('.game-card')]
      .some((card) => card.textContent?.includes('Tree'))).toBe(false);
  });

  it('retains an authoritative Got It transition on a Player client until the filter is reapplied', () => {
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    client.room.set({
      ...room,
      roomRevision: 6,
      game: {
        ...room.game!,
        cards: room.game!.cards.map((card) =>
          card.playerId === 'guest'
            ? { ...card, gameStatus: 'GOT_IT' as const, statusVersion: 1 }
            : card,
        ),
      },
    });
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const changed = [...root.querySelectorAll<HTMLElement>('.game-card')]
      .find((card) => card.textContent?.includes('Tree'))!;
    expect(changed).toBeDefined();
    expect(changed.classList.contains('guessed')).toBe(true);
    expect(changed.textContent).toContain('✓ Got It');
    expect(changed.textContent).toContain('Your name is hidden!');
    expect(changed.querySelector('.undo-result')).toBeNull();
    expect(fixture.componentInstance.notGuessedCount()).toBe(1);
    expect(fixture.componentInstance.guessedCount()).toBe(2);

    fixture.componentInstance.applyFilter('NOT_GUESSED'); fixture.detectChanges();
    expect([...root.querySelectorAll<HTMLElement>('.game-card')]
      .some((card) => card.textContent?.includes('Tree'))).toBe(false);
    fixture.componentInstance.applyFilter('GUESSED'); fixture.detectChanges();
    expect([...root.querySelectorAll<HTMLElement>('.game-card')]
      .some((card) => card.textContent?.includes('Tree'))).toBe(true);
  });

  it('removes retained success styling after an authoritative Undo snapshot', () => {
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    const gotItRoom: RoomView = {
      ...room,
      roomRevision: 6,
      game: {
        ...room.game!,
        cards: room.game!.cards.map((card) => card.playerId === 'gm'
          ? { ...card, gameStatus: 'GOT_IT' as const, statusVersion: 1 }
          : card),
      },
    };
    client.room.set(gotItRoom); fixture.detectChanges();
    client.room.set({
      ...gotItRoom,
      roomRevision: 7,
      game: {
        ...gotItRoom.game!,
        cards: gotItRoom.game!.cards.map((card) => card.playerId === 'gm'
          ? { ...card, gameStatus: 'PLAYING' as const, statusVersion: 2 }
          : card),
      },
    });
    fixture.detectChanges();

    const restored = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.game-card')]
      .find((card) => card.textContent?.includes(longNickname))!;
    expect(restored).toBeDefined();
    expect(restored.classList.contains('guessed')).toBe(false);
    expect(restored.textContent).toContain('Not Guessed');
    expect(fixture.componentInstance.notGuessedCount()).toBe(2);
    expect(fixture.componentInstance.guessedCount()).toBe(1);
  });

  it('offers End Game only to the GM and confirms through the authoritative command', () => {
    let fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.end-game')).toBeNull();
    fixture.destroy();

    client.room.set({ ...room, isGm: true, currentPlayerId: 'gm', allowedActions: ['GM_END_GAME'] });
    fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const dialog = root.querySelector<HTMLDialogElement>('.end-game-dialog')!;
    dialog.showModal = vi.fn();
    dialog.close = vi.fn();
    root.querySelector<HTMLButtonElement>('.end-game')!.click();
    expect(dialog.showModal).toHaveBeenCalledOnce();
    root.querySelector<HTMLButtonElement>('.dialog-cancel')!.click();
    expect(dialog.close).toHaveBeenCalledOnce();
    expect(client.endGame).not.toHaveBeenCalled();
    root.querySelector<HTMLButtonElement>('.dialog-confirm')!.click();
    expect(client.endGame).toHaveBeenCalledOnce();
  });

  it.each([10, 20])('renders %i stable cards in the bounded grid with YOU first', (count) => {
    const cards = Array.from({ length: count }, (_, index) => ({
      playerId: index === count - 1 ? 'guest' : `player-${index}`,
      nickname: `Player ${index + 1}`,
      avatarId: (index % 25) + 1,
      connectionStatus: 'CONNECTED' as const,
      gameStatus: 'PLAYING' as const,
      statusVersion: 0,
      assignedName: index === count - 1 ? null : `Secret ${index + 1}`,
    }));
    client.room.set({ ...room, game: { ...room.game!, participantCount: count, cards } });
    const fixture = TestBed.createComponent(WhoAmIPlaying); fixture.detectChanges();
    const rendered = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.game-card')];
    expect(rendered).toHaveLength(count);
    expect(rendered[0].textContent).toContain(`Player ${count}`);
    expect(rendered[0].textContent).toContain('Your name is hidden!');
    expect((fixture.nativeElement as HTMLElement).querySelector('.card-scroll')).not.toBeNull();
  });
});
