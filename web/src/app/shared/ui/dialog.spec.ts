import { TestBed } from '@angular/core/testing';
import { ConfirmService } from './dialog';

const settle = () => new Promise((r) => setTimeout(r, 60));

describe('ConfirmService dialog', () => {
  let svc: ConfirmService;
  // jsdom does no layout, so CDK's visibility check (offsetWidth/Height) rejects every element and focus
  // falls back to the container. Give elements a size for this spec; the real-browser run covers layout.
  const sized = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => 10 });
    svc = TestBed.inject(ConfirmService);
  });
  afterEach(() => {
    if (sized) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', sized);
    document.querySelectorAll('.cdk-overlay-container').forEach((n) => n.remove());
  });

  const open = async (destructive: boolean) => {
    const result = svc.confirm({ title: 'Reject CLM-1?', message: 'This is final.', confirmLabel: 'Reject claim', destructive });
    await settle();
    return result;
  };
  const dialog = () => document.querySelector<HTMLElement>('[role=alertdialog]')!;
  const byText = (t: string) => [...document.querySelectorAll<HTMLButtonElement>('.cdk-dialog-container button')].find((b) => b.textContent?.trim() === t)!;

  it('is named and described by elements that exist', async () => {
    void open(true);
    await settle();
    const d = dialog();
    const name = document.getElementById(d.getAttribute('aria-labelledby')!);
    const desc = document.getElementById(d.getAttribute('aria-describedby')!);
    expect(name?.textContent).toContain('Reject CLM-1?');
    expect(desc?.textContent).toContain('This is final.');
    expect(d.getAttribute('aria-modal')).toBe('true');
  });

  it('starts focus on Cancel for a destructive dialog', async () => {
    void open(true);
    await settle();
    expect(document.activeElement).toBe(byText('Cancel'));
  });

  it('starts focus on the confirm button for a non-destructive dialog', async () => {
    void open(false);
    await settle();
    expect(document.activeElement).toBe(byText('Reject claim'));
  });

  it('keeps the panel chrome on the shell, not on the CDK pane, and caps its width inline', async () => {
    void open(true);
    await settle();
    const pane = document.querySelector<HTMLElement>('.cdk-overlay-pane')!;
    expect(pane.style.maxWidth).toBe('32rem');
    const shell = document.querySelector<HTMLElement>('ui-dialog-shell')!;
    expect(shell.className).toContain('relative');
    expect(shell.className).toContain('bg-background');
    expect(pane.className).not.toContain('bg-background');
  });

  it('close button is at least 24px (h-8 w-8) and resolves false', async () => {
    const p = open(true);
    await settle();
    const close = [...document.querySelectorAll<HTMLButtonElement>('.cdk-dialog-container button')].find((b) => b.textContent?.includes('Close'))!;
    expect(close.className).toContain('h-8');
    expect(close.className).toContain('w-8');
    close.click();
    expect(await p).toBe(false);
  });
});
