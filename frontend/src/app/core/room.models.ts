export interface PlayerView {
  playerId: string;
  nickname: string;
  avatarId: number;
  connectionStatus: 'CONNECTED' | 'DISCONNECTED';
}
export interface WhoAmIParticipantView {
  playerId: string;
  submitted: boolean;
  resetSubmissionId: string | null;
}
export interface WhoAmIGameCardView {
  playerId: string;
  nickname: string;
  avatarId: number;
  connectionStatus: 'CONNECTED' | 'DISCONNECTED';
  gameStatus: 'PLAYING' | 'GOT_IT';
  statusVersion: number;
  assignedName: string | null;
}
export interface WhoAmIRoastView {
  kind: 'LAST_ONE';
  playingPlayerIds: string[];
}
export interface WhoAmIRevealCardView {
  playerId: string;
  nickname: string;
  avatarId: number;
  gameStatus: 'PLAYING' | 'GOT_IT';
  assignedName: string;
  createdBy: string;
}
export interface WhoAmIGameView {
  gameType: 'WHO_AM_I';
  phase: 'SUBMIT_NAME' | 'PLAYING' | 'ROAST' | 'REVEAL';
  participants: WhoAmIParticipantView[];
  submittedCount: number;
  participantCount: number;
  currentPlayerSubmitted: boolean;
  readyForShuffle: boolean;
  canShuffle: boolean;
  cards: WhoAmIGameCardView[];
  roast: WhoAmIRoastView | null;
  reveal: WhoAmIRevealCardView[];
}
export interface RoomView {
  roomId: string;
  roomRevision: number;
  expiresAt: string;
  sessionId: string | null;
  lifecycle: 'LOBBY' | 'IN_GAME';
  currentPlayerId: string;
  gmPlayerId: string;
  isGm: boolean;
  capacity: number;
  joinable: boolean;
  allowedActions: string[];
  players: PlayerView[];
  game?: WhoAmIGameView | null;
}
export type Role = 'guest' | 'gm';
export interface Attachment {
  roomId: string;
  role: Role;
  nickname?: string;
}
export const roomCode = (text: string) => text.trim().toUpperCase();
export const validCode = (text: string) => /^[A-HJ-NP-Z2-9]{6}$/.test(roomCode(text));
export const validNickname = (text: string) => text.trim().length > 0;
export function friendlyError(code: string): string {
  const errors: Record<string, string> = {
    ROOM_NOT_FOUND: 'That room is no longer here. Check the code with your crew.',
    ROOM_EXPIRED: 'Party’s over! 🍻💤 This room has expired. ถึงเวลาตั้งวงใหม่แล้ว',
    ROOM_FULL: 'The crew is full! This room has space for 20 friends.',
    NICKNAME_TAKEN: 'That nickname is already in the crew. Try another one.',
    INVALID_INPUT: 'Check your room code and nickname, then try again.',
    GAME_IN_PROGRESS:
      'They’re playing right now. Ask the Game Master to return to the Lobby before you join.',
    PLAYER_KICKED: 'The Game Master removed you from this room. You can’t rejoin this room.',
    NOT_AUTHORIZED:
      'We couldn’t restore your place in this room. Check that you’re using the same browser.',
    STALE_COMMAND: 'The room changed. We’ve refreshed it for you.',
    REPLACED: 'Your room is open in another tab. Continue there, or reconnect here.',
  };
  return errors[code] ?? 'Something went a little sideways. Please try again.';
}
