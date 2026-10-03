import { describe, expect, it } from 'vitest';
import { normalizeLiarsDiceView } from './liars-dice-state';

const GM = '11111111-1111-4111-8111-111111111111';
const PLAYER = '22222222-2222-4222-8222-222222222222';
const GM_HAND = [1, 1, 2, 3, 4];
const PLAYER_HAND = [2, 2, 3, 5, 6];
const participants = [GM, PLAYER];
const counts = [
  { face: 1, rawCount: 2, effectiveCount: 2 },
  { face: 2, rawCount: 3, effectiveCount: 5 },
  { face: 3, rawCount: 2, effectiveCount: 4 },
  { face: 4, rawCount: 1, effectiveCount: 3 },
  { face: 5, rawCount: 1, effectiveCount: 3 },
  { face: 6, rawCount: 1, effectiveCount: 3 },
];

const playing = (ownDice: number[] = PLAYER_HAND) => ({
  gameType: 'LIARS_DICE',
  phase: 'PLAYING',
  participantIds: participants,
  ownDice,
  revealedHands: [],
  revealCounts: [],
});

const reveal = () => ({
  gameType: 'LIARS_DICE',
  phase: 'REVEAL',
  participantIds: participants,
  ownDice: [],
  revealedHands: [
    { playerId: GM, dice: GM_HAND },
    { playerId: PLAYER, dice: PLAYER_HAND },
  ],
  revealCounts: counts,
});

describe('Liar’s Dice snapshot normalization', () => {
  it('parses a clean START snapshot without secret state', () => {
    expect(
      normalizeLiarsDiceView({
        gameType: 'LIARS_DICE',
        phase: 'START',
        participantIds: [],
        ownDice: [],
        revealedHands: [],
        revealCounts: [],
      }),
    ).toEqual({
      gameType: 'LIARS_DICE',
      phase: 'START',
      participantIds: [],
      ownDice: null,
      revealedHands: [],
      revealCounts: [],
    });
  });

  it('parses a participant PLAYING snapshot as exactly one immutable hand', () => {
    const result = normalizeLiarsDiceView(playing(), PLAYER);
    expect(result?.phase).toBe('PLAYING');
    if (result?.phase === 'PLAYING') expect(result.ownDice).toEqual(PLAYER_HAND);
    expect(JSON.stringify(result)).not.toContain(GM_HAND.join(','));
  });

  it('parses an excluded-member PLAYING snapshot without manufacturing a hand', () => {
    const excluded = '33333333-3333-4333-8333-333333333333';
    const result = normalizeLiarsDiceView(playing([]), excluded);
    expect(result?.phase).toBe('PLAYING');
    if (result?.phase === 'PLAYING') expect(result.ownDice).toBeNull();
  });

  it('parses complete authoritative REVEAL hands and counts', () => {
    const result = normalizeLiarsDiceView(reveal(), PLAYER);
    expect(result?.phase).toBe('REVEAL');
    if (result?.phase === 'REVEAL') {
      expect(result.revealedHands.map((item) => item.dice)).toEqual([GM_HAND, PLAYER_HAND]);
      expect(result.revealCounts).toEqual(counts);
      expect(result.ownDice).toBeNull();
    }
  });

  it('rejects unknown phases, malformed IDs, invalid values and malformed hands', () => {
    expect(normalizeLiarsDiceView({ ...playing(), phase: 'ROLL' }, PLAYER)).toBeNull();
    expect(
      normalizeLiarsDiceView({ ...playing(), participantIds: ['not-a-uuid', PLAYER] }, PLAYER),
    ).toBeNull();
    expect(normalizeLiarsDiceView(playing([1, 1, 2, 3, 7]), PLAYER)).toBeNull();
    expect(normalizeLiarsDiceView(playing([1, 1, 2, 3]), PLAYER)).toBeNull();
    expect(normalizeLiarsDiceView(playing([1, 2, 3, 4, 5]), PLAYER)).toBeNull();
  });

  it('rejects another hand in PLAYING and missing participant own dice', () => {
    expect(
      normalizeLiarsDiceView(
        { ...playing(), revealedHands: [{ playerId: GM, dice: GM_HAND }] },
        PLAYER,
      ),
    ).toBeNull();
    expect(normalizeLiarsDiceView(playing([]), PLAYER)).toBeNull();
  });

  it('rejects duplicate, missing, inconsistent and impossible reveal counts', () => {
    expect(
      normalizeLiarsDiceView({ ...reveal(), revealCounts: [...counts.slice(0, 5), counts[0]] }),
    ).toBeNull();
    expect(normalizeLiarsDiceView({ ...reveal(), revealCounts: counts.slice(0, 5) })).toBeNull();
    expect(
      normalizeLiarsDiceView({
        ...reveal(),
        revealCounts: counts.map((item) =>
          item.face === 2 ? { ...item, effectiveCount: 99 } : item,
        ),
      }),
    ).toBeNull();
  });

  it('rejects incomplete, duplicate or out-of-roster reveal hands', () => {
    expect(
      normalizeLiarsDiceView({ ...reveal(), revealedHands: reveal().revealedHands.slice(0, 1) }),
    ).toBeNull();
    expect(
      normalizeLiarsDiceView({
        ...reveal(),
        revealedHands: [reveal().revealedHands[0], reveal().revealedHands[0]],
      }),
    ).toBeNull();
  });
});
