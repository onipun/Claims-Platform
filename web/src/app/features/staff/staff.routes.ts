import { Routes } from '@angular/router';

/**
 * Children of /staff (the shell, role guard and nav are set in app.routes.ts).
 * Officers is merged into Insights > Workload (the nav entry is removed); /staff/officers redirects there so old links work.
 */
export const STAFF_ROUTES: Routes = [
  { path: '', title: 'Overview - Claims Platform', loadComponent: () => import('./overview.page').then((m) => m.OverviewPage) },
  { path: 'claims', title: 'Claims - Claims Platform', loadComponent: () => import('./queue.page').then((m) => m.QueuePage) },
  { path: 'claims/:id', title: 'Claim - Claims Platform', loadComponent: () => import('./claim-workspace.page').then((m) => m.ClaimWorkspacePage) },
  { path: 'insights', title: 'Insights - Claims Platform', loadComponent: () => import('./insights.page').then((m) => m.InsightsPage) },
  { path: 'officers', redirectTo: () => '/staff/insights?tab=workload' },
];
