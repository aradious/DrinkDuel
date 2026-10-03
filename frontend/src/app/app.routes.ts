import { Routes } from '@angular/router';
export const routes: Routes = [
  { path: '', loadComponent: () => import('./pages/home').then((m) => m.Home) },
  { path: 'create', loadComponent: () => import('./pages/create').then((m) => m.Create) },
  { path: 'join', loadComponent: () => import('./pages/join').then((m) => m.Join) },
  {
    path: 'room/:id/games',
    loadComponent: () => import('./pages/choose-game').then((m) => m.ChooseGame),
  },
  {
    path: 'room/:id/liars-dice/start',
    loadComponent: () => import('./pages/liars-dice-start').then((m) => m.LiarsDiceStart),
  },
  {
    path: 'room/:id/liars-dice/playing',
    loadComponent: () =>
      import('./pages/liars-dice-playing').then((m) => m.LiarsDicePlaying),
  },
  {
    path: 'room/:id/liars-dice/reveal',
    loadComponent: () => import('./pages/liars-dice-reveal').then((m) => m.LiarsDiceReveal),
  },
  { path: 'room/:id', loadComponent: () => import('./pages/lobby').then((m) => m.Lobby) },
  { path: '**', redirectTo: '' },
];
