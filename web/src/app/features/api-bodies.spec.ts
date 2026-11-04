import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ProblemError } from '../core/api/problem-error';
import { ClaimantApi } from './claimant/claimant.api';
import { StaffApi } from './staff/staff.api';

describe('API wrappers: empty bodies and encoded ids', () => {
  let ctl: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    ctl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => ctl.verify());

  it('staff: a 200 with no body is an error, not a blank claim', async () => {
    const p = TestBed.inject(StaffApi).getClaim('c1');
    ctl.expectOne('/api/v1/staff/claims/c1').flush(null);
    await expect(p).rejects.toMatchObject({ code: 'EMPTY_RESPONSE' });
    await p.catch((e) => expect(e).toBeInstanceOf(ProblemError));
  });

  it('staff: ids are encoded in the path', async () => {
    const p = TestBed.inject(StaffApi).financials('a/b?c');
    ctl.expectOne('/api/v1/staff/claims/a%2Fb%3Fc/financials').flush({});
    await p;
  });

  it('claimant: getClaim and answer reject an empty body', async () => {
    const api = TestBed.inject(ClaimantApi);
    const g = api.getClaim('c1');
    ctl.expectOne('/api/v1/claimant/claims/c1').flush(null);
    await expect(g).rejects.toMatchObject({ code: 'EMPTY_RESPONSE' });
    const a = api.answer('c1', 'x', '"1"');
    await Promise.resolve();
    ctl.expectOne('/api/v1/claimant/claims/c1/info-responses').flush(null);
    await expect(a).rejects.toMatchObject({ code: 'EMPTY_RESPONSE' });
  });
});
