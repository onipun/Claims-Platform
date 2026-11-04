import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmService } from '../../shared/ui';
import { ClaimWorkspacePage } from './claim-workspace.page';
import { CLAIM_ID, detail, flush, norm, setupStaffTest } from './testing';
import type { StaffClaimDetail } from './staff.api';

const BASE = `/api/v1/staff/claims/${CLAIM_ID}`;
const fail = (req: TestRequest, status: number, code: string) =>
  req.flush({ status, code, title: code, detail: code }, { status, statusText: code });

describe('ClaimWorkspacePage', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<ClaimWorkspacePage>;
  let el: HTMLElement;
  let confirmAnswer = true;

  async function open(d: StaffClaimDetail = detail(), etag = '"3"') {
    ctl = setupStaffTest('OFFICER', ['SG'], { providers: [{ provide: ConfirmService, useValue: { confirm: async () => confirmAnswer } }] });
    fixture = TestBed.createComponent(ClaimWorkspacePage);
    fixture.componentRef.setInput('id', CLAIM_ID);
    el = fixture.nativeElement;
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();
    ctl.expectOne(BASE).flush(d, { headers: { ETag: etag } });
    await flush(fixture);
    ctl.expectOne(`${BASE}/financials`).flush({ currency: 'SGD', reserve: '3700.00', reserveStatus: 'PROVISIONAL', paid: '0.00', outstanding: '3700.00', incurred: '3700.00', entries: [{ id: 1, type: 'RESERVE_SET', amount: '3700.00', at: '2026-11-02T01:00:00Z', actorName: 'System' }] });
    ctl.expectOne(`${BASE}/documents`).flush({ items: [], other: [], outstanding: 0 });
    await flush(fixture);
  }
  const buttons = () => Array.from(el.querySelectorAll<HTMLButtonElement>('[data-action]')).map((b) => b.dataset['action']);
  const click = async (sel: string) => {
    el.querySelector<HTMLElement>(sel)!.click();
    await flush(fixture);
  };
  const type = async (sel: string, v: string) => {
    const i = el.querySelector<HTMLInputElement | HTMLTextAreaElement>(sel)!;
    i.value = v;
    i.dispatchEvent(new Event('input'));
    await flush(fixture);
  };
  const submit = async (formSel: string) => {
    el.querySelector<HTMLFormElement>(formSel)!.dispatchEvent(new Event('submit'));
    await flush(fixture);
  };
  const post = (path: string): TestRequest => ctl.expectOne((r) => r.method === 'POST' && r.url === `${BASE}/${path}`);

  afterEach(() => {
    ctl.verify();
    fixture.destroy();
    sessionStorage.clear();
    confirmAnswer = true;
  });

  it('wraps long unbroken claimant text instead of widening the page', async () => {
    const long = 'Z'.repeat(300);
    await open(detail({ description: long, openQuestion: { question: long, askedAt: '2026-11-02T01:00:00Z' } } as Partial<StaffClaimDetail>));
    for (const id of ['description', 'open-question']) {
      const node = el.querySelector(`[data-testid=${id}]`)!;
      expect(node.textContent).toContain(long);
      expect(node.className).toContain('break-words');
    }
  });

  it('renders buttons only for the actions the API allows', async () => {
    await open(detail({ allowedActions: ['ADD_NOTE', 'ASSESS'] }));
    expect(buttons()).toEqual(['ADD_NOTE', 'ASSESS']);
    expect(el.textContent).not.toContain('Approve');
  });

  it('shows an unknown or empty action list as no actions', async () => {
    await open(detail({ allowedActions: [] }));
    expect(buttons()).toEqual([]);
    expect(el.querySelector('[data-testid=no-actions]')).toBeTruthy();
  });

  it('says plainly that notes are internal and that request-info text is seen by the claimant', async () => {
    await open();
    await click('[data-action=ADD_NOTE]');
    expect(el.textContent).toContain('Notes are internal');
    await click('[data-action=REQUEST_INFO]');
    expect(el.textContent).toContain('The claimant will see this text');
  });

  it('sends If-Match with the ETag on a note, then adopts the new ETag', async () => {
    await open();
    await click('[data-action=ADD_NOTE]');
    await type('textarea', 'Checked the garage quote');
    await submit('form');
    const req = post('notes');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ text: 'Checked the garage quote' });
    req.flush(detail({ version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.expectOne(`${BASE}/financials`).flush({ entries: [] });
    ctl.expectOne(`${BASE}/documents`).flush({});
    await flush(fixture);
    await click('[data-action=ASSESS]');
    await type('form input', '3000');
    await submit('form');
    expect(post('assessment').request.headers.get('If-Match')).toBe('"4"');
  });

  it('keeps typed input and offers a reload on 409', async () => {
    await open();
    await click('[data-action=REQUEST_INFO]');
    await type('textarea', 'Please send the police report');
    await submit('form');
    fail(post('info-requests'), 409, 'STALE_VERSION');
    await flush(fixture);
    const notice = el.querySelector('[data-testid=notice-stale]')!;
    expect(notice.textContent).toContain('This claim changed');
    expect(notice.textContent).toContain('Reload claim');
    expect(el.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('Please send the police report');
  });

  it('explains that a manager is needed on AUTHORITY_EXCEEDED', async () => {
    await open(detail({ status: 'ASSESSED', allowedActions: ['APPROVE'] }));
    await click('[data-action=APPROVE]');
    await type('form input', '9000');
    await submit('form');
    const req = post('approval');
    expect(req.request.body).toEqual({ amount: '9000' });
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    fail(req, 403, 'AUTHORITY_EXCEEDED');
    await flush(fixture);
    const n = el.querySelector('[data-testid=notice-authority]')!;
    expect(n.textContent).toContain('A manager needs to approve it');
    expect(norm(n.textContent)).toContain('SGD 5,000.00');
  });

  it('asks for confirmation before rejecting, and sends nothing when cancelled', async () => {
    await open();
    await click('[data-action=REJECT]');
    expect(el.textContent).toContain('The claimant will see this explanation');
    el.querySelector<HTMLSelectElement>('select')!.value = 'NOT_COVERED';
    el.querySelector('select')!.dispatchEvent(new Event('change'));
    await type('textarea', 'Flood damage is excluded');
    confirmAnswer = false;
    await submit('form');
    ctl.expectNone((r) => r.method === 'POST');
    confirmAnswer = true;
    await submit('form');
    const req = post('rejection');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ reason: 'NOT_COVERED', explanation: 'Flood damage is excluded' });
    req.flush(detail({ status: 'REJECTED', allowedActions: [] }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.expectOne(`${BASE}/financials`).flush({ entries: [] });
    ctl.expectOne(`${BASE}/documents`).flush({});
  });

  it('sends If-Match on pick-up', async () => {
    await open(detail({ status: 'SUBMITTED', assignedOfficer: undefined, allowedActions: ['PICK_UP'] }));
    await click('[data-action=PICK_UP]');
    const req = post('pick-up');
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    req.flush(detail({ version: 4 }), { headers: { ETag: '"4"' } });
    await flush(fixture);
    ctl.expectOne(`${BASE}/financials`).flush({ entries: [] });
    ctl.expectOne(`${BASE}/documents`).flush({});
  });

  it('shows internal timeline entries distinctly and can hide them', async () => {
    await open();
    const items = Array.from(el.querySelectorAll<HTMLElement>('[data-testid=timeline] li'));
    expect(items.map((i) => i.dataset['visibility']).sort()).toEqual(['CLAIMANT', 'INTERNAL']);
    expect(el.querySelector('[data-visibility=INTERNAL]')!.textContent).toContain('Internal');
    await click('#tl-h ~ label input, section[aria-labelledby=tl-h] input[type=checkbox]');
    expect(el.querySelectorAll('[data-testid=timeline] li').length).toBe(1);
  });

  it('shows incident time in the market zone, late flag and stated reason', async () => {
    await open();
    expect(el.querySelector('[data-testid=incident]')!.textContent).toContain('30 Oct 2026, 22:15 (Asia/Singapore)');
    expect(el.querySelector('[data-testid=late-reason]')!.textContent).toContain('Was abroad');
  });
});

describe('ClaimWorkspacePage conflict of interest', () => {
  it('shows a blocking state, not a generic error', async () => {
    const ctl = setupStaffTest('OFFICER', ['SG']);
    const fixture = TestBed.createComponent(ClaimWorkspacePage);
    fixture.componentRef.setInput('id', CLAIM_ID);
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();
    fail(ctl.expectOne(BASE), 403, 'CONFLICT_OF_INTEREST');
    await flush(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=blocked]')!.textContent).toContain('your own policy');
    expect(el.querySelector('[data-action]')).toBeNull();
    fixture.destroy();
    sessionStorage.clear();
  });
});
