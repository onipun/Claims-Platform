import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Announcer } from '../../shared/ui';
import { CHECKLIST, CLAIM_ID, claimDetail } from './claimant.fixtures';
import { ClaimDetailPage } from './claim-detail.page';
import { provideHttpClientTesting } from '@angular/common/http/testing';

const API = `/api/v1/claimant/claims/${CLAIM_ID}`;
const OTHER_ID = '88888888-8888-4888-8888-888888888888';
const OTHER = `/api/v1/claimant/claims/${OTHER_ID}`;

interface Internals {
  refresh(): Promise<void>;
  reload(): Promise<string>;
  reloading(): boolean;
  onUploaded(): Promise<void>;
  etag(): string | null;
  claim(): { openQuestion?: { question?: string } } | null;
  checklist(): unknown;
  answer: { (): string };
  answered(): boolean;
}

describe('ClaimDetailPage: stale responses and failures', () => {
  let fixture: ComponentFixture<ClaimDetailPage>;
  let el: HTMLElement;
  let ctl: HttpTestingController;
  let page: Internals;
  let polite: ReturnType<typeof vi.fn>;

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  };
  const open = async () => {
    fixture.componentRef.setInput('id', CLAIM_ID);
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r));
    ctl.expectOne(API).flush(claimDetail(), { headers: { ETag: '"3"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
  };
  const type = async (v: string) => {
    const box = el.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = v;
    box.dispatchEvent(new Event('input'));
    await settle();
  };
  /** Starts a background refresh and returns its two pending requests, unanswered. */
  const startPoll = async () => {
    void page.refresh();
    await Promise.resolve();
    const reqs = ctl.match((r) => r.url === API || r.url === `${API}/documents`);
    expect(reqs.length).toBe(2);
    return { claim: reqs.find((r) => r.request.url === API)!, docs: reqs.find((r) => r.request.url !== API)! };
  };

  beforeEach(() => {
    polite = vi.fn();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: Announcer, useValue: { polite, assertive: vi.fn() } }],
    });
    fixture = TestBed.createComponent(ClaimDetailPage);
    el = fixture.nativeElement;
    ctl = TestBed.inject(HttpTestingController);
    page = fixture.componentInstance as unknown as Internals;
  });
  afterEach(() => {
    fixture.destroy();
  });

  it('a poll that started before the answer succeeded cannot overwrite the claim or the ETag', async () => {
    await open();
    await type('It is at my home.');
    const poll = await startPoll(); // old state: version 3, question still open

    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
    ctl.expectOne(`${API}/info-responses`).flush(
      claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, actionRequired: false, version: 4 }),
      { headers: { ETag: '"4"' } },
    );
    await settle();
    expect(page.etag()).toBe('"4"');

    // The poll answers last, with the version from before the command.
    poll.claim.flush(claimDetail({ version: 3 }), { headers: { ETag: '"3"' } });
    poll.docs.flush(CHECKLIST);
    await settle();

    expect(page.etag()).toBe('"4"');
    expect(page.claim()?.openQuestion).toBeUndefined();
    expect(el.querySelector('[data-testid=question-panel]')).toBeNull();

    // The refresh that the answer itself started is current and may land.
    ctl.expectOne(API).flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, version: 4 }), { headers: { ETag: '"4"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
  });

  it('a poll that changes the version while the claimant has typed sets stale, keeps the ETag and the visible question', async () => {
    await open();
    await type('half an answer');
    const poll = await startPoll();
    poll.claim.flush(
      claimDetail({ version: 4, openQuestion: { askedAt: '2026-03-10T02:00:00Z', question: 'A different question?' } }),
      { headers: { ETag: '"4"' } },
    );
    poll.docs.flush(CHECKLIST);
    await settle();

    expect(el.querySelector('[data-testid=stale-notice]')).not.toBeNull();
    expect(el.querySelector('[data-testid=stale-notice]')!.textContent).toContain("hasn't been sent");
    expect(el.querySelector('[data-testid=stale-notice]')!.textContent).not.toContain('was not sent');
    expect(page.etag()).toBe('"3"');
    expect(el.querySelector('[data-testid=question-text]')!.textContent).toContain('where the vehicle is now');
    expect(el.querySelector('[data-testid=question-text]')!.textContent).not.toContain('A different question');
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('half an answer');
  });

  it('with an empty answer box a changed refresh applies normally', async () => {
    await open();
    const poll = await startPoll();
    poll.claim.flush(
      claimDetail({ version: 4, openQuestion: { askedAt: '2026-03-10T02:00:00Z', question: 'A different question?' } }),
      { headers: { ETag: '"4"' } },
    );
    poll.docs.flush(CHECKLIST);
    await settle();

    expect(el.querySelector('[data-testid=stale-notice]')).toBeNull();
    expect(page.etag()).toBe('"4"');
    expect(el.querySelector('[data-testid=question-text]')!.textContent).toContain('A different question');
  });

  it('an unchanged refresh with text typed does not raise the stale notice', async () => {
    await open();
    await type('half an answer');
    const poll = await startPoll();
    poll.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    poll.docs.flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=stale-notice]')).toBeNull();
  });

  it('changing the id resets the answer, the checklist and the sent notice, and drops the old claim\'s late reply', async () => {
    await open();
    await type('typed for claim one');
    const late = await startPoll(); // claim one's poll, still in flight

    fixture.componentRef.setInput('id', OTHER_ID);
    fixture.detectChanges();
    await settle();

    expect(page.answer()).toBe('');
    expect(page.checklist()).toBeNull();
    expect(page.claim()).toBeNull();
    expect(page.answered()).toBe(false);

    // Claim one's reply lands while claim two is loading: ignored.
    late.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    late.docs.flush(CHECKLIST);
    await settle();
    expect(page.claim()).toBeNull();

    ctl.expectOne(OTHER).flush(claimDetail({ id: OTHER_ID, claimNumber: 'CLM-SG-000099', openQuestion: undefined }), { headers: { ETag: '"9"' } });
    ctl.expectOne(`${OTHER}/documents`).flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=claim-number]')!.textContent).toContain('CLM-SG-000099');
    expect(page.etag()).toBe('"9"');
  });

  it('after an upload says "Uploaded"; when the refresh then fails says so and offers a reload', async () => {
    await open();
    const up = page.onUploaded();
    await Promise.resolve();
    expect(el.textContent).not.toContain('could not');
    ctl.expectOne(API).flush({ status: 500, code: 'X', title: 'boom' }, { status: 500, statusText: 'x' });
    ctl.expectOne(`${API}/documents`).flush({}, { status: 500, statusText: 'x' });
    await up;
    await settle();
    const notice = el.querySelector('[data-testid=upload-notice]')!;
    expect(notice.textContent).toContain("Uploaded, but we couldn't refresh");

    notice.querySelector('button')!.click();
    await Promise.resolve();
    ctl.expectOne(API).flush(claimDetail(), { headers: { ETag: '"3"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=upload-notice]')!.textContent!.trim()).toBe('Uploaded');
  });

  it('a failed poll shows "Out of date since HH:MM, retrying" and the next success clears it', async () => {
    await open();
    const failing = await startPoll();
    failing.claim.flush({ status: 503, code: 'X', title: 'down' }, { status: 503, statusText: 'x' });
    failing.docs.flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=out-of-date]')!.textContent).toMatch(/Out of date since \d\d:\d\d, retrying/);

    const ok = await startPoll();
    ok.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    ok.docs.flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=out-of-date]')).toBeNull();
  });

  it('says it is unknown whether the answer was saved when the network drops after sending', async () => {
    await open();
    await type('my answer');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
    ctl.expectOne(`${API}/info-responses`).error(new ProgressEvent('error'));
    await settle();
    const err = el.querySelector('[data-testid=answer-error]')!;
    expect(err.textContent).toContain('Unknown whether this was saved. Reload to check.');
    expect(err.querySelector('button')).not.toBeNull();
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('my answer');
  });

  /** Starts a Reload and returns its two pending requests, unanswered. */
  const startReload = async () => {
    const done = page.reload();
    await Promise.resolve();
    const reqs = ctl.match((r) => r.url === API || r.url === `${API}/documents`);
    expect(reqs.length).toBe(2);
    return { done, claim: reqs.find((r) => r.request.url === API)!, docs: reqs.find((r) => r.request.url !== API)! };
  };
  const answerNow = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
    ctl.expectOne(`${API}/info-responses`).flush(
      claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, actionRequired: false, version: 4 }),
      { headers: { ETag: '"4"' } },
    );
    await settle();
  };

  it('R1: a reload overtaken by the post-answer refresh does not leave Reload stuck', async () => {
    await open();
    await type('my answer');
    const rl = await startReload();
    expect(page.reloading()).toBe(true);
    await answerNow(); // the answer invalidates the reload, and its refresh (forced) supersedes it
    rl.claim.flush(claimDetail({ version: 3 }), { headers: { ETag: '"3"' } });
    rl.docs.flush(CHECKLIST);
    expect(await rl.done).toBe('superseded');
    expect(page.reloading()).toBe(false);
    expect(page.etag()).toBe('"4"'); // the overtaken read did not land
    // Finish the refresh the answer started, then Reload must work again.
    ctl.expectOne(API).flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, version: 4 }), { headers: { ETag: '"4"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
    const again = page.reload();
    await Promise.resolve();
    ctl.expectOne(API).flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, version: 4 }), { headers: { ETag: '"4"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    expect(await again).toBe('ok');
  });

  it('R1: a poll tick while a reload is reading is skipped; a second Reload click is refused', async () => {
    await open();
    const rl = await startReload();
    void page.refresh();
    expect(await page.reload()).toBe('busy');
    await Promise.resolve();
    ctl.expectNone((r) => r.url === API || r.url === `${API}/documents`);
    rl.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    rl.docs.flush(CHECKLIST);
    expect(await rl.done).toBe('ok');
    expect(page.reloading()).toBe(false);
  });

  it('R1: an upload whose refresh was overtaken shows no "couldn\'t refresh" failure', async () => {
    await open();
    await type('my answer');
    const up = page.onUploaded();
    await Promise.resolve();
    const reqs = ctl.match((r) => r.url === API || r.url === `${API}/documents`);
    expect(reqs.length).toBe(2);
    await answerNow(); // overtakes the upload's reload
    reqs.forEach((r) => r.flush({ status: 500, code: 'X', title: 'boom' }, { status: 500, statusText: 'x' }));
    await up;
    await settle();
    expect(el.textContent).not.toContain("couldn't refresh");
    expect(el.querySelector('[data-testid=upload-notice]')?.textContent?.trim()).toBe('Uploaded');
    ctl.expectOne(API).flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, version: 4 }), { headers: { ETag: '"4"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
  });

  it('R1: a successful answer forces a refresh GET even with no reload running', async () => {
    await open();
    await type('my answer');
    await answerNow();
    const c = ctl.expectOne(API);
    expect(c.request.method).toBe('GET');
    c.flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, version: 4 }), { headers: { ETag: '"4"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
  });

  const WITH_FILE = {
    ...CHECKLIST,
    outstanding: 0,
    items: [
      { documentType: 'POLICE_REPORT', status: 'UPLOADED', placeholder: false, uploads: [
        { id: 'd9', documentType: 'POLICE_REPORT', filename: 'police-report.pdf', sizeBytes: 1024, status: 'UPLOADED', uploadedAt: '2026-03-09T03:00:00Z' },
      ] },
      CHECKLIST.items![1],
    ],
  } as typeof CHECKLIST;

  it('D-2: an upload finishing while a Reload is reading forces a new read, and the checklist shows the file', async () => {
    await open();
    const rl = await startReload(); // this read started before the upload and has no file
    const up = page.onUploaded();
    await Promise.resolve();
    await Promise.resolve();
    const claim = ctl.expectOne(API); // a further GET is made, not a skipped one
    const docs = ctl.expectOne(`${API}/documents`);
    rl.claim.flush(claimDetail({ version: 3 }), { headers: { ETag: '"3"' } });
    rl.docs.flush(CHECKLIST);
    expect(await rl.done).toBe('superseded');
    expect(el.textContent).not.toContain('police-report.pdf');
    claim.flush(claimDetail({ version: 3 }), { headers: { ETag: '"3"' } });
    docs.flush(WITH_FILE);
    await up;
    await settle();
    expect(el.textContent).toContain('police-report.pdf');
    expect(page.reloading()).toBe(false);
    expect(el.querySelector('[data-testid=upload-notice]')?.textContent?.trim()).toBe('Uploaded');
  });

  it('D-2: if the forced read fails, the upload notice says it could not refresh', async () => {
    await open();
    const rl = await startReload();
    const up = page.onUploaded();
    await Promise.resolve();
    await Promise.resolve();
    const claim = ctl.expectOne(API);
    const docs = ctl.expectOne(`${API}/documents`);
    rl.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    rl.docs.flush(CHECKLIST);
    await rl.done;
    claim.flush({ status: 500, code: 'X', title: 'boom' }, { status: 500, statusText: 'x' });
    docs.flush(CHECKLIST);
    await up;
    await settle();
    expect(el.querySelector('[data-testid=upload-notice]')?.textContent).toContain("couldn't refresh");
  });

  it('D-3: a later successful read removes the failed-reload alert and the upload "couldn\'t refresh" notice', async () => {
    await open();
    const up = page.onUploaded();
    await Promise.resolve();
    const reqs = ctl.match((r) => r.url === API || r.url === `${API}/documents`);
    expect(reqs.length).toBe(2);
    reqs.forEach((r) => r.flush({ status: 500, code: 'X', title: 'boom' }, { status: 500, statusText: 'x' }));
    await up;
    await settle();
    expect(el.querySelector('[data-testid=upload-notice]')?.textContent).toContain("couldn't refresh");
    expect((page as unknown as { reloadFailure(): unknown }).reloadFailure()).not.toBeNull();

    // A poll tick succeeds on its own, without anyone pressing Reload.
    const ok = await startPoll();
    ok.claim.flush(claimDetail(), { headers: { ETag: '"3"' } });
    ok.docs.flush(WITH_FILE);
    await settle();
    expect(el.textContent).not.toContain("couldn't refresh");
    expect((page as unknown as { reloadFailure(): unknown }).reloadFailure()).toBeNull();
    expect(el.querySelector('[data-testid=upload-notice]')?.textContent?.trim()).toBe('Uploaded');
  });

  it('R2: a failed Reload says why in a role=alert, and a 404 does not read as a network blip', async () => {
    await open();
    const rl = await startReload();
    rl.claim.flush({ status: 404, code: 'CLAIM_NOT_FOUND', title: 'Not found' }, { status: 404, statusText: 'Not Found' });
    rl.docs.flush(CHECKLIST);
    expect(await rl.done).toBe('failed');
    await settle();
    const alert = el.querySelector('[data-testid=reload-error]')!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('could not be found');
    expect(alert.textContent).not.toContain('connection');
    expect(page.reloading()).toBe(false);
  });

  it('R2: the out-of-date banner keeps the reason; a 403 says access, not connection', async () => {
    await open();
    const p = await startPoll();
    p.claim.flush({ status: 403, code: 'FORBIDDEN', title: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });
    p.docs.flush(CHECKLIST);
    await settle();
    const banner = el.querySelector('[data-testid=out-of-date]')!.textContent!;
    expect(banner).toMatch(/Out of date since \d\d:\d\d/);
    expect(banner).toContain('no longer have access');
  });

  it('R3: a successful Reload announces "Claim reloaded" and puts focus on the answer box', async () => {
    await open();
    (document.activeElement as HTMLElement | null)?.blur?.();
    const done = (page as unknown as { reloadClaim(): Promise<string> }).reloadClaim();
    await Promise.resolve();
    ctl.expectOne(API).flush(claimDetail(), { headers: { ETag: '"3"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    expect(await done).toBe('ok');
    await settle();
    expect(polite).toHaveBeenCalledWith('Claim reloaded');
    expect(document.activeElement).toBe(el.querySelector('textarea'));
  });
});
