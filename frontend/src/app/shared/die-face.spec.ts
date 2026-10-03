import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { DieValue } from '../core/room.models';
import { DieFace } from './die-face';

@Component({
  imports: [DieFace],
  template: `<dd-die-face [value]="value()" />`,
})
class Host {
  readonly value = signal<DieValue>(1);
}

describe('DieFace', () => {
  it.each([1, 2, 3, 4, 5, 6] as DieValue[])(
    'maps authoritative face %i to its approved PNG',
    (face) => {
      const fixture = TestBed.createComponent(Host);
      fixture.componentInstance.value.set(face);
      fixture.detectChanges();
      const die = (fixture.nativeElement as HTMLElement).querySelector('dd-die-face')!;
      const image = die.querySelector('img.die-art')!;
      expect(die.getAttribute('data-face')).toBe(String(face));
      expect(image.getAttribute('src')).toBe(
        `/assets/drinkduel/games/liars-dice/dice/dice-${face}.png`,
      );
      expect(image.getAttribute('aria-label')).toBe(`Die showing ${face}`);
      expect(image.getAttribute('alt')).toBe('');
    },
  );
});
