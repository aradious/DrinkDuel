import { describe, expect, it, vi } from 'vitest';
import {
  createRequestId,
  joinRoomUrl,
  normalizeBasePath,
  publicPath,
  webSocketUrl,
} from './browser';

describe('createRequestId', () => {
  it('uses crypto.randomUUID when it is available', () => {
    const randomUUID = vi.fn(
      () =>
        '11111111-1111-4111-8111-111111111111' as `${string}-${string}-${string}-${string}-${string}`,
    );
    const id = createRequestId({ randomUUID, getRandomValues: vi.fn() } as unknown as Crypto);
    expect(id).toBe('11111111-1111-4111-8111-111111111111');
    expect(randomUUID).toHaveBeenCalledOnce();
  });

  it('uses random bytes without throwing when randomUUID is unavailable', () => {
    let next = 0;
    const getRandomValues = vi.fn((array: Uint8Array) => {
      array.fill(next++);
      return array;
    });
    const cryptoApi = { getRandomValues } as unknown as Crypto;
    const first = createRequestId(cryptoApi);
    const second = createRequestId(cryptoApi);
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(second).not.toBe(first);
    expect(getRandomValues).toHaveBeenCalledTimes(2);
  });

  it('retains a non-throwing correlation-only fallback without Web Crypto', () => {
    expect(createRequestId(null)).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('deployment base paths', () => {
  it('keeps root deployment URLs unchanged', () => {
    expect(normalizeBasePath('/')).toBe('/');
    expect(publicPath('/', 'api/rooms')).toBe('/api/rooms');
    expect(webSocketUrl('http://localhost:8080', '/', 'ws/rooms')).toBe(
      'ws://localhost:8080/ws/rooms',
    );
  });

  it('prefixes REST and WebSocket paths for a subpath deployment', () => {
    expect(normalizeBasePath('/drinkduel')).toBe('/drinkduel/');
    expect(publicPath('/drinkduel/', '/api/rooms')).toBe('/drinkduel/api/rooms');
    expect(webSocketUrl('https://example.com', '/drinkduel/', '/ws/rooms')).toBe(
      'wss://example.com/drinkduel/ws/rooms',
    );
  });

  it('builds root and subpath join URLs from the supplied browser origin', () => {
    expect(joinRoomUrl('https://root.example', '/', 'ABC123')).toBe(
      'https://root.example/join?room=ABC123',
    );
    expect(joinRoomUrl('https://party.example', '/drinkduel/', 'XYZ789')).toBe(
      'https://party.example/drinkduel/join?room=XYZ789',
    );
  });
});
