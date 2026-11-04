import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProblemError } from '../../core/api/problem-error';
import { AuthService } from '../../core/auth/auth.service';
import { UI } from '../../shared/ui';
import { ClaimPage, StaffApi, StaffClaimSummary } from './staff.api';
import { dueIn } from './format';

const OPEN = (c: StaffClaimSummary) => c.status !== 'SETTLED' && c.status !== 'REJECTED';

/** Officer or manager home. All figures come from claims-service (live), not the insights read model. */
@Component({
  selector: 'app-staff-overview',
  imports: [RouterLink, UI],
  template: `
    <h1 class="mb-1 text-2xl font-bold tracking-tight">Overview</h1>
    <p class="mb-6 text-sm text-muted-foreground">
      {{ auth.name() }} · {{ auth.role() === 'MANAGER' ? 'Manager' : 'Officer' }} · Markets: {{ auth.markets().join(', ') || '–' }}
    </p>

    @if (loading()) {
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        @for (i of [1, 2, 3, 4]; track i) { <ui-skeleton-card /> }
      </div>
    } @else if (error(); as e) {
      <section uiCard><ui-error-state [headingLevel]="2" title="Could not load your overview" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="load()" /></section>
    } @else {
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" data-testid="overview-tiles">
        <a [routerLink]="'/staff/claims'" [queryParams]="{ queue: 'mine' }" uiCard class="block p-4 hover:bg-muted/40">
          <p class="text-sm text-muted-foreground">My open claims</p>
          <p class="text-3xl font-semibold tabular-nums" data-testid="tile-open">{{ open().length }}</p>
        </a>
        <div uiCard class="p-4" [class.border-red-300]="overdue().length > 0">
          <p class="text-sm text-muted-foreground">Overdue</p>
          <p class="text-3xl font-semibold tabular-nums" [class.text-red-700]="overdue().length > 0" data-testid="tile-overdue">{{ overdue().length }}</p>
        </div>
        <div uiCard class="p-4">
          <p class="text-sm text-muted-foreground">Waiting on claimant</p>
          <p class="text-3xl font-semibold tabular-nums" data-testid="tile-waiting">{{ waiting().length }}</p>
        </div>
        <a [routerLink]="'/staff/claims'" uiCard class="block p-4 hover:bg-muted/40">
          <p class="text-sm text-muted-foreground">Unassigned in my markets</p>
          <p class="text-3xl font-semibold tabular-nums" data-testid="tile-unassigned">{{ unassignedTotal() }}</p>
        </a>
      </div>
      @if (truncated()) {
        <ui-warning-notice class="mt-3">Counts cover your first 100 claims; open the queue for the full list.</ui-warning-notice>
      }

      <div class="mt-6 grid gap-4 lg:grid-cols-2">
        <section uiCard>
          <div uiCardHeader>
            <h2 uiCardTitle class="text-lg">Needs attention</h2>
            <p uiCardDescription>Your overdue claims and those waiting on a claimant.</p>
          </div>
          <div uiCardContent>
            @if (attention().length === 0) {
              <ui-empty-state title="Nothing needs attention" description="No overdue claims, and nothing is waiting on a claimant." />
            } @else {
              <ul class="divide-y">
                @for (c of attention(); track c.id) {
                  <li class="flex items-center justify-between gap-3 py-2 text-sm">
                    <a [routerLink]="['/staff/claims', c.id]" class="font-medium text-primary hover:underline">{{ c.claimNumber }}</a>
                    <claim-status-badge [status]="c.status" />
                    <span class="tabular-nums" [class.text-red-700]="c.overdue">{{ dueText(c) }}</span>
                  </li>
                }
              </ul>
            }
          </div>
        </section>
        <section uiCard>
          <div uiCardHeader>
            <h2 uiCardTitle class="text-lg">Quick links</h2>
            <p uiCardDescription>Where staff usually go next.</p>
          </div>
          <div uiCardContent class="flex flex-wrap gap-2">
            <a uiButton [routerLink]="'/staff/claims'">Unassigned queue</a>
            <a uiButton variant="outline" [routerLink]="'/staff/claims'" [queryParams]="{ queue: 'mine' }">My claims</a>
            <a uiButton variant="outline" [routerLink]="'/staff/insights'">Insights</a>
          </div>
        </section>
      </div>
    }
  `,
})
export class OverviewPage {
  private readonly api = inject(StaffApi);
  protected readonly auth = inject(AuthService);
  protected readonly loading = signal(true);
  protected readonly error = signal<ProblemError | null>(null);
  private readonly mine = signal<ClaimPage>({});
  protected readonly unassignedTotal = signal(0);
  protected readonly open = computed(() => (this.mine().items ?? []).filter(OPEN));
  protected readonly overdue = computed(() => this.open().filter((c) => c.overdue));
  protected readonly waiting = computed(() => this.open().filter((c) => c.waitingOnClaimant));
  protected readonly attention = computed(() => this.open().filter((c) => c.overdue || c.waitingOnClaimant).slice(0, 8));
  protected readonly truncated = computed(() => (this.mine().totalElements ?? 0) > (this.mine().items?.length ?? 0));

  constructor() {
    void this.load();
  }

  protected dueText(c: StaffClaimSummary): string {
    return dueIn(c.status === 'SUBMITTED' ? c.pickupDueAt : c.decisionDueAt, Date.now()).text;
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [mine, un] = await Promise.all([
        this.api.listClaims({ queue: 'mine', page: 0, size: 100 }),
        this.api.listClaims({ queue: 'unassigned', page: 0, size: 1 }),
      ]);
      this.mine.set(mine);
      this.unassignedTotal.set(un.totalElements ?? un.items?.length ?? 0);
    } catch (e) {
      this.error.set(ProblemError.from(e));
    } finally {
      this.loading.set(false);
    }
  }
}
