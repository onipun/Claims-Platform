import { Routes } from '@angular/router';

const T = (t: string) => `${t} - Claims Platform`;

export const CLAIMANT_ROUTES: Routes = [
  { path: '', title: T('Overview'), loadComponent: () => import('./overview.page').then((m) => m.OverviewPage) },
  { path: 'claims', title: T('My claims'), loadComponent: () => import('./claims-list.page').then((m) => m.ClaimsListPage) },
  { path: 'claims/:id', title: T('Claim'), loadComponent: () => import('./claim-detail.page').then((m) => m.ClaimDetailPage) },
  { path: 'new', title: T('Report a claim'), loadComponent: () => import('./report-claim.page').then((m) => m.ReportClaimPage) },
  { path: 'policies', title: T('Policies'), loadComponent: () => import('./policies.page').then((m) => m.PoliciesPage) },
  { path: 'updates', title: T('Updates'), loadComponent: () => import('./updates.page').then((m) => m.UpdatesPage) },
];
