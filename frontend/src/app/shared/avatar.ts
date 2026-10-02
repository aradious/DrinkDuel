import { Component, input } from '@angular/core';
import { avatarSource } from './avatar-catalog';
@Component({
  selector: 'dd-avatar',
  template: `<span class="avatar" aria-hidden="true">
    @if (source(id()); as src) {
      <img [src]="src" alt="" width="70" height="76" loading="lazy" decoding="async" />
    } @else {
      <span>?</span>
    }
  </span>`,
  styles: `
    :host {
      display: block;
      flex: 0 0 auto;
    }
    .avatar {
      display: grid;
      place-items: center;
      width: 70px;
      height: 76px;
      border-radius: 22px;
      background: #eee5ff;
      overflow: hidden;
    }
    img {
      display: block;
      width: 100%;
      height: 100%;
      object-fit: contain;
    }
  `,
})
export class Avatar {
  readonly id = input(1);
  readonly source = avatarSource;
}
