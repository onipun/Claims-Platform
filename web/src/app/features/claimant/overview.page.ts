import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule, AlertCircle, FilePlus2 } from 'lucide-angular';
import { AuthService } from '../../core/auth/auth.service';
import { loadable } from '../../core/util/loadable';
import { UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { formatWhen } from './claimant.format';
import { claimTypeLabel } from './claimant.labels';
import type { ClaimPage, UpdatePage } from './claimant.types';

interface Overview {
  claims: ClaimPage;
  updates: UpdatePage;
}

@Component({
  selector: 'app-overview-page',
  imports: [RouterLink, LucideAngularModule, UI],
  template: `
    <div class="mx-auto max-w-5xl space-y-8">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-2xl font-bold tracking-tight">Welcome{{ firstName() ? ', ' + firstName() : '' }}</h1>
          <p class="text-sm text-muted-foreground">Where your claims stand, and what we need from you.</p>
        </div>
        <a uiButton routerLink="/claimant/new"><lucide-icon [img]="icons.FilePlus2" class="mr-2 h-4 w-4" aria-hidden="true" />Report a claim</a>
      </div>

      @switch (data.state().status) {
        @case ('loading') {
          <div class="space-y-4" aria-busy="true"><ui-skeleton-card /><ui-skeleton-card /></div>
        }
        @case ('error') {
          <section uiCard><ui-error-state [headingLevel]="2" [message]="data.error()?.detail ?? data.error()?.title ?? ''" [errorId]="data.error()?.errorId" (retry)="data.load()" /></section>
        }
        @case ('ready') {
          @if (data.data(); as d) {
            @if (truncated(); as t) {
              <ui-warning-notice data-testid="claims-truncated">This page reads your {{ t.shown }} most recent claims, and you have {{ t.total }}. <a routerLink="/claimant/claims" class="underline">See all claims</a> to check the rest.</ui-warning-notice>
            }
            <section aria-labelledby="needs-action">
              <h2 id="needs-action" class="mb-3 text-lg font-semibold">Needs your attention</h2>
              @if (action().length) {
                <ul class="space-y-3" data-testid="action-list">
                  @for (c of action(); track c.id) {
                    <li uiCard class="border-orange-300 dark:border-orange-800">
                      <a [routerLink]="['/claimant/claims', c.id]" class="flex flex-wrap items-center justify-between gap-3 p-4 hover:bg-muted/40">
                        <span class="flex items-center gap-3">
                          <lucide-icon [img]="icons.AlertCircle" class="h-5 w-5 flex-shrink-0 text-orange-600" aria-hidden="true" />
                          <span>
                            <span class="block font-medium">{{ c.claimNumber }}</span>
                            <span class="block text-sm text-muted-foreground">{{ type(c.claimType) }}: we are waiting for something from you</span>
                          </span>
                        </span>
                        <claim-status-badge [status]="c.status" />
                      </a>
                    </li>
                  }
                </ul>
              } @else {
                <p class="rounded-lg border border-dashed p-6 text-sm text-muted-foreground">Nothing needs your attention right now.</p>
              }
            </section>

            <section aria-labelledby="open-claims">
              <div class="mb-3 flex items-center justify-between"><h2 id="open-claims" class="text-lg font-semibold">Your other claims</h2><a routerLink="/claimant/claims" class="text-sm text-primary underline-offset-4 hover:underline">See all</a></div>
              @if (others().length) {
                <ul uiCard class="divide-y">
                  @for (c of others(); track c.id) {
                    <li>
                      <a [routerLink]="['/claimant/claims', c.id]" class="flex items-center justify-between gap-3 p-4 hover:bg-muted/40">
                        <span class="min-w-0"><span class="block font-medium">{{ c.claimNumber }}</span><span class="block truncate text-sm text-muted-foreground">{{ type(c.claimType) }}</span></span>
                        <claim-status-badge [status]="c.status" />
                      </a>
                    </li>
                  }
                </ul>
              } @else if (!action().length) {
                <ui-empty-state title="You have not reported a claim yet" description="If something has happened to your vehicle or property, report it and we will take it from there.">
                  <a uiButton routerLink="/claimant/new">Report a claim</a>
                </ui-empty-state>
              } @else {
                <p class="text-sm text-muted-foreground">No other claims.</p>
              }
            </section>

            <section aria-labelledby="recent">
              <div class="mb-3 flex items-center justify-between"><h2 id="recent" class="text-lg font-semibold">Recent updates</h2><a routerLink="/claimant/updates" class="text-sm text-primary underline-offset-4 hover:underline">See all</a></div>
              @if (d.updates.items?.length) {
                <ol uiCard class="divide-y" data-testid="recent-updates">
                  @for (u of d.updates.items; track u.id) {
                    <li class="space-y-1 p-4">
                      <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                        <a [routerLink]="['/claimant/claims', u.claimId]" class="font-medium text-primary underline-offset-4 hover:underline">{{ u.claimNumber }}</a>
                        @if (u.toStatus) { <claim-status-badge [status]="u.toStatus" /> }
                      </div>
                      <p class="break-words text-sm">{{ u.message }}</p>
                      <p class="text-xs text-muted-foreground">{{ when(u.at) }}</p>
                    </li>
                  }
                </ol>
              } @else {
                <p class="text-sm text-muted-foreground">No updates yet.</p>
              }
            </section>
          }
        }
      }
    </div>
  `,
})
export class OverviewPage {
  private readonly api = inject(ClaimantApi);
  private readonly auth = inject(AuthService);
  protected readonly icons = { AlertCircle, FilePlus2 };
  protected readonly firstName = computed(() => this.auth.name().split(' ')[0] ?? '');
  protected readonly data = loadable<Overview>(async () => {
    const [claims, updates] = await Promise.all([this.api.listClaims(0, 50), this.api.updates(0, 5)]);
    return { claims, updates };
  });
  protected readonly action = computed(() => (this.data.data()?.claims.items ?? []).filter((c) => c.actionRequired));
  protected readonly others = computed(() => (this.data.data()?.claims.items ?? []).filter((c) => !c.actionRequired).slice(0, 5));
  /** The overview reads one page of claims; say so when there are more than it shows. */
  protected readonly truncated = computed(() => {
    const c = this.data.data()?.claims;
    const total = c?.totalElements ?? 0;
    const shown = c?.items?.length ?? 0;
    return total > shown ? { shown, total } : null;
  });
  protected readonly type = claimTypeLabel;
  protected readonly when = (v: string | undefined) => formatWhen(v);

  constructor() {
    void this.data.load();
  }
}
