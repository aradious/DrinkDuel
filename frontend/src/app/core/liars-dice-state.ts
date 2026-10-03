import {
  DiceHand,
  DieValue,
  LiarsDiceFaceCountView,
  LiarsDiceGameView,
  LiarsDiceHandView,
} from './room.models';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type UnknownRecord = Record<string, unknown>;

const record = (value: unknown): UnknownRecord | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as UnknownRecord)
    : null;

const playerId = (value: unknown): string | null =>
  typeof value === 'string' && UUID.test(value) ? value : null;

const die = (value: unknown): DieValue | null =>
  Number.isInteger(value) && Number(value) >= 1 && Number(value) <= 6
    ? (Number(value) as DieValue)
    : null;

const hand = (value: unknown): DiceHand | null => {
  if (!Array.isArray(value) || value.length !== 5) return null;
  const values = value.map(die);
  if (values.some((item) => item === null) || new Set(values).size === 5) return null;
  return [values[0]!, values[1]!, values[2]!, values[3]!, values[4]!];
};

const empty = (value: unknown): boolean => Array.isArray(value) && value.length === 0;

function roster(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  const ids = value.map(playerId);
  if (ids.some((id) => id === null) || new Set(ids).size !== ids.length) return null;
  return ids as string[];
}

function revealedHands(value: unknown, participants: string[]): LiarsDiceHandView[] | null {
  if (!Array.isArray(value) || value.length !== participants.length) return null;
  const parsed = value.map((candidate) => {
    const item = record(candidate);
    const id = playerId(item?.['playerId']);
    const dice = hand(item?.['dice']);
    return id && dice ? { playerId: id, dice } : null;
  });
  if (parsed.some((item) => item === null)) return null;
  const hands = parsed as LiarsDiceHandView[];
  if (
    new Set(hands.map((item) => item.playerId)).size !== hands.length ||
    hands.some((item, index) => item.playerId !== participants[index])
  )
    return null;
  return hands;
}

function counts(value: unknown, hands: LiarsDiceHandView[]): LiarsDiceFaceCountView[] | null {
  if (!Array.isArray(value) || value.length !== 6) return null;
  const parsed = value.map((candidate) => {
    const item = record(candidate);
    const face = die(item?.['face']);
    const rawCount = item?.['rawCount'];
    const effectiveCount = item?.['effectiveCount'];
    return face && Number.isInteger(rawCount) && Number(rawCount) >= 0 &&
      Number.isInteger(effectiveCount) && Number(effectiveCount) >= 0
      ? { face, rawCount: Number(rawCount), effectiveCount: Number(effectiveCount) }
      : null;
  });
  if (parsed.some((item) => item === null)) return null;
  const result = parsed as LiarsDiceFaceCountView[];
  if (new Set(result.map((item) => item.face)).size !== 6) return null;
  result.sort((left, right) => left.face - right.face);
  if (result.some((item, index) => item.face !== index + 1)) return null;
  const expected = [0, 0, 0, 0, 0, 0];
  hands.forEach((item) => item.dice.forEach((value) => expected[value - 1]++));
  if (
    result.some(
      (item, index) =>
        item.rawCount !== expected[index] ||
        item.effectiveCount !== (index === 0 ? expected[0] : expected[index] + expected[0]),
    )
  )
    return null;
  return result;
}

/** Converts one untrusted wire game view into a phase-safe recipient view. */
export function normalizeLiarsDiceView(
  value: unknown,
  recipientPlayerId?: string,
): LiarsDiceGameView | null {
  const source = record(value);
  if (!source || source['gameType'] !== 'LIARS_DICE') return null;
  const participants = roster(source['participantIds']);
  if (!participants) return null;

  if (source['phase'] === 'START') {
    if (
      participants.length !== 0 ||
      !empty(source['ownDice']) ||
      !empty(source['revealedHands']) ||
      !empty(source['revealCounts'])
    )
      return null;
    return {
      gameType: 'LIARS_DICE',
      phase: 'START',
      participantIds: [],
      ownDice: null,
      revealedHands: [],
      revealCounts: [],
    };
  }

  if (source['phase'] === 'PLAYING') {
    if (participants.length < 2 || !empty(source['revealedHands']) || !empty(source['revealCounts']))
      return null;
    let ownDice: DiceHand | null = null;
    if (!empty(source['ownDice'])) {
      ownDice = hand(source['ownDice']);
      if (!ownDice) return null;
    }
    if (recipientPlayerId && participants.includes(recipientPlayerId) !== Boolean(ownDice)) return null;
    return {
      gameType: 'LIARS_DICE',
      phase: 'PLAYING',
      participantIds: participants,
      ownDice,
      revealedHands: [],
      revealCounts: [],
    };
  }

  if (source['phase'] === 'REVEAL') {
    if (participants.length < 2 || !empty(source['ownDice'])) return null;
    const hands = revealedHands(source['revealedHands'], participants);
    if (!hands) return null;
    const revealCounts = counts(source['revealCounts'], hands);
    if (!revealCounts) return null;
    return {
      gameType: 'LIARS_DICE',
      phase: 'REVEAL',
      participantIds: participants,
      ownDice: null,
      revealedHands: hands,
      revealCounts,
    };
  }

  return null;
}
