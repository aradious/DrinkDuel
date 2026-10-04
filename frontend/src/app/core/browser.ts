import { InjectionToken } from '@angular/core';

type RequestCrypto = Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>>;
let fallbackSequence = 0;

export function createRequestId(cryptoApi: RequestCrypto | null | undefined = globalThis.crypto): string {
  if (typeof cryptoApi?.randomUUID === 'function') {
    try {
      return cryptoApi.randomUUID();
    } catch {
      // Continue with browser-safe request-correlation fallbacks.
    }
  }

  const bytes = new Uint8Array(16);
  if (typeof cryptoApi?.getRandomValues === 'function') {
    cryptoApi.getRandomValues(bytes);
  } else {
    fallbackSequence = (fallbackSequence + 1) >>> 0;
    const seed = `${Date.now()}-${fallbackSequence}-${Math.random()}`;
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = (seed.charCodeAt(index % seed.length) + index * 29) & 0xff;
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export interface BrowserPort {
  storage: Storage;
  socket: (url: string) => WebSocket;
  request: typeof fetch;
  origin: string;
  randomToken: () => string;
  requestId: () => string;
}
export const BROWSER = new InjectionToken<BrowserPort>('browser', {
  providedIn: 'root',
  factory: () => ({
    storage: localStorage,
    socket: (url) => new WebSocket(url),
    request: window.fetch.bind(window),
    origin: window.location.origin,
    randomToken: () =>
      btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
        .replaceAll('+', '-')
        .replaceAll('/', '_')
        .replaceAll('=', ''),
    requestId: () => createRequestId(window.crypto),
  }),
});
