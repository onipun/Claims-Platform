import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ClaimActions } from './claim-actions';
import { ConfirmService } from '../../shared/ui';
import { ClaimWorkspacePage } from './claim-workspace.page';
import { CLAIM_ID, detail, flush, setupStaffTest } from './testing';

const BASE = `/api/v1/staff/claims/${CLAIM_ID}`;

describe('ClaimActions: confirmations and unknown outcomes', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<ClaimWorkspacePage>;
  let el: HTMLElement;
  let onConfirm: () => void = () => undefined;

  async function open(allowed = ['REJECT', 'ASSIGN']) {
    ctl = setupStaffTest('OFFICER', ['SG'], {
      providers: [{ provide: ConfirmService, useValue: { confirm: async () => (onConfirm(), true) } }],
    });
    fixture = TestBed.createComponent(ClaimWorkspacePage);
    fixture.componentRef.setInput('id', CLAIM_ID);
    el = fixture.nativeElement;
    fixture.detectChanges();
    await Promise.resolve();
    ctl.expectOne(BASE).flush(detail({ allowedActions: allowed }), { headers: { ETag: '"3"' } });
    await flush(fixture);
    ctl.expectOne(`${BASE}/financials`).flush({ entries: [] });
    ctl.expectOne(`${BASE}/documents`).flush({ items: [], other: [], outstanding: 0 });
    await flush(fixture);
  }
  const type = async (sel: string, v: string) => {
    const i = el.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(sel)!;
    i.value = v;
    i.dispatchEvent(new Event(i instanceof HTMLSelectElement ? 'change' : 'input'));
    await flush(fixture);
  };
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
    onConfirm = () => undefined;
  });

  async function startReject() {
    el.querySelector<HTMLElement>('[data-action=REJECT]')!.click();
    await flush(fixture);
    await type('select', 'NOT_COVERED');
    await type('textarea', 'Excluded under section 4');
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await flush(fixture);
  }

  it('sends the ETag the officer saw before the confirm dialog, even if the claim moves on while it is open', async () => {
    await open();
    // While the dialog is open the page adopts a newer version.
    onConfirm = () => (fixture.componentInstance as unknown as { etag: { set(v: string): void } }).etag.set('"9"');
    await startReject();
    const req = ctl.expectOne((r) => r.method === 'POST' && r.url === `${BASE}/rejection`);
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush(detail({ version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.match(`${BASE}/financials`).forEach((r) => r.flush({ entries: [] }));
    ctl.match(`${BASE}/documents`).forEach((r) => r.flush({}));
  });

  it('is busy while the confirm dialog is open', async () => {
    await open();
    let busyDuring: boolean | null = null;
    onConfirm = () => (busyDuring = (fixture.debugElement.query(By.directive(ClaimActions)).componentInstance as unknown as { busy(): boolean }).busy());
    await startReject();
    ctl.expectOne((r) => r.method === 'POST').flush(detail({ version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.match(`${BASE}/financials`).forEach((r) => r.flush({ entries: [] }));
    ctl.match(`${BASE}/documents`).forEach((r) => r.flush({}));
    expect(busyDuring).toBe(true);
  });

  it('says it is unknown whether a command was saved when the network drops or the server fails', async () => {
    await open();
    await startReject();
    ctl.expectOne((r) => r.method === 'POST').error(new ProgressEvent('error'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=notice-unknown]')!.textContent).toContain('Unknown whether this was saved. Reload to check.');
    expect(el.querySelector('[data-testid=notice-unknown]')!.textContent).toContain('Reload claim');
  });

  it('R4: the "Nothing was saved. Reload" notice goes when a reload puts a new claim on screen, and the typed draft stays', async () => {
    await open();
    await startReject();
    ctl.expectOne((r) => r.method === 'POST').flush({ status: 409, code: 'STALE_VERSION', title: 'Stale' }, { status: 409, statusText: 'Conflict' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=notice-stale]')).not.toBeNull();

    void (fixture.componentInstance as unknown as { load(): Promise<void> }).load();
    await Promise.resolve();
    ctl.expectOne(BASE).flush(detail({ allowedActions: ['REJECT', 'ASSIGN'], version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.match(`${BASE}/financials`).forEach((r) => r.flush({ entries: [] }));
    ctl.match(`${BASE}/documents`).forEach((r) => r.flush({ items: [], other: [], outstanding: 0 }));
    await flush(fixture);

    expect(el.querySelector('[data-testid=notice-stale]')).toBeNull();
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Excluded under section 4');
  });

  /** Puts a newer claim on screen, as a reload does. */
  async function reloadClaim() {
    void (fixture.componentInstance as unknown as { load(): Promise<void> }).load();
    await Promise.resolve();
    ctl.expectOne(BASE).flush(detail({ allowedActions: ['REJECT', 'ASSIGN'], version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.match(`${BASE}/financials`).forEach((r) => r.flush({ entries: [] }));
    ctl.match(`${BASE}/documents`).forEach((r) => r.flush({ items: [], other: [], outstanding: 0 }));
    await flush(fixture);
  }

  it('D-4: after an ETag change the "unknown" notice clears and the draft stays', async () => {
    await open();
    await startReject();
    ctl.expectOne((r) => r.method === 'POST').error(new ProgressEvent('error'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=notice-unknown]')).not.toBeNull();
    await reloadClaim();
    expect(el.querySelector('[data-testid=notice-unknown]')).toBeNull();
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Excluded under section 4');
  });

  it.each([
    ['authority', 'AUTHORITY_EXCEEDED', 422],
    ['conflict', 'CONFLICT_OF_INTEREST', 403],
  ])('D-4: the "%s" notice survives an ETag change, because it is still true after a reload', async (kind, code, status) => {
    await open();
    await startReject();
    ctl.expectOne((r) => r.method === 'POST').flush({ status, code, title: 'No' }, { status, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector(`[data-testid=notice-${kind}]`)).not.toBeNull();
    await reloadClaim();
    expect(el.querySelector(`[data-testid=notice-${kind}]`)).not.toBeNull();
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Excluded under section 4');
  });

  it('explains why the officer list could not load, instead of leaving "Loading officers…"', async () => {
    await open();
    el.querySelector<HTMLElement>('[data-action=ASSIGN]')!.click();
    await flush(fixture);
    ctl.expectOne('/api/v1/staff/officers').flush({ status: 403, code: 'FORBIDDEN', title: 'Forbidden' }, { status: 403, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=officers-error]')!.textContent).toContain('not allowed');
    expect(el.textContent).not.toContain('Loading officers');
  });
});
