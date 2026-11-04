import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { INSIGHTS_NOW, InsightsPage } from './insights.page';
import { flush, norm, setupStaffTest } from './testing';
import type { ExposureResponse } from './staff.api';

const NOW = Date.parse('2026-11-04T10:00:00Z');
const exposure = (asOf: string | null): ExposureResponse => ({
  asOf: asOf ?? undefined,
  basis: 'Case reserves. Excludes IBNR; not total liability.',
  rows: [
    { market: 'SG', line: 'MOTOR', currency: 'SGD', openClaims: 3, caseOutstanding: '12000.50', provisional: '4000.00', assessed: '8000.50', paid: '1500.00', incurred: '13500.50' },
    { market: 'ID', line: 'MOTOR', currency: 'IDR', openClaims: 2, caseOutstanding: '25000000', provisional: '0', assessed: '25000000', paid: '0', incurred: '25000000' },
  ],
  totalsByCurrency: [
    { currency: 'SGD', openClaims: 3, caseOutstanding: '12000.50', provisional: '4000.00', assessed: '8000.50', paid: '1500.00', incurred: '13500.50' },
    { currency: 'IDR', openClaims: 2, caseOutstanding: '25000000', provisional: '0', assessed: '25000000', paid: '0', incurred: '25000000' },
  ],
  indicativeUsdTotal: { currency: 'USD', label: 'Indicative', caseOutstanding: '10000.00', paid: '1100.00', incurred: '11100.00', usdPerUnitRates: { SGD: '0.74', IDR: '0.000062' }, excludedCurrencies: [] },
});

describe('InsightsPage', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<InsightsPage>;
  let el: HTMLElement;

  async function open() {
    ctl = setupStaffTest('MANAGER', ['SG', 'ID'], { providers: [{ provide: INSIGHTS_NOW, useValue: () => NOW }] });
    fixture = TestBed.createComponent(InsightsPage);
    el = fixture.nativeElement;
    fixture.detectChanges();
  }
  const exposureReq = () => ctl.expectOne((r) => r.url === '/api/v1/insights/exposure');
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
  });

  it('shows the basis, As of, and no stale mark for a fresh snapshot', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:59:30Z'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=basis]')!.textContent).toContain('Excludes IBNR; not total liability');
    expect(el.querySelector('[data-testid=basis]')!.textContent).not.toContain('CASE_RESERVES_EXCL_IBNR');
    expect(el.querySelector('[data-testid=asof]')!.textContent).toContain('As of');
    expect(el.querySelector('[data-testid=stale-badge]')).toBeNull();
  });

  it('marks an old asOf as stale', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:50:00Z'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=stale-badge]')!.textContent).toContain('No recent events');
    expect(el.querySelector('[data-testid=stale-badge]')!.textContent).not.toContain('Stale');
    expect(el.querySelector('[data-testid=stale-note]')!.textContent).toContain('may be behind if claims are being worked');
    expect(el.querySelector('[data-testid=stale-note]')!.textContent).toContain('10 min ago');
    expect(el.querySelector('[data-testid=asof]')!.textContent).toContain('As of');
  });

  it('marks a null asOf as stale and says the time is unknown', async () => {
    await open();
    exposureReq().flush(exposure(null));
    await flush(fixture);
    expect(el.querySelector('[data-testid=stale-badge]')!.textContent).toContain('Event time unknown');
    expect(el.querySelector('[data-testid=stale-note]')!.textContent).toContain('cannot be told');
    expect(el.querySelector('[data-testid=asof]')!.textContent).toContain('unknown');
  });

  it('keeps the last data, marked stale, when insights is unavailable', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:59:50Z'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=stale-badge]')).toBeNull();
    el.querySelector<HTMLButtonElement>('select')!; // filter present
    (fixture.componentInstance as unknown as { reload(): Promise<void> }).reload();
    exposureReq().flush({ status: 502, code: 'BAD_GATEWAY', title: 'x' }, { status: 502, statusText: 'Bad Gateway' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=unavailable]')!.textContent).toContain("Claim work isn't affected");
    expect(el.querySelector('[data-testid=stale-badge]')!.textContent).toContain('Stale');
    expect(el.querySelector('[data-testid=stale-note]')).toBeNull();
    expect(el.querySelector('table[aria-label="Case exposure by market and line"]')).toBeTruthy();
    expect(norm(el.textContent)).toContain('SGD 12,000.50');
  });

  it('treats a network error as unavailable too', async () => {
    await open();
    exposureReq().error(new ProgressEvent('error'));
    await flush(fixture);
    expect(el.querySelector('[data-testid=unavailable]')).toBeTruthy();
  });

  it('shows money per currency with no cross-currency sum, and USD only as labelled indicative', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:59:50Z'));
    await flush(fixture);
    const totals = Array.from(el.querySelectorAll('[data-testid=currency-totals] tbody tr')).map((r) => norm(r.textContent));
    expect(totals.length).toBe(2);
    expect(totals[0]).toContain('SGD 12,000.50');
    expect(totals[1]).toContain('IDR 25,000,000');
    // Nowhere is a sum of SGD and IDR amounts shown.
    expect(el.textContent).not.toContain('25,012,000');
    const ind = el.querySelector('[data-testid=indicative]')!;
    expect(ind.textContent).toContain('Indicative');
    expect(norm(ind.textContent)).toContain('USD 10,000.00');
    expect(el.querySelector('[data-testid=rates]')!.textContent).toContain('SGD = 0.74');
  });

  it('sends the market filter as a narrowing query parameter', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:59:50Z'));
    await flush(fixture);
    const sel = el.querySelector<HTMLSelectElement>('[data-testid=market-filter]')!;
    sel.value = 'SG';
    sel.dispatchEvent(new Event('change'));
    const req = ctl.expectOne((r) => r.url === '/api/v1/insights/exposure');
    expect(req.request.params.getAll('market')).toEqual(['SG']);
    req.flush(exposure('2026-11-04T09:59:50Z'));
  });

  it('shows a dash, not 0, for null performance figures', async () => {
    await open();
    exposureReq().flush(exposure('2026-11-04T09:59:50Z'));
    await flush(fixture);
    el.querySelector<HTMLButtonElement>('#tab-performance')!.click();
    fixture.detectChanges();
    const req = ctl.expectOne((r) => r.url === '/api/v1/insights/performance');
    expect(req.request.params.get('days')).toBe('30');
    req.flush({ asOf: '2026-11-04T09:59:50Z', days: 30, overall: { name: 'All', decided: 4, approvalRate: 0.75, avgHoursToFirstPickup: null, medianHoursToFirstPickup: null, avgHoursToDecision: 50, avgHoursToSettlement: null }, byMarket: [], byOfficer: [] });
    await flush(fixture);
    const cells = Array.from(el.querySelectorAll('table[aria-label=Overall] tbody td')).map((c) => c.textContent!.trim());
    expect(cells).toEqual(['All', '4', '75%', '–', '–', '2.1 d', '–']);
  });
});
