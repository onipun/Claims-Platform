import { Routes } from '@angular/router';
import { CLAIMANT_NAV, NAV_ACTIVATE, NAV_BADGES, STAFF_NAV } from './layout/nav';
import { claimantNavActivate, claimantNavBadges } from './features/claimant/unread.service';
import { STAFF_ROUTES } from './features/staff/staff.routes';
import { anonymousOnlyGuard, homeRedirectGuard, roleGuard } from './core/auth/guards';

const shell = () => import('./layout/shell').then((m) => m.Shell);
export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [homeRedirectGuard], children: [] },
  {
    path: 'login',
    canActivate: [anonymousOnlyGuard],
    title: 'Sign in - Claims Platform',
    loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'claimant',
    canActivate: [roleGuard('CLAIMANT')],
    loadComponent: shell,
    data: { nav: CLAIMANT_NAV },
    providers: [
      { provide: NAV_BADGES, useFactory: claimantNavBadges },
      { provide: NAV_ACTIVATE, useFactory: claimantNavActivate },
    ],
    loadChildren: () => import('./features/claimant/claimant.routes').then((m) => m.CLAIMANT_ROUTES),
  },
  {
    path: 'staff',
    canActivate: [roleGuard('OFFICER', 'MANAGER')],
    loadComponent: shell,
    data: { nav: STAFF_NAV },
    children: STAFF_ROUTES,
  },
  { path: '**', redirectTo: '' },
];
