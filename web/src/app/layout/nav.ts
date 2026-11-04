import { BarChart3, FileText, FilePlus2, LayoutDashboard, LucideIconData, ScrollText, Bell } from 'lucide-angular';
import { InjectionToken, Signal } from '@angular/core';
import type { Role } from '../core/auth/roles';

export interface NavItem {
  label: string;
  path: string;
  icon: LucideIconData;
  /** Match the path exactly (for the tree's home entry). */
  exact?: boolean;
  /** Visible only to these roles; omitted = every role that can enter the tree. */
  roles?: readonly Role[];
  /** Key into NAV_BADGES: a count shown next to the label when above zero. */
  badge?: string;
}

/** Per-tree badge counts, provided on the route that owns the shell. Absent keys show no badge. */
/** What a nav badge shows: `text` on screen, `label` read after it by a screen reader. Null = no badge. */
export type NavBadge = { text: string; label: string } | null;

export const NAV_BADGES = new InjectionToken<Record<string, Signal<NavBadge>>>('NAV_BADGES', {
  providedIn: 'root',
  factory: () => ({}),
});

/** Optional hook a nav tree provides; the shell runs it once when it is created (for example to start polling). */
export const NAV_ACTIVATE = new InjectionToken<() => void>('NAV_ACTIVATE');

export interface NavConfig {
  /** Shown under the product name, e.g. the area. */
  area: string;
  items: readonly NavItem[];
}

export const CLAIMANT_NAV: NavConfig = {
  area: 'Claimant',
  items: [
    { label: 'Overview', path: '/claimant', icon: LayoutDashboard, exact: true },
    { label: 'My claims', path: '/claimant/claims', icon: FileText },
    { label: 'New claim', path: '/claimant/new', icon: FilePlus2 },
    { label: 'Policies', path: '/claimant/policies', icon: ScrollText },
    { label: 'Updates', path: '/claimant/updates', icon: Bell, badge: 'updates' },
  ],
};

export const STAFF_NAV: NavConfig = {
  area: 'Staff',
  items: [
    { label: 'Overview', path: '/staff', icon: LayoutDashboard, exact: true },
    { label: 'Claims', path: '/staff/claims', icon: FileText },
    { label: 'Insights', path: '/staff/insights', icon: BarChart3 },
  ],
};
