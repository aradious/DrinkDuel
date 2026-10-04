type ClipboardWriter = Pick<Clipboard, 'writeText'>;

export async function copyText(
  text: string,
  clipboard: ClipboardWriter | undefined = navigator.clipboard,
  documentRef: Document = document,
): Promise<boolean> {
  if (typeof clipboard?.writeText === 'function') {
    try {
      await clipboard.writeText(text);
      return true;
    } catch {
      // Some browsers expose Clipboard API but reject it outside a secure context.
    }
  }

  const activeElement = documentRef.activeElement;
  const temporary = documentRef.createElement('textarea');
  temporary.value = text;
  temporary.readOnly = true;
  temporary.setAttribute('aria-hidden', 'true');
  temporary.style.position = 'fixed';
  temporary.style.opacity = '0';
  temporary.style.pointerEvents = 'none';
  documentRef.body.appendChild(temporary);
  try {
    temporary.focus();
    temporary.select();
    return typeof documentRef.execCommand === 'function' && documentRef.execCommand('copy');
  } catch {
    return false;
  } finally {
    temporary.remove();
    if (activeElement instanceof HTMLElement) activeElement.focus();
  }
}
