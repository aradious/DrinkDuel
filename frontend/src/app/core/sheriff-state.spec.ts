import { describe, expect, it } from 'vitest';
import { normalizeSheriffView } from './sheriff-state';

const IDS = [
  '11111111-1111-4111-8111-111111111111',
  '22222222-2222-4222-8222-222222222222',
  '33333333-3333-4333-8333-333333333333',
  '44444444-4444-4444-8444-444444444444',
  '55555555-5555-4555-8555-555555555555',
];

const participants = IDS.map((playerId) => ({ playerId, lifeStatus: 'ALIVE' }));
const roleStatus = [
  { role: 'SHERIFF', assigned: 1, alive: 1, dead: 0 },
  { role: 'DEPUTY', assigned: 1, alive: 1, dead: 0 },
  { role: 'OUTLAW', assigned: 2, alive: 2, dead: 0 },
  { role: 'RENEGADE', assigned: 1, alive: 1, dead: 0 },
];
const playing = (ownRole: string | null = 'OUTLAW') => ({
  gameType: 'SHERIFF',
  phase: 'PLAYING',
  participants,
  sheriffPlayerId: IDS[0],
  ownRole,
  publicRoles: [{ playerId: IDS[0], role: 'SHERIFF' }],
  roleStatus,
  overallStatus: { total: 5, alive: 5, dead: 0 },
});

describe('Sheriff snapshot normalization', () => {
  it('parses an empty START snapshot without manufacturing round data', () => {
    expect(
      normalizeSheriffView({
        gameType: 'SHERIFF',
        phase: 'START',
        participants: [],
        sheriffPlayerId: null,
        ownRole: null,
        publicRoles: [],
        roleStatus: [],
        overallStatus: null,
      }),
    ).toEqual({
      gameType: 'SHERIFF',
      phase: 'START',
      participants: [],
      sheriffPlayerId: null,
      ownRole: null,
      publicRoles: [],
      roleStatus: [],
      overallStatus: null,
    });
  });

  it('keeps only the recipient role and public Sheriff during PLAYING', () => {
    const result = normalizeSheriffView(playing(), IDS[2]);

    expect(result?.phase).toBe('PLAYING');
    if (result?.phase !== 'PLAYING') throw new Error('Expected PLAYING');
    expect(result.ownRole).toBe('OUTLAW');
    expect(result.publicRoles).toEqual([{ playerId: IDS[0], role: 'SHERIFF' }]);
    expect(JSON.stringify(result)).not.toContain(`"playerId":"${IDS[1]}","role":"DEPUTY"`);
  });

  it('accepts excluded members without a private role', () => {
    const excluded = '66666666-6666-4666-8666-666666666666';
    const result = normalizeSheriffView(playing(null), excluded);

    expect(result?.phase).toBe('PLAYING');
    if (result?.phase === 'PLAYING') expect(result.ownRole).toBeNull();
  });

  it('accepts a public dead role and verifies its authoritative count change', () => {
    const result = normalizeSheriffView(
      {
        ...playing('DEPUTY'),
        participants: participants.map((item, index) =>
          index === 1 ? { ...item, lifeStatus: 'DEAD' } : item,
        ),
        publicRoles: [
          { playerId: IDS[0], role: 'SHERIFF' },
          { playerId: IDS[1], role: 'DEPUTY' },
        ],
        roleStatus: roleStatus.map((item) =>
          item.role === 'DEPUTY' ? { ...item, alive: 0, dead: 1 } : item,
        ),
        overallStatus: { total: 5, alive: 4, dead: 1 },
      },
      IDS[1],
    );

    expect(result?.publicRoles).toContainEqual({ playerId: IDS[1], role: 'DEPUTY' });
    expect(result?.overallStatus).toEqual({ total: 5, alive: 4, dead: 1 });
  });

  it('requires every assignment to be public in REVEAL', () => {
    const result = normalizeSheriffView(
      {
        ...playing('RENEGADE'),
        phase: 'REVEAL',
        publicRoles: [
          { playerId: IDS[0], role: 'SHERIFF' },
          { playerId: IDS[1], role: 'DEPUTY' },
          { playerId: IDS[2], role: 'OUTLAW' },
          { playerId: IDS[3], role: 'OUTLAW' },
          { playerId: IDS[4], role: 'RENEGADE' },
        ],
      },
      IDS[4],
    );

    expect(result?.phase).toBe('REVEAL');
    expect(result?.publicRoles).toHaveLength(5);
  });

  it('rejects malformed phases, IDs, rosters, roles and count invariants', () => {
    expect(normalizeSheriffView({ ...playing(), phase: 'ENDED' }, IDS[2])).toBeNull();
    expect(
      normalizeSheriffView(
        { ...playing(), participants: [{ playerId: 'bad', lifeStatus: 'ALIVE' }] },
        IDS[2],
      ),
    ).toBeNull();
    expect(
      normalizeSheriffView(
        { ...playing(), participants: [...participants.slice(0, 4), participants[0]] },
        IDS[2],
      ),
    ).toBeNull();
    expect(normalizeSheriffView({ ...playing(), ownRole: 'BYSTANDER' }, IDS[2])).toBeNull();
    expect(
      normalizeSheriffView(
        { ...playing(), overallStatus: { total: 5, alive: 4, dead: 0 } },
        IDS[2],
      ),
    ).toBeNull();
    expect(
      normalizeSheriffView(
        {
          ...playing(),
          roleStatus: roleStatus.map((item) =>
            item.role === 'OUTLAW' ? { ...item, assigned: 3, alive: 3 } : item,
          ),
        },
        IDS[2],
      ),
    ).toBeNull();
    expect(
      normalizeSheriffView(
        {
          ...playing('OUTLAW'),
          phase: 'REVEAL',
          publicRoles: IDS.map((playerId, index) => ({
            playerId,
            role: index === 0 ? 'SHERIFF' : 'OUTLAW',
          })),
        },
        IDS[2],
      ),
    ).toBeNull();
  });

  it('rejects role leaks, missing public deaths and missing participant private roles', () => {
    expect(
      normalizeSheriffView(
        {
          ...playing(),
          publicRoles: [
            { playerId: IDS[0], role: 'SHERIFF' },
            { playerId: IDS[1], role: 'DEPUTY' },
          ],
        },
        IDS[2],
      ),
    ).toBeNull();
    expect(normalizeSheriffView(playing(null), IDS[2])).toBeNull();
    expect(
      normalizeSheriffView(
        {
          ...playing(),
          participants: participants.map((item, index) =>
            index === 1 ? { ...item, lifeStatus: 'DEAD' } : item,
          ),
          roleStatus: roleStatus.map((item) =>
            item.role === 'DEPUTY' ? { ...item, alive: 0, dead: 1 } : item,
          ),
          overallStatus: { total: 5, alive: 4, dead: 1 },
        },
        IDS[2],
      ),
    ).toBeNull();
  });
});
