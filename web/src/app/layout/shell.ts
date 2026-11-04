import { A11yModule } from '@angular/cdk/a11y';
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LucideAngularModule, LogOut, Menu, Moon, Sun, User, X, Shield } from 'lucide-angular';
import { AuthService } from '../core/auth/auth.service';
import { UiButton } from '../shared/ui/button';
import { NAV_ACTIVATE, NAV_BADGES, NavBadge, NavConfig, NavItem } from './nav';
import { ThemeService } from './theme.service';

export const PRODUCT_NAME = 'Claims Platform';

/** Layout per FleetROS dashboard-layout.tsx: w-64 sidebar, sticky h-16 top bar, mobile drawer, py-10 content. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LucideAngularModule, A11yModule, CdkMenuTrigger, CdkMenu, CdkMenuItem, UiButton],
  templateUrl: './shell.html',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly nav: NavConfig = inject(ActivatedRoute).snapshot.data['nav'];

  protected readonly product = PRODUCT_NAME;
  protected readonly area = this.nav.area;
  protected readonly items = computed<readonly NavItem[]>(() => {
    const role = this.auth.role();
    return this.nav.items.filter((i) => !i.roles || (role !== null && i.roles.includes(role)));
  });
  private readonly badges = inject(NAV_BADGES);
  protected badge(item: NavItem): NavBadge {
    return item.badge ? (this.badges[item.badge]?.() ?? null) : null;
  }
  protected readonly drawerOpen = signal(false);

  constructor() {
    // Anything the nav tree polls lives and dies with the shell, so it cannot outlive a sign-out.
    inject(NAV_ACTIVATE, { optional: true })?.();
  }

  protected readonly icons = { Menu, X, Sun, Moon, User, LogOut, Shield };

  /** The skip link focuses `main` instead of navigating: with `<base href="/">` a bare `#main` leaves the page. */
  protected skipToMain(e: Event): void {
    e.preventDefault();
    const main = document.getElementById('main');
    main?.focus();
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  protected signOut(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
