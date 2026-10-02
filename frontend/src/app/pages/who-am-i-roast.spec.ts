import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { WhoAmIRoast } from './who-am-i-roast';

describe('WhoAmIRoast', () => {
  const room: RoomView = {
    roomId: 'ABC234', roomRevision: 8, expiresAt: '', sessionId: 'round', lifecycle: 'IN_GAME',
    currentPlayerId: 'target', gmPlayerId: 'gm', isGm: false, capacity: 20, joinable: false,
    allowedActions: ['GET_STATE'],
    players: [
      { playerId: 'gm', nickname: 'Aood', avatarId: 1, connectionStatus: 'CONNECTED' },
      { playerId: 'target', nickname: 'Earth', avatarId: 25, connectionStatus: 'CONNECTED' },
    ],
    game: {
      gameType: 'WHO_AM_I', phase: 'ROAST', participants: [], submittedCount: 0,
      participantCount: 2, currentPlayerSubmitted: false, readyForShuffle: false, canShuffle: false,
      cards: [], roast: { kind: 'LAST_ONE', playingPlayerIds: ['target'] }, reveal: [],
    },
  };
  function render(view: RoomView) {
    const client = {
      room: signal<RoomView | null>(view), busy: signal(false), continueReveal: vi.fn(),
      can: (action: string) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
    };
    TestBed.configureTestingModule({ providers: [{ provide: RoomClient, useValue: client }] });
    const fixture = TestBed.createComponent(WhoAmIRoast); fixture.detectChanges();
    return { fixture, client };
  }
  it('renders only the authoritative target without exposing a hidden secret', () => {
    const { fixture } = render(room);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Still guessing, Earth?');
    expect(text).toContain('LAST UNGUESSED PLAYER');
    expect(text).not.toContain('TOP-SECRET-NAME');
    expect(fixture.componentInstance.target()?.avatarId).toBe(25);
  });
  it('shows waiting feedback without an actionable Reveal button to players', () => {
    const { fixture } = render(room);
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('.reveal-everyone')).toBeNull();
    expect(root.textContent).toContain('Waiting for the Game Master');
  });
  it('allows only the GM to invoke the authoritative continue command once pending', () => {
    const gmRoom = { ...room, currentPlayerId: 'gm', isGm: true, allowedActions: ['GM_CONTINUE_REVEAL'] };
    const { fixture, client } = render(gmRoom);
    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.reveal-everyone')!;
    button.click();
    expect(client.continueReveal).toHaveBeenCalledOnce();
    client.busy.set(true); fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Revealing');
  });
});
