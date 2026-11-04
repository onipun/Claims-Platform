import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CHECKLIST, CLAIM_ID, claimDetail } from './claimant.fixtures';
import { ClaimDetailPage } from './claim-detail.page';

const API = `/api/v1/claimant/claims/${CLAIM_ID}`;

describe('ClaimDetailPage', () => {
  let fixture: ComponentFixture<ClaimDetailPage>;
  let el: HTMLElement;
  let ctl: HttpTestingController;

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  };
  const load = async (claim = claimDetail()) => {
    fixture.componentRef.setInput('id', CLAIM_ID);
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r));
    ctl.expectOne(API).flush(claim, { headers: { ETag: '"3"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
  };
  const answerBox = () => el.querySelector<HTMLTextAreaElement>('textarea')!;
  const type = async (v: string) => {
    answerBox().value = v;
    answerBox().dispatchEvent(new Event('input'));
    await settle();
  };
  const send = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(ClaimDetailPage);
    el = fixture.nativeElement;
    ctl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctl.verify());

  it('renders status in plain language, the open question, the checklist and the timeline', async () => {
    await load();
    expect(el.querySelector('[data-testid=status-explanation]')?.textContent).toMatch(/need more information/);
    expect(el.querySelector('[data-testid=question-text]')?.textContent).toContain('where the vehicle is now');
    expect(el.querySelector('[data-testid=outstanding]')?.textContent).toMatch(/1 document is still outstanding/);
    expect(el.querySelector('[data-testid=re-requested]')?.textContent).toContain('The quote was cut off.');
    expect(el.querySelector('[data-testid=timeline]')?.textContent).toContain('Claim received');
    expect(el.textContent?.toLowerCase()).not.toContain('reserve');
  });

  it('R12: shows the accident location the server returned', async () => {
    await load();
    expect(el.querySelector('[data-testid=accident-location]')?.textContent).toContain('Orchard Road, Singapore');
  });

  it('sends the answer with the ETag as If-Match and shows the thank-you', async () => {
    await load();
    await type('It is at my home.');
    await send();
    const req = ctl.expectOne(`${API}/info-responses`);
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ answer: 'It is at my home.' });
    req.flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined, actionRequired: false, version: 4 }), { headers: { ETag: '"4"' } });
    await settle();
    // The refresh after a successful answer now runs: `sending` is already false when it starts.
    const refresh = ctl.match(API);
    expect(refresh.length).toBe(1);
    refresh.forEach((r) => r.flush(claimDetail({ status: 'UNDER_REVIEW', openQuestion: undefined })));
    ctl.match(`${API}/documents`).forEach((r) => r.flush(CHECKLIST));
    await settle();
    expect(el.querySelector('[data-testid=question-panel]')).toBeNull();
  });

  it('on 409 STALE_VERSION shows the reload notice and keeps what was typed; reload fetches the new ETag', async () => {
    await load();
    await type('My long answer');
    await send();
    ctl.expectOne(`${API}/info-responses`).flush(
      { status: 409, code: 'STALE_VERSION', title: 'Stale' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(el.querySelector('[data-testid=stale-notice]')).not.toBeNull();
    expect(answerBox().value).toBe('My long answer');

    (el.querySelector('[data-testid=stale-notice] button') as HTMLButtonElement).click();
    await Promise.resolve();
    ctl.expectOne(API).flush(claimDetail({ version: 5 }), { headers: { ETag: '"5"' } });
    ctl.expectOne(`${API}/documents`).flush(CHECKLIST);
    await settle();
    expect(el.querySelector('[data-testid=stale-notice]')).toBeNull();
    expect(answerBox().value).toBe('My long answer');

    await send();
    expect(ctl.expectOne(`${API}/info-responses`).request.headers.get('If-Match')).toBe('"5"');
  });

  it('does not send an empty answer', async () => {
    await load();
    await type('   ');
    await send();
    ctl.expectNone(`${API}/info-responses`);
    expect(el.querySelector('[role=alert]')?.textContent).toMatch(/Enter your answer/);
  });

  it('shows a decision with the approved amount, formatted from a string', async () => {
    await load(claimDetail({ status: 'APPROVED', openQuestion: undefined, decision: { amount: '1234567.50', currency: 'SGD', outcome: 'APPROVED', explanation: 'Approved in full.' } }));
    expect(el.querySelector('[data-testid=decision-amount]')?.textContent?.replace(/\s/g, ' ')).toContain('SGD 1,234,567.50');
  });

  it('shows an error state with retry when the claim cannot be loaded', async () => {
    fixture.componentRef.setInput('id', CLAIM_ID);
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r));
    ctl.expectOne(API).flush({ status: 500, code: 'X', title: 'Boom', errorId: 'e-1' }, { status: 500, statusText: 'x' });
    ctl.expectOne(`${API}/documents`).flush({}, { status: 500, statusText: 'x' });
    await settle();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('e-1');
  });
});
