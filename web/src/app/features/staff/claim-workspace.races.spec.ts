import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClaimWorkspacePage } from './claim-workspace.page';
import { CLAIM_ID, detail, flush, setupStaffTest } from './testing';

const A = CLAIM_ID;
const B = '22222222-2222-2222-2222-222222222222';
const url = (id: string, tail = '') => `/api/v1/staff/claims/${id}${tail}`;

interface Internals {
  load(): Promise<void>;
  etag(): string | null;
  claim(): { id?: string; claimNumber?: string } | null;
  reloadError(): unknown;
}

describe('ClaimWorkspacePage: navigation between claims', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<ClaimWorkspacePage>;
  let el: HTMLElement;
  let page: Internals;

  const goto = async (id: string) => {
    fixture.componentRef.setInput('id', id);
    await flush(fixture);
  };
  const side = async (id: string) => {
    ctl.expectOne(url(id, '/financials')).flush({ currency: 'SGD', entries: [] });
    ctl.expectOne(url(id, '/documents')).flush({ items: [], other: [], outstanding: 0 });
    await flush(fixture);
  };
  const post = (id: string, path: string): TestRequest => ctl.expectOne((r) => r.method === 'POST' && r.url === url(id, `/${path}`));

  beforeEach(() => {
    ctl = setupStaffTest('OFFICER', ['SG']);
    fixture = TestBed.createComponent(ClaimWorkspacePage);
    el = fixture.nativeElement;
    page = fixture.componentInstance as unknown as Internals;
  });
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('drops the response for claim A that arrives after navigating to B, and commands use B', async () => {
    fixture.componentRef.setInput('id', A);
    fixture.detectChanges();
    await Promise.resolve();
    const aRead = ctl.expectOne(url(A)); // still in flight

    await goto(B);
    ctl.expectOne(url(B)).flush(detail({ id: B, claimNumber: 'CLM-SG-000099', version: 7 }), { headers: { ETag: '"7"' } });
    await flush(fixture);
    await side(B);

    // A answers last.
    aRead.flush(detail({ id: A, claimNumber: 'CLM-SG-000042', version: 3 }), { headers: { ETag: '"3"' } });
    await flush(fixture);
    ctl.expectNone(url(A, '/financials'));
    expect(page.claim()?.id).toBe(B);
    expect(page.etag()).toBe('"7"');
    expect(el.textContent).toContain('CLM-SG-000099');
    expect(el.textContent).not.toContain('CLM-SG-000042');

    // The action buttons post to B, with B's version.
    el.querySelector<HTMLElement>('[data-action=ADD_NOTE]')!.click();
    await flush(fixture);
    const box = el.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'note for B';
    box.dispatchEvent(new Event('input'));
    await flush(fixture);
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await flush(fixture);
    const req = post(B, 'notes');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    ctl.expectNone(url(A, '/notes'));
    req.flush(detail({ id: B, claimNumber: 'CLM-SG-000099', version: 8 }), { headers: { ETag: '"8"' } });
    await flush(fixture);
    await side(B);
    expect(page.etag()).toBe('"8"');
  });

  it('clears claim A at once on navigation, so its buttons cannot act while B loads', async () => {
    fixture.componentRef.setInput('id', A);
    fixture.detectChanges();
    await Promise.resolve();
    ctl.expectOne(url(A)).flush(detail(), { headers: { ETag: '"3"' } });
    await flush(fixture);
    await side(A);
    expect(el.querySelector('[data-action=ADD_NOTE]')).not.toBeNull();

    await goto(B);
    expect(page.claim()).toBeNull();
    expect(el.querySelector('[data-action=ADD_NOTE]')).toBeNull();
    ctl.expectOne(url(B)).flush(detail({ id: B, claimNumber: 'CLM-SG-000099' }), { headers: { ETag: '"1"' } });
    await flush(fixture);
    await side(B);
  });

  it('a command for A that finishes after navigating to B does not touch B', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    fixture.componentRef.setInput('id', A);
    fixture.detectChanges();
    await Promise.resolve();
    ctl.expectOne(url(A)).flush(detail(), { headers: { ETag: '"3"' } });
    await flush(fixture);
    await side(A);
    el.querySelector<HTMLElement>('[data-action=ADD_NOTE]')!.click();
    await flush(fixture);
    const box = el.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'note for A';
    box.dispatchEvent(new Event('input'));
    await flush(fixture);
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await flush(fixture);
    const aCommand = post(A, 'notes'); // in flight

    await goto(B);
    ctl.expectOne(url(B)).flush(detail({ id: B, claimNumber: 'CLM-SG-000099', version: 7 }), { headers: { ETag: '"7"' } });
    await flush(fixture);
    await side(B);

    aCommand.flush(detail({ id: A, version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.expectNone(url(A, '/financials'));
    expect(page.claim()?.id).toBe(B);
    expect(page.etag()).toBe('"7"');
    expect(errors).not.toHaveBeenCalled();
  });

  it('keeps the open claim and the typed draft when a reload fails', async () => {
    fixture.componentRef.setInput('id', A);
    fixture.detectChanges();
    await Promise.resolve();
    ctl.expectOne(url(A)).flush(detail(), { headers: { ETag: '"3"' } });
    await flush(fixture);
    await side(A);
    el.querySelector<HTMLElement>('[data-action=ADD_NOTE]')!.click();
    await flush(fixture);
    const box = el.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'my draft';
    box.dispatchEvent(new Event('input'));
    await flush(fixture);

    void page.load();
    await Promise.resolve();
    ctl.expectOne(url(A)).flush({ status: 503, code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    await flush(fixture);

    expect(page.claim()?.id).toBe(A);
    expect(page.etag()).toBe('"3"');
    expect(el.querySelector('[data-testid=reload-error]')).not.toBeNull();
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('my draft');
  });

  it('has no polling: nothing re-reads the claim on its own', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval'] });
    try {
      fixture.componentRef.setInput('id', A);
      fixture.detectChanges();
      await Promise.resolve();
      ctl.expectOne(url(A)).flush(detail(), { headers: { ETag: '"3"' } });
      for (let i = 0; i < 10; i++) await Promise.resolve();
      ctl.match((r) => r.url.includes('/financials') || r.url.includes('/documents')).forEach((r) => r.flush({}));
      vi.advanceTimersByTime(10 * 60_000);
      ctl.expectNone(url(A));
    } finally {
      vi.useRealTimers();
    }
  });
});
