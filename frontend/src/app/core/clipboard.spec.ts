import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard';

describe('copyText', () => {
  afterEach(() => {
    document.querySelectorAll('textarea[aria-hidden="true"]').forEach((node) => node.remove());
    vi.restoreAllMocks();
  });

  it('uses Clipboard API with the exact room code when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    await expect(copyText('ABC234', { writeText })).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith('ABC234');
  });

  it('falls back without throwing and cleans temporary DOM state', async () => {
    const focused = document.createElement('button');
    document.body.appendChild(focused);
    focused.focus();
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand });

    await expect(copyText('ABC234', undefined)).resolves.toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
    expect(document.querySelector('textarea[aria-hidden="true"]')).toBeNull();
    expect(document.activeElement).toBe(focused);
    focused.remove();
  });

  it('attempts the DOM fallback when Clipboard API rejects', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('not allowed'));
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, 'execCommand', { configurable: true, value: execCommand });
    await expect(copyText('ZXCV12', { writeText })).resolves.toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
    expect(document.querySelector('textarea[aria-hidden="true"]')).toBeNull();
  });

  it('fails gracefully when neither copy mechanism is available', async () => {
    Object.defineProperty(document, 'execCommand', { configurable: true, value: undefined });
    await expect(copyText('ABC234', undefined)).resolves.toBe(false);
    expect(document.querySelector('textarea[aria-hidden="true"]')).toBeNull();
  });
});
