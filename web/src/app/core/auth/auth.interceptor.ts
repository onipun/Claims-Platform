import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const LOGIN_PATH = '/api/v1/auth/login';
/** insights-service answers its own 401s (plan 6.5: after a restart of both services); that is "dashboards unavailable", not a dead session. */
const INSIGHTS_PREFIX = '/api/v1/insights/';

/** The request's path if it targets this origin's /api, else null (absolute URLs to other hosts, or non-API paths). */
function sameOriginApiPath(url: string): string | null {
  try {
    const u = new URL(url, window.location.origin);
    return u.origin === window.location.origin && (u.pathname === '/api' || u.pathname.startsWith('/api/'))
      ? u.pathname
      : null;
  } catch {
    return null;
  }
}

/**
 * Bearer only for same-origin /api: a token must never leak to another host (or to an
 * attacker-supplied absolute URL). A 401 from the API means the session is gone: clear it and go to
 * /login with a return URL (login's own 401 is "wrong password" and is left to the form).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const path = sameOriginApiPath(req.url);
  if (path === null) return next(req);

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();
  const authed = token && !req.headers.has('Authorization') ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authed).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && path !== LOGIN_PATH && !path.startsWith(INSIGHTS_PREFIX)) {
        // A late 401 for a token that is no longer current must not end the newer session.
        if ((auth.session()?.token ?? null) !== token) return throwError(() => err);
        const returnUrl = router.url && router.url !== '/' && !router.url.startsWith('/login') ? router.url : undefined;
        auth.expire();
        void router.navigate(['/login'], { queryParams: returnUrl ? { returnUrl } : {} });
      }
      return throwError(() => err);
    }),
  );
};
