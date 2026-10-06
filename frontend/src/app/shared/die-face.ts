import { Component, computed, input } from '@angular/core';
import { DieValue } from '../core/room.models';

const DICE_ASSETS: Record<DieValue, string> = {
  1: 'assets/drinkduel/games/liars-dice/dice/dice-1.png',
  2: 'assets/drinkduel/games/liars-dice/dice/dice-2.png',
  3: 'assets/drinkduel/games/liars-dice/dice/dice-3.png',
  4: 'assets/drinkduel/games/liars-dice/dice/dice-4.png',
  5: 'assets/drinkduel/games/liars-dice/dice/dice-5.png',
  6: 'assets/drinkduel/games/liars-dice/dice/dice-6.png',
};

@Component({
  selector: 'dd-die-face',
  host: {
    '[attr.data-face]': 'value()',
  },
  template: `
    <img
      class="die-art"
      [src]="source()"
      alt=""
      role="img"
      [attr.aria-label]="label()"
      width="1254"
      height="1254"
      draggable="false"
    />
  `,
  styles: `
    :host {
      display: block;
      width: clamp(48px, 10vw, 82px);
      aspect-ratio: 1;
      filter: drop-shadow(0 8px 7px rgb(20 5 24 / 0.42));
    }
    .die-art {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: contain;
      pointer-events: none;
      user-select: none;
      -webkit-user-drag: none;
    }
  `,
})
export class DieFace {
  readonly value = input.required<DieValue>();
  readonly source = computed(() => DICE_ASSETS[this.value()]);
  readonly label = computed(() => `Die showing ${this.value()}`);
}
