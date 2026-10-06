import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RoomClient } from '../core/room-client';
import { RoomView } from '../core/room.models';
import { WhoAmISubmit } from './who-am-i-submit';

describe('WhoAmISubmit', () => {
  const game = {
    gameType: 'WHO_AM_I' as const,
    phase: 'SUBMIT_NAME' as const,
    participants: [
      { playerId: 'gm', submitted: false, resetSubmissionId: null },
      { playerId: 'guest', submitted: true, resetSubmissionId: 'submission-1' },
    ],
    submittedCount: 1,
    participantCount: 2,
    currentPlayerSubmitted: false,
    readyForShuffle: false,
    canShuffle: false,
    cards: [],
    roast: null,
    reveal: [],
  };
  const room: RoomView = {
    roomId: 'ABC234',
    roomRevision: 2,
    expiresAt: '',
    sessionId: 'session-1',
    lifecycle: 'IN_GAME',
    currentPlayerId: 'gm',
    gmPlayerId: 'gm',
    isGm: true,
    capacity: 20,
    joinable: false,
    allowedActions: ['SUBMIT_NAME', 'GM_RESET_SUBMISSION'],
    players: [
      { playerId: 'gm', nickname: 'Aood', avatarId: 1, connectionStatus: 'CONNECTED' },
      { playerId: 'guest', nickname: 'Ken', avatarId: 25, connectionStatus: 'CONNECTED' },
    ],
    game,
  };
  let client: {
    room: ReturnType<typeof signal<RoomView | null>>;
    busy: ReturnType<typeof signal<boolean>>;
    can: (action: string) => boolean;
    submitName: ReturnType<typeof vi.fn>;
    resetSubmission: ReturnType<typeof vi.fn>;
    shuffle: ReturnType<typeof vi.fn>;
    chooseAnotherGame: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      room: signal<RoomView | null>(room),
      busy: signal(false),
      can: (action) => !client.busy() && (client.room()?.allowedActions.includes(action) ?? false),
      submitName: vi.fn(),
      resetSubmission: vi.fn(),
      shuffle: vi.fn(),
      chooseAnotherGame: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: RoomClient, useValue: client }] });
  });

  it('renders real players, avatars, progress, and status without submitted content', () => {
    const fixture = TestBed.createComponent(WhoAmISubmit);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('1 / 2');
    expect(root.textContent).toContain('Aood');
    expect(root.textContent).toContain('Ken');
    expect(root.textContent).toContain('Submitted');
    expect(root.querySelectorAll('dd-avatar')).toHaveLength(2);
    expect(root.querySelector('img[src="assets/drinkduel/games/who-am-i-logo.webp"]')).not.toBeNull();
    expect(root.textContent).not.toContain('submission-1');
  });

  it('validates and submits only the current player name', () => {
    const fixture = TestBed.createComponent(WhoAmISubmit);
    fixture.detectChanges();
    fixture.componentInstance.submit();
    expect(client.submitName).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error).toContain('Add a name');
    fixture.componentInstance.secretName = 'Batman';
    fixture.componentInstance.submit();
    expect(client.submitName).toHaveBeenCalledWith('Batman');
  });

  it('shows the locked state after submission and keeps GM reset authority scoped', () => {
    client.room.set({
      ...room,
      allowedActions: ['GM_RESET_SUBMISSION', 'GM_SHUFFLE'],
      game: { ...game, currentPlayerSubmitted: true, submittedCount: 2, readyForShuffle: true, canShuffle: true },
    });
    const fixture = TestBed.createComponent(WhoAmISubmit);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('Your secret is safe.');
    expect(root.querySelector('input')).toBeNull();
    expect(root.textContent).toContain('Shuffle Names');
    expect(root.querySelectorAll('.reset')).toHaveLength(1);
  });

  it('shows the authoritative GM-only Choose Another Game action below Shuffle', () => {
    client.room.set({ ...room, allowedActions: [...room.allowedActions, 'GM_BACK_TO_ROOM'] });
    const fixture = TestBed.createComponent(WhoAmISubmit);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const shuffle = root.querySelector<HTMLButtonElement>('.shuffle')!;
    const choose = root.querySelector<HTMLButtonElement>('.choose-another')!;
    expect(choose.textContent).toContain('Choose Another Game');
    expect(shuffle.compareDocumentPosition(choose) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    choose.click();
    expect(client.chooseAnotherGame).toHaveBeenCalledOnce();
    client.busy.set(true);
    fixture.detectChanges();
    expect(shuffle.disabled).toBe(true);
    expect(choose.disabled).toBe(true);
  });

  it('does not advertise Choose Another Game to a normal player', () => {
    client.room.set({
      ...room,
      currentPlayerId: 'guest',
      isGm: false,
      allowedActions: ['SUBMIT_NAME'],
      game: { ...game, currentPlayerSubmitted: true },
    });
    const fixture = TestBed.createComponent(WhoAmISubmit);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.choose-another')).toBeNull();
  });
});
