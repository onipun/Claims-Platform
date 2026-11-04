import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { loadable } from '../../core/util/loadable';
import { UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { formatDay } from './claimant.format';
import { lineLabel } from './claimant.labels';
import type { Policy } from './claimant.types';

@Component({
  selector: 'app-policies-page',
  imports: [RouterLink, UI],
  template: `
    <div class="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">Policies</h1>
        <p class="text-sm text-muted-foreground">The policies you can report a claim against.</p>
      </div>

      @switch (list.state().status) {
        @case ('loading') {
          <div class="grid gap-4 md:grid-cols-2" aria-busy="true"><ui-skeleton-card /><ui-skeleton-card /></div>
        }
        @case ('error') {
          <section uiCard><ui-error-state [headingLevel]="2" [message]="list.error()?.detail ?? list.error()?.title ?? ''" [errorId]="list.error()?.errorId" (retry)="list.load()" /></section>
        }
        @case ('empty') {
          <ui-empty-state [headingLevel]="2" title="No policies found" description="We could not find a policy linked to your account. Contact your insurer if you expect to see one here." />
        }
        @case ('ready') {
          <ul class="grid gap-4 md:grid-cols-2" data-testid="policy-list">
            @for (p of list.data(); track p.id) {
              <li uiCard>
                <div uiCardHeader>
                  <div class="flex items-start justify-between gap-2">
                    <h2 uiCardTitle class="break-all text-lg">{{ p.policyNumber }}</h2>
                    <span uiBadge variant="secondary">{{ line(p.line) }}</span>
                  </div>
                  <p uiCardDescription>{{ day(p.effectiveFrom) }} to {{ day(p.effectiveTo) }}</p>
                </div>
                <dl uiCardContent class="grid grid-cols-2 gap-4 text-sm">
                  <div><dt class="text-muted-foreground">Coverage limit</dt><dd class="font-medium">{{ p.coverageLimit | money: p.currency }}</dd></div>
                  <div><dt class="text-muted-foreground">Deductible</dt><dd class="font-medium">{{ p.deductible | money: p.currency }}</dd></div>
                </dl>
                <div uiCardFooter>
                  <a uiButton variant="outline" size="sm" routerLink="/claimant/new" [queryParams]="{ policyId: p.id }">Report a claim</a>
                </div>
              </li>
            }
          </ul>
        }
      }
    </div>
  `,
})
export class PoliciesPage {
  private readonly api = inject(ClaimantApi);
  protected readonly list = loadable<Policy[]>(() => this.api.policies());
  protected readonly line = lineLabel;
  protected readonly day = (v: string | undefined) => formatDay(v);

  constructor() {
    void this.list.load();
  }
}
