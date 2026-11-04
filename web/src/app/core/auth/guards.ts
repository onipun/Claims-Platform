import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { Role, homeFor } from './roles';

/** Only accept in-app paths as return targets: no scheme, no `//host` open redirects. */
export function safeReturnUrl(url: string | null | undefined): string | null {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.includes('\\') ? url : null;
}

/**
 * Role gate for a route tree. Anonymous -> /login with the wanted URL; wrong role -> that role's own home.
 * This is navigation convenience only: claims-service and insights-service enforce roles and markets.
 */
export function roleGuard(...allowed: Role[]): CanActivateFn {
  return (_route, state): true | UrlTree => {
    const auth = inject(AuthService);
    const router = inject(Router);
    if (!auth.isValid()) {
      const returnUrl = safeReturnUrl(state.url);
      return router.createUrlTree(['/login'], { queryParams: returnUrl && returnUrl !== '/' ? { returnUrl } : {} });
    }
    const role = auth.role()!;
    return allowed.includes(role) ? true : router.createUrlTree([homeFor(role)]);
  };
}

/** `/` -> the signed-in role's home, or /login. */
export const homeRedirectGuard: CanActivateFn = (): UrlTree => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.isValid() ? homeFor(auth.role()) : '/login']);
};

/** /login while already signed in -> own home. */
export const anonymousOnlyGuard: CanActivateFn = (): true | UrlTree => {
  const auth = inject(AuthService);
  return auth.isValid() ? inject(Router).createUrlTree([homeFor(auth.role())]) : true;
};
