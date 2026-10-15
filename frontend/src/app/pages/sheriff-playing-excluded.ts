import { Component } from '@angular/core';

@Component({
  selector: 'dd-sheriff-playing-excluded',
  styleUrl: './sheriff-playing-excluded.scss',
  template: `
    <div class="excluded-role" role="status">
      <span aria-hidden="true">◇</span>
      <h3>Watching this round</h3>
      <p>You weren't in the locked roster, so no private role was assigned.</p>
    </div>
  `,
})
export class SheriffPlayingExcluded {}
