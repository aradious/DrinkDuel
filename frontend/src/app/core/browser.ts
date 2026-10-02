import { InjectionToken } from '@angular/core';
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
    requestId: () => crypto.randomUUID(),
  }),
});
