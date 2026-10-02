import catalog from '../../assets/drinkduel/avatars/catalog.json';

/** Stable server IDs map to filenames here; never assign or reroll in the UI. */
export const AVATAR_CATALOG: Readonly<Record<string, string>> = Object.freeze(catalog);

export function avatarSource(id: number): string | null {
  const filename = AVATAR_CATALOG[String(id)];
  return filename ? `assets/drinkduel/avatars/${filename}` : null;
}
