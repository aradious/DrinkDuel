import { describe, expect, it } from 'vitest';
import { SheriffPlaying } from './pages/sheriff-playing';
import { SheriffReveal } from './pages/sheriff-reveal';
import { routes } from './app.routes';

describe('application routes', () => {
  it('keeps existing routes and registers every Sheriff phase route', () => {
    const paths = routes.map((route) => route.path);

    expect(paths).toContain('room/:id');
    expect(paths).toContain('room/:id/liars-dice/start');
    expect(paths).toContain('room/:id/liars-dice/playing');
    expect(paths).toContain('room/:id/liars-dice/reveal');
    expect(paths).toContain('room/:id/sheriff/start');
    expect(paths).toContain('room/:id/sheriff/playing');
    expect(paths).toContain('room/:id/sheriff/reveal');
  });

  it('lazy-loads the complete Sheriff PLAYING screen', async () => {
    const route = routes.find((candidate) => candidate.path === 'room/:id/sheriff/playing');
    const component = await route?.loadComponent?.();

    expect(component).toBe(SheriffPlaying);
  });

  it('lazy-loads the complete Sheriff REVEAL screen', async () => {
    const route = routes.find((candidate) => candidate.path === 'room/:id/sheriff/reveal');
    const component = await route?.loadComponent?.();

    expect(component).toBe(SheriffReveal);
  });
});
