import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { makeToken } from '../../../testing/token';
import { authInterceptor } from '../../core/auth/auth.interceptor';
import { AuthService, SESSION_KEY } from '../../core/auth/auth.service';
import { INSIGHTS_NOW, InsightsPage } from './insights.page';
import { flush } from './testing';
import type { ExposureResponse } from './staff.api';

@Component({ template: '' })
class Blank {}

const NOW = Date.parse('2026-11-04T10:00:00Z');
const exposure = (basis: string): ExposureResponse => ({
  asOf: '2026-11-04T09:59:50Z', basis: 'Case reserves. Excludes IBNR; not total liability.',
  rows: [{ market: basis, line: 'MOTOR', currency: 'SGD', openClaims: 1, caseOutstanding: '1', provisional: '0', assessed: '1', paid: '0', incurred: '1' }], totalsByCurrency: [],
});

describe('InsightsPage: late responses and 401', () => {
  let ctl: HttpTestingController;
  let fixture: ComponentFixture<InsightsPage>;
  let el: HTMLElement;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem(SESSION_KEY, makeToken({ role: 'MANAGER', markets: ['SG', 'ID'] }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', component: Blank }, { path: 'staff/insights', component: Blank }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: INSIGHTS_NOW, useValue: () => NOW },
      ],
    });
    ctl = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(InsightsPage);
    el = fixture.nativeElement;
  });
  afterEach(() => {
    fixture.destroy();
    sessionStorage.clear();
  });

  const exposureReqs = () => ctl.match((r) => r.url === '/api/v1/insights/exposure');
  const byMarket = (reqs: ReturnType<typeof exposureReqs>, m: string) => reqs.find((r) => r.request.params.getAll('market')?.[0] === m)!;
  const setMarket = (m: string) => (fixture.componentInstance as unknown as { setMarket(v: string): void }).setMarket(m);

  it('a late response for the old market is dropped', async () => {
    fixture.detectChanges();
    exposureReqs()[0].flush(exposure('all markets'));
    await flush(fixture);

    setMarket('SG');
    const sg = byMarket(exposureReqs(), 'SG');
    setMarket('ID');
    const id = byMarket(exposureReqs(), 'ID');

    id.flush(exposure('ID figures'));
    await flush(fixture);
    sg.flush(exposure('SG figures')); // the old market answers last
    await flush(fixture);

    expect(el.textContent).toContain('ID figures');
    expect(el.textContent).not.toContain('SG figures');
    expect(el.querySelector('[aria-busy=true]')).toBeNull();
  });

  it('a request still in flight when the market changes cannot show its figures under the new filter', async () => {
    fixture.detectChanges();
    const first = exposureReqs()[0];
    setMarket('SG');
    const sg = byMarket(exposureReqs(), 'SG');
    first.flush(exposure('all-markets figures')); // belongs to the old filter
    await flush(fixture);
    expect(el.textContent).not.toContain('all-markets figures');
    sg.flush(exposure('SG figures'));
    await flush(fixture);
    expect(el.textContent).toContain('SG figures');
  });

  it('a 401 from insights does not sign the user out and shows the unavailable state', async () => {
    await router.navigateByUrl('/staff/insights');
    const auth = TestBed.inject(AuthService);
    fixture.detectChanges();
    exposureReqs()[0].flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await flush(fixture);

    expect(auth.isValid()).toBe(true);
    expect(sessionStorage.getItem(SESSION_KEY)).not.toBeNull();
    expect(router.url).toBe('/staff/insights');
    expect(el.querySelector('[data-testid=unavailable]')).not.toBeNull();
  });

  it('a later result of the other kind replaces the earlier error', async () => {
    fixture.detectChanges();
    exposureReqs()[0].flush({ status: 500, code: 'BOOM', title: 'Server error' }, { status: 500, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=unavailable]')).toBeNull();
    expect(el.textContent).toContain('Server error');

    (fixture.componentInstance as unknown as { reload(): Promise<void> }).reload();
    exposureReqs()[0].flush({ status: 503, code: 'DOWN', title: 'x' }, { status: 503, statusText: 'x' });
    await flush(fixture);
    expect(el.querySelector('[data-testid=unavailable]')).not.toBeNull();
    expect(el.textContent).not.toContain('Server error');
  });
});
