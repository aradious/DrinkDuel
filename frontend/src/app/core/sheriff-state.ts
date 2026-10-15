import {
  SheriffGameView,
  SheriffOverallStatusView,
  SheriffParticipantView,
  SheriffPlayerStatus,
  SheriffPublicRoleView,
  SheriffRole,
  SheriffRoleStatusView,
} from './room.models';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ROLES: readonly SheriffRole[] = ['SHERIFF', 'DEPUTY', 'OUTLAW', 'RENEGADE'];
const LIFE_STATUSES: readonly SheriffPlayerStatus[] = ['ALIVE', 'DEAD'];

type UnknownRecord = Record<string, unknown>;

const record = (value: unknown): UnknownRecord | null =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as UnknownRecord)
    : null;

const uuid = (value: unknown): string | null =>
  typeof value === 'string' && UUID.test(value) ? value : null;

const role = (value: unknown): SheriffRole | null =>
  typeof value === 'string' && ROLES.includes(value as SheriffRole) ? (value as SheriffRole) : null;

const lifeStatus = (value: unknown): SheriffPlayerStatus | null =>
  typeof value === 'string' && LIFE_STATUSES.includes(value as SheriffPlayerStatus)
    ? (value as SheriffPlayerStatus)
    : null;

const count = (value: unknown): number | null =>
  Number.isInteger(value) && Number(value) >= 0 ? Number(value) : null;

const empty = (value: unknown): boolean => Array.isArray(value) && value.length === 0;

function participants(value: unknown): SheriffParticipantView[] | null {
  if (!Array.isArray(value) || value.length < 4 || value.length > 20) return null;
  const parsed = value.map((candidate) => {
    const source = record(candidate);
    const playerId = uuid(source?.['playerId']);
    const status = lifeStatus(source?.['lifeStatus']);
    return playerId && status ? { playerId, lifeStatus: status } : null;
  });
  if (parsed.some((item) => item === null)) return null;
  const result = parsed as SheriffParticipantView[];
  return new Set(result.map((item) => item.playerId)).size === result.length ? result : null;
}

function publicRoles(
  value: unknown,
  roster: SheriffParticipantView[],
): SheriffPublicRoleView[] | null {
  if (!Array.isArray(value)) return null;
  const rosterIds = new Set(roster.map((item) => item.playerId));
  const parsed = value.map((candidate) => {
    const source = record(candidate);
    const playerId = uuid(source?.['playerId']);
    const parsedRole = role(source?.['role']);
    return playerId && parsedRole && rosterIds.has(playerId)
      ? { playerId, role: parsedRole }
      : null;
  });
  if (parsed.some((item) => item === null)) return null;
  const result = parsed as SheriffPublicRoleView[];
  return new Set(result.map((item) => item.playerId)).size === result.length ? result : null;
}

function roleStatus(value: unknown, playerCount: number): SheriffRoleStatusView[] | null {
  if (!Array.isArray(value) || value.length !== ROLES.length) return null;
  const parsed = value.map((candidate) => {
    const source = record(candidate);
    const parsedRole = role(source?.['role']);
    const assigned = count(source?.['assigned']);
    const alive = count(source?.['alive']);
    const dead = count(source?.['dead']);
    return parsedRole &&
      assigned !== null &&
      alive !== null &&
      dead !== null &&
      assigned === alive + dead
      ? { role: parsedRole, assigned, alive, dead }
      : null;
  });
  if (parsed.some((item) => item === null)) return null;
  const result = parsed as SheriffRoleStatusView[];
  if (new Set(result.map((item) => item.role)).size !== ROLES.length) return null;
  result.sort((left, right) => ROLES.indexOf(left.role) - ROLES.indexOf(right.role));
  const expected = [
    1,
    Math.floor(playerCount / 2) - 1,
    Math.floor(playerCount / 2),
    playerCount % 2,
  ];
  return result.every(
    (item, index) => item.role === ROLES[index] && item.assigned === expected[index],
  )
    ? result
    : null;
}

function overallStatus(value: unknown): SheriffOverallStatusView | null {
  const source = record(value);
  const total = count(source?.['total']);
  const alive = count(source?.['alive']);
  const dead = count(source?.['dead']);
  return total !== null && alive !== null && dead !== null && total === alive + dead
    ? { total, alive, dead }
    : null;
}

function validCounts(
  roster: SheriffParticipantView[],
  statuses: SheriffRoleStatusView[],
  overall: SheriffOverallStatusView,
  revealed: SheriffPublicRoleView[],
): boolean {
  const total = (key: 'assigned' | 'alive' | 'dead') =>
    statuses.reduce((sum, item) => sum + item[key], 0);
  if (
    overall.total !== roster.length ||
    overall.alive !== roster.filter((item) => item.lifeStatus === 'ALIVE').length ||
    overall.dead !== roster.filter((item) => item.lifeStatus === 'DEAD').length ||
    total('assigned') !== overall.total ||
    total('alive') !== overall.alive ||
    total('dead') !== overall.dead
  )
    return false;
  return ROLES.every((candidate) => {
    const publicDead = revealed.filter((item) => {
      const participant = roster.find((entry) => entry.playerId === item.playerId);
      return participant?.lifeStatus === 'DEAD' && item.role === candidate;
    }).length;
    return statuses.find((item) => item.role === candidate)?.dead === publicDead;
  });
}

/** Converts one untrusted wire Sheriff view into a phase-safe recipient view. */
export function normalizeSheriffView(
  value: unknown,
  recipientPlayerId?: string,
): SheriffGameView | null {
  const source = record(value);
  if (!source || source['gameType'] !== 'SHERIFF') return null;

  if (source['phase'] === 'START') {
    if (
      !empty(source['participants']) ||
      source['sheriffPlayerId'] !== null ||
      source['ownRole'] !== null ||
      !empty(source['publicRoles']) ||
      !empty(source['roleStatus']) ||
      source['overallStatus'] !== null
    )
      return null;
    return {
      gameType: 'SHERIFF',
      phase: 'START',
      participants: [],
      sheriffPlayerId: null,
      ownRole: null,
      publicRoles: [],
      roleStatus: [],
      overallStatus: null,
    };
  }

  if (source['phase'] !== 'PLAYING' && source['phase'] !== 'REVEAL') return null;
  const roster = participants(source['participants']);
  const sheriffPlayerId = uuid(source['sheriffPlayerId']);
  const ownRole = source['ownRole'] === null ? null : role(source['ownRole']);
  if (!roster || !sheriffPlayerId || (source['ownRole'] !== null && ownRole === null)) return null;
  const revealed = publicRoles(source['publicRoles'], roster);
  const statuses = roleStatus(source['roleStatus'], roster.length);
  const overall = overallStatus(source['overallStatus']);
  if (!revealed || !statuses || !overall || !validCounts(roster, statuses, overall, revealed))
    return null;

  const rosterIds = new Set(roster.map((item) => item.playerId));
  const recipientParticipates = Boolean(recipientPlayerId && rosterIds.has(recipientPlayerId));
  if (recipientParticipates !== Boolean(ownRole)) return null;
  const publicByPlayer = new Map(revealed.map((item) => [item.playerId, item.role]));
  if (publicByPlayer.get(sheriffPlayerId) !== 'SHERIFF') return null;
  if (
    roster.some((participant) => {
      const publicRole = publicByPlayer.get(participant.playerId);
      const shouldBePublic =
        source['phase'] === 'REVEAL' ||
        participant.playerId === sheriffPlayerId ||
        participant.lifeStatus === 'DEAD';
      return shouldBePublic !== Boolean(publicRole);
    })
  )
    return null;
  if (
    recipientPlayerId &&
    ownRole &&
    publicByPlayer.has(recipientPlayerId) &&
    publicByPlayer.get(recipientPlayerId) !== ownRole
  )
    return null;
  if (
    source['phase'] === 'REVEAL' &&
    (revealed.length !== roster.length ||
      ROLES.some(
        (candidate) =>
          revealed.filter((item) => item.role === candidate).length !==
          statuses.find((item) => item.role === candidate)?.assigned,
      ))
  )
    return null;

  return {
    gameType: 'SHERIFF',
    phase: source['phase'],
    participants: roster,
    sheriffPlayerId,
    ownRole,
    publicRoles: revealed,
    roleStatus: statuses,
    overallStatus: overall,
  };
}
