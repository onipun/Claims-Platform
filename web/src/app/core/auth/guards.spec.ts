import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { makeToken } from '../../../testing/token';
import { AuthService, SESSION_KEY } from './auth.service';
import { anonymousOnlyGuard, homeRedirectGuard, roleGuard, safeReturnUrl } from './guards';
import type { Role } from './roles';

const ROLES: Role[] = ['CLAIMANT', 'OFFICER', 'MANAGER'];

function setup(role: Role | null) {
  sessionStorage.clear();
  if (role) sessionStorage.setItem(SESSION_KEY, makeToken({ role }));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  TestBed.inject(AuthService);
  return TestBed.inject(Router);
}

function run(guard: ReturnType<typeof roleGuard>, url: string): boolean | UrlTree {
  return TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)) as boolean | UrlTree;
}
const asPath = (r: boolean | UrlTree) => (typeof r === 'boolean' ? r : TestBed.inject(Router).serializeUrl(r));

describe('roleGuard', () => {
  afterEach(() => sessionStorage.clear());

  const matrix: [Role, string, boolean | string][] = [
    ['CLAIMANT', '/claimant/claims', true],
    ['CLAIMANT', '/staff/claims', '/claimant'],
    ['OFFICER', '/staff/claims', true],
    ['OFFICER', '/claimant/claims', '/staff'],
    ['MANAGER', '/staff/insights', true],
    ['MANAGER', '/claimant/claims', '/staff'],
  ];
  for (const [role, url, expected] of matrix) {
    it(`${role} -> ${url}: ${expected === true ? 'allowed' : 'sent to ' + expected}`, () => {
      setup(role);
      const guard = url.startsWith('/claimant') ? roleGuard('CLAIMANT') : roleGuard('OFFICER', 'MANAGER');
      expect(asPath(run(guard, url))).toBe(expected);
    });
  }

  it('MANAGER-only route sends an OFFICER to their home', () => {
    setup('OFFICER');
    expect(asPath(run(roleGuard('MANAGER'), '/staff/officers'))).toBe('/staff');
  });

  it('anonymous -> /login with the wanted URL as returnUrl', () => {
    setup(null);
    expect(asPath(run(roleGuard('CLAIMANT'), '/claimant/claims'))).toBe('/login?returnUrl=%2Fclaimant%2Fclaims');
  });

  it('expired session counts as anonymous', () => {
    sessionStorage.setItem(SESSION_KEY, makeToken({ expInSeconds: -5 }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    expect(asPath(run(roleGuard('CLAIMANT'), '/claimant'))).toBe('/login?returnUrl=%2Fclaimant');
  });
});

describe('homeRedirectGuard and anonymousOnlyGuard', () => {
  afterEach(() => sessionStorage.clear());
  const homes: Record<Role, string> = { CLAIMANT: '/claimant', OFFICER: '/staff', MANAGER: '/staff' };
  for (const role of ROLES) {
    it(`/ redirects ${role} to ${homes[role]}; /login bounces them there`, () => {
      setup(role);
      expect(asPath(run(homeRedirectGuard, '/'))).toBe(homes[role]);
      expect(asPath(run(anonymousOnlyGuard, '/login'))).toBe(homes[role]);
    });
  }
  it('/ redirects anonymous to /login; /login is open', () => {
    setup(null);
    expect(asPath(run(homeRedirectGuard, '/'))).toBe('/login');
    expect(run(anonymousOnlyGuard, '/login')).toBe(true);
  });
});

describe('safeReturnUrl', () => {
  it('accepts in-app paths only', () => {
    expect(safeReturnUrl('/staff/claims?x=1')).toBe('/staff/claims?x=1');
    for (const bad of ['//evil.example', 'https://evil.example', '/\\evil', 'javascript:alert(1)', '', null, undefined]) {
      expect(safeReturnUrl(bad as string)).toBeNull();
    }
  });
});
