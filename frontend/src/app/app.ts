import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  standalone: true,
  template: `<div class="site-shell">
    <main><router-outlet /></main>
    <footer>GOOD COMPANY, GREAT STORIES.</footer>
  </div>`,
})
export class App {}
