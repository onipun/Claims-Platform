import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { makeToken } from '../../../testing/token';
import { AuthService, SESSION_KEY, decodeToken } from './auth.service';
import { authInterceptor } from './auth.interceptor';

@Component({ template: '' })
class Blank {}

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctl: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    sessionStorage.setItem(SESSION_KEY, makeToken({ role: 'OFFICER' }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'login', component: Blank },
          { path: 'staff/claims', component: Blank },
        ]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    ctl = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });
  afterEach(() => {
    ctl.verify();
    sessionStorage.clear();
  });

  it('restores the session from sessionStorage', () => {
    expect(auth.role()).toBe('OFFICER');
    expect(auth.isValid()).toBe(true);
  });

  it('adds the bearer to a relative /api request', () => {
    http.get('/api/v1/staff/claims').subscribe();
    const req = ctl.expectOne('/api/v1/staff/claims');
    expect(req.request.headers.get('Authorization')).toMatch(/^Bearer .+\..+\..+/);
    req.flush({});
  });

  it('adds the bearer to an absolute same-origin /api request', () => {
    const url = `${window.location.origin}/api/v1/insights/exposure`;
    http.get(url).subscribe();
    const req = ctl.expectOne(url);
    expect(req.request.headers.has('Authorization')).toBe(true);
    req.flush({});
  });

  it('does NOT add the bearer to an external origin, even with an /api path', () => {
    http.get('https://evil.example/api/v1/steal').subscribe();
    const req = ctl.expectOne('https://evil.example/api/v1/steal');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('does NOT add the bearer to same-origin non-API paths or look-alike prefixes', () => {
    for (const url of ['/assets/data.json', '/apiary/x']) {
      http.get(url).subscribe();
      const req = ctl.expectOne(url);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush({});
    }
  });

  it('on 401 clears the session and goes to /login with a return URL', async () => {
    await router.navigateByUrl('/staff/claims');
    http.get('/api/v1/staff/claims').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/staff/claims').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((r) => setTimeout(r));
    expect(auth.isValid()).toBe(false);
    expect(sessionStorage.getItem(SESSION_KEY)).toBeNull();
    expect(router.url).toBe('/login?returnUrl=%2Fstaff%2Fclaims');
  });

  it('a 401 from the login call itself is left to the form (no redirect, no clear)', async () => {
    await router.navigateByUrl('/staff/claims');
    http.post('/api/v1/auth/login', {}).subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/auth/login').flush({ code: 'INVALID_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((r) => setTimeout(r));
    expect(router.url).toBe('/staff/claims');
    expect(auth.isValid()).toBe(true);
  });

  it('an expired stored token is not restored', () => {
    sessionStorage.setItem(SESSION_KEY, makeToken({ expInSeconds: -10 }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    expect(TestBed.inject(AuthService).isValid()).toBe(false);
  });

  it('a 401 from insights-service does not end the session or redirect', async () => {
    await router.navigateByUrl('/staff/claims');
    let status = 0;
    http.get('/api/v1/insights/exposure').subscribe({ error: (e) => (status = e.status) });
    ctl.expectOne('/api/v1/insights/exposure').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((r) => setTimeout(r));
    expect(status).toBe(401);
    expect(auth.isValid()).toBe(true);
    expect(sessionStorage.getItem(SESSION_KEY)).not.toBeNull();
    expect(router.url).toBe('/staff/claims');
  });

  it('a claims-service 401 still signs out, and the login page can say the session expired', async () => {
    await router.navigateByUrl('/staff/claims');
    http.get('/api/v1/staff/claims').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/staff/claims').flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((r) => setTimeout(r));
    expect(auth.isValid()).toBe(false);
    expect(auth.expired()).toBe(true);
  });

  it('a late 401 for an old token leaves a newer session alone', async () => {
    await router.navigateByUrl('/staff/claims');
    http.get('/api/v1/staff/claims').subscribe({ error: () => undefined });
    const pending = ctl.expectOne('/api/v1/staff/claims'); // sent with the old token
    // Someone else signs in on this tab before the old request answers.
    const newer = makeToken({ role: 'OFFICER', sub: 'newer-user' });
    (auth as unknown as { start(s: unknown): void }).start(decodeToken(newer));
    pending.flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await new Promise((r) => setTimeout(r));
    expect(auth.session()?.token).toBe(newer);
    expect(auth.isValid()).toBe(true);
    expect(router.url).toBe('/staff/claims');
  });
});
