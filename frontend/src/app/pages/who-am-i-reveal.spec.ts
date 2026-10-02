import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView, WhoAmIRevealCardView } from '../core/room.models';
import { WhoAmIReveal } from './who-am-i-reveal';

describe('WhoAmIReveal', () => {
  const longNickname = 'ผู้เล่นชื่อยาวมาก Mixed Language Celebration';
  const longAnswer = 'ตัวละครชื่อยาวมาก Very Long Assigned Character Name';
  function cards(count: number): WhoAmIRevealCardView[] {
    return Array.from({ length: count }, (_, index) => ({
      playerId: index === count - 1 ? 'self' : `p${index}`,
      nickname: index === 0 ? longNickname : `Player ${index + 1}`,
      avatarId: (index % 25) + 1,
      gameStatus: index % 2 === 0 ? 'GOT_IT' : 'PLAYING',
      assignedName: index === 0 ? longAnswer : `Secret ${index + 1}`,
      createdBy: index === 0 ? 'Removed Submitter' : `Creator ${index + 1}`,
    }));
  }
  function render(count: number, isGm = false) {
    const busy = signal(false);
    const playAgain = vi.fn(() => busy.set(true));
    const room: RoomView = {
      roomId: 'ABC234', roomRevision: 9, expiresAt: '', sessionId: 'round', lifecycle: 'IN_GAME',
      currentPlayerId: 'self', gmPlayerId: isGm ? 'self' : 'p0', isGm, capacity: 20, joinable: false,
      allowedActions: isGm ? ['GET_STATE', 'GM_PLAY_AGAIN'] : ['GET_STATE'], players: [],
      game: { gameType: 'WHO_AM_I', phase: 'REVEAL', participants: [], submittedCount: 0,
        participantCount: count, currentPlayerSubmitted: false, readyForShuffle: false,
        canShuffle: false, cards: [], roast: null, reveal: cards(count) },
    };
    TestBed.configureTestingModule({ providers: [{ provide: RoomClient, useValue: {
      room: signal(room), busy, can: (action: string) => action === 'GM_PLAY_AGAIN' && !busy(), playAgain,
    } }] });
    const fixture = TestBed.createComponent(WhoAmIReveal); fixture.detectChanges();
    return fixture;
  }
  it('renders YOU first with every authorized assignment and retained attribution', () => {
    const fixture = render(2); const root = fixture.nativeElement as HTMLElement;
    const rendered = [...root.querySelectorAll('.reveal-card')];
    expect(rendered[0].textContent).toContain('Player 2');
    expect(rendered[0].textContent).toContain('YOU');
    expect(root.textContent).toContain(longAnswer);
    expect(root.textContent).toContain('Submitted by Removed Submitter');
  });
  it('renders final Got It and Not Guessed states', () => {
    const root = render(3).nativeElement as HTMLElement;
    const rendered = [...root.querySelectorAll<HTMLElement>('.reveal-card')];
    const gotIt = rendered.find((card) => card.textContent?.includes('✓ Got It'));
    const notGuessed = rendered.find((card) => card.textContent?.includes('Not Guessed'));

    expect(gotIt).toBeDefined();
    expect(gotIt?.classList.contains('got-it-card')).toBe(true);
    expect(notGuessed).toBeDefined();
    expect(notGuessed?.classList.contains('got-it-card')).toBe(false);
  });
  it.each([2, 10, 20])('renders %i stable result cards in the bounded grid', (count) => {
    const root = render(count).nativeElement as HTMLElement;
    expect(root.querySelectorAll('.reveal-card')).toHaveLength(count);
    expect(root.querySelector('.results-scroll')).not.toBeNull();
  });
  it('offers Play Again only to the GM and prevents a duplicate pending command', () => {
    const fixture = render(3, true);
    const root = fixture.nativeElement as HTMLElement;
    const client = TestBed.inject(RoomClient);
    const button = root.querySelector<HTMLButtonElement>('.play-again')!;

    expect(button.textContent?.trim()).toBe('Play Again');
    button.click();
    fixture.detectChanges();
    expect(client.playAgain).toHaveBeenCalledTimes(1);
    expect(button.disabled).toBe(true);
    expect(button.textContent?.trim()).toBe('Starting new round…');
    button.click();
    expect(client.playAgain).toHaveBeenCalledTimes(1);
  });
  it('shows players a waiting message without an actionable Play Again control', () => {
    const root = render(3).nativeElement as HTMLElement;
    expect(root.querySelector('.play-again')).toBeNull();
    expect(root.querySelector('.waiting-for-gm')?.textContent).toContain('Waiting for the Game Master');
  });
});
