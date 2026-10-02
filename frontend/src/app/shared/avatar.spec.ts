import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Avatar } from './avatar';
import { AVATAR_CATALOG, avatarSource } from './avatar-catalog';

describe('avatar artwork', () => {
  it('maps stable IDs to the supplied files including the PNG exception', () => {
    expect(Object.keys(AVATAR_CATALOG)).toHaveLength(30);
    expect(avatarSource(4)).toBe('assets/drinkduel/avatars/avatar-04.png');
    expect(avatarSource(30)).toBe('assets/drinkduel/avatars/avatar-30.webp');
    expect(avatarSource(0)).toBeNull();
  });
  it('renders the assigned image without rerandomizing on repeated renders', () => {
    const fixture = TestBed.createComponent(Avatar);
    fixture.componentRef.setInput('id', 4);
    fixture.detectChanges();
    const image = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(avatarSource(4));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBe(image);
    expect(image.getAttribute('src')).toBe(avatarSource(4));
    fixture.componentRef.setInput('id', 30);
    fixture.detectChanges();
    expect(image.getAttribute('src')).toBe(avatarSource(30));
  });
});
