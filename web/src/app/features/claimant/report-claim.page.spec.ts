import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CLAIM_ID, MOTOR_POLICY, claimDetail } from './claimant.fixtures';
import { ReportClaimPage } from './report-claim.page';

const API = '/api/v1/claimant';

describe('ReportClaimPage', () => {
  let fixture: ComponentFixture<ReportClaimPage>;
  let el: HTMLElement;
  let ctl: HttpTestingController;

  const settle = async () => {
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
  };
  const field = (name: string) => el.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(`[formcontrolname=${name}]`)!;
  const fill = (name: string, v: string) => {
    const f = field(name);
    f.value = v;
    f.dispatchEvent(new Event(f instanceof HTMLSelectElement ? 'change' : 'input'));
    f.dispatchEvent(new Event('blur'));
  };
  const fillValid = async () => {
    fill('claimType', 'OWN_DAMAGE');
    fill('incidentDate', '2026-03-01');
    fill('incidentTime', '10:00');
    fill('estimate', '1200.50');
    fill('vehicleRegistration', 'SGX1234A');
    fill('driverName', 'Tan Wei');
    fill('location', 'Orchard Road');
    await settle();
  };
  const submit = async () => {
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await Promise.resolve();
  };
  const post = (): TestRequest => ctl.expectOne((r) => r.method === 'POST' && r.url === `${API}/claims`);

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(ReportClaimPage);
    el = fixture.nativeElement;
    ctl = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    ctl.expectOne(`${API}/policies`).flush([MOTOR_POLICY]);
    await settle();
  });

  afterEach(() => ctl.verify());

  it('shows motor fields for a motor policy and no property fields', () => {
    expect(el.querySelector('[data-testid=motor-fields]')).not.toBeNull();
    expect(el.querySelector('[data-testid=property-fields]')).toBeNull();
    const options = Array.from(field('claimType').querySelectorAll('option')).map((o) => o.value);
    expect(options).toEqual(['', 'OWN_DAMAGE', 'THIRD_PARTY_DAMAGE', 'TOTAL_LOSS', 'WINDSCREEN', 'THEFT']);
  });

  it('does not call the API while required fields are missing, and wires the errors with aria-describedby', async () => {
    await submit();
    await settle();
    ctl.expectNone(`${API}/claims`);
    const reg = field('vehicleRegistration');
    expect(reg.getAttribute('aria-invalid')).toBe('true');
    const id = reg.getAttribute('aria-describedby')!;
    expect(el.querySelector(`#${id}`)?.textContent).toMatch(/registration/);
  });

  it('keeps the same Idempotency-Key across a failed retry and takes a new one after success', async () => {
    await fillValid();
    await submit();
    const first = post();
    const key1 = first.request.headers.get('Idempotency-Key')!;
    expect(key1).toBeTruthy();
    first.flush({ status: 503, code: 'UNAVAILABLE', title: 'Unavailable' }, { status: 503, statusText: 'Unavailable' });
    await settle();
    expect(el.querySelector('[data-testid=form-error]')).not.toBeNull();

    await submit();
    const retry = post();
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key1);
    retry.flush(claimDetail({ status: 'SUBMITTED' }), { status: 201, statusText: 'Created', headers: { ETag: '"0"' } });
    await settle();
    expect(el.querySelector('[data-testid=claim-number]')?.textContent).toContain('CLM-SG-000042');

    // Report another claim with identical details: a fresh key, because the earlier one was spent on success.
    (Array.from(el.querySelectorAll('button')).find((b) => /another/.test(b.textContent ?? '')) as HTMLButtonElement).click();
    await settle();
    await fillValid();
    await submit();
    const second = post();
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key1);
    second.flush(claimDetail(), { status: 201, statusText: 'Created' });
  });

  it('takes a new key after the user edits the form', async () => {
    await fillValid();
    await submit();
    const first = post();
    const key1 = first.request.headers.get('Idempotency-Key');
    first.flush({ status: 503, code: 'UNAVAILABLE', title: 'x' }, { status: 503, statusText: 'x' });
    await settle();
    fill('estimate', '1300');
    await settle();
    await submit();
    const second = post();
    expect(second.request.headers.get('Idempotency-Key')).not.toBe(key1);
    expect((second.request.body as { estimate: string }).estimate).toBe('1300');
    second.flush(claimDetail(), { status: 201, statusText: 'Created' });
  });

  it('marks the button aria-disabled while submitting so a double click sends one request', async () => {
    await fillValid();
    await submit();
    await submit();
    const req = post(); // expectOne fails if a second POST went out
    fixture.detectChanges();
    const btn = el.querySelector<HTMLButtonElement>('[data-testid=submit-claim]')!;
    // aria-disabled, not disabled: focus stays on the button while the request is in flight.
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    expect(btn.disabled).toBe(false);
    req.flush(claimDetail(), { status: 201, statusText: 'Created' });
  });

  it('shows server field errors from problem+json on the right control', async () => {
    await fillValid();
    await submit();
    post().flush(
      { status: 422, code: 'VALIDATION_FAILED', title: 'Invalid', errors: [{ field: 'lossDetails.vehicleRegistration', code: 'VALIDATION_FAILED', message: 'Registration not recognised' }] },
      { status: 422, statusText: 'Unprocessable' },
    );
    await settle();
    const reg = field('vehicleRegistration');
    expect(reg.getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector(`#${reg.getAttribute('aria-describedby')}`)?.textContent).toContain('Registration not recognised');
  });

  it('R12: asks for the accident location, and shows the server\'s lossDetails.location error (control characters) on it', async () => {
    expect(el.textContent).toContain('Accident location');
    await fillValid();
    await submit();
    post().flush(
      { status: 422, code: 'VALIDATION_FAILED', title: 'Invalid', errors: [{ field: 'lossDetails.location', code: 'INVALID_CHARACTERS', message: 'Remove control characters' }] },
      { status: 422, statusText: 'Unprocessable' },
    );
    await settle();
    const loc = field('location');
    expect(loc.getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector(`#${loc.getAttribute('aria-describedby')}`)?.textContent).toContain('Remove control characters');
    expect(el.querySelector('[data-testid=form-error]')!.textContent).toContain('Fix the 1 field with errors.');
  });

  it('lists server field errors that match no visible field in the banner, never just "highlighted"', async () => {
    await fillValid();
    await submit();
    post().flush(
      { status: 422, code: 'VALIDATION_FAILED', title: 'Invalid', errors: [
        { field: 'motor.thirdParty.name', code: 'VALIDATION_FAILED', message: 'Other party name is required' },
        { field: 'somethingElse', code: 'VALIDATION_FAILED', message: 'Something else is wrong' },
      ] },
      { status: 422, statusText: 'Unprocessable' },
    );
    await settle();
    const banner = el.querySelector('[data-testid=form-error]')!;
    expect(banner.textContent).toContain('Other party name is required');
    expect(banner.textContent).toContain('Something else is wrong');
    expect(banner.textContent).not.toContain('highlighted');
  });

  it('shows the claim number as the acknowledgement with documents as the next step', async () => {
    await fillValid();
    await submit();
    post().flush(claimDetail({ status: 'SUBMITTED' }), { status: 201, statusText: 'Created' });
    await settle();
    const conf = el.querySelector('[data-testid=confirmation]')!;
    expect(conf.textContent).toContain('CLM-SG-000042');
    expect(conf.textContent).toMatch(/Upload your documents/);
    expect(conf.querySelector<HTMLAnchorElement>('a')?.getAttribute('href')).toContain(CLAIM_ID);
  });
});
