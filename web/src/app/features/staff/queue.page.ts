import { Component, ElementRef, Injector, afterNextRender, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { LucideAngularModule, Loader2, RefreshCw } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { AuthService } from '../../core/auth/auth.service';
import { requestGuard } from '../../core/util/latest';
import { poll } from '../../core/util/poll';
import { Announcer, ToastService, UI } from '../../shared/ui';
import { CLAIM_STATUSES, ClaimPage, ClaimStatus, Queue, StaffApi, StaffClaimSummary, etagForVersion } from './staff.api';
import { ageOf, dueIn, label } from './format';

const PAGE_SIZE = 20;

/** Work queue. Staff reads come from claims-service (D5), so a pick-up is visible at once. */
@Component({
  selector: 'app-staff-queue',
  imports: [RouterLink, LucideAngularModule, UI],
  template: `
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h1 class="text-2xl font-bold tracking-tight">Claims</h1>
      <!-- Not a live region: the timestamp changes every poll and would be read out each time (A5). Count and errors go through the Announcer. -->
      <div class="flex items-center gap-2 text-xs text-muted-foreground">
        @if (refreshing()) {
          <lucide-icon [img]="icons.Loader2" class="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" /> Refreshing
        } @else if (updatedAt()) {
          Updated {{ updatedAt() }}. Refreshes every 30 s while this tab is visible.
        }
        <button uiButton variant="ghost" size="sm" type="button" (click)="reload(true)">
          <lucide-icon [img]="icons.RefreshCw" class="mr-1 h-4 w-4" aria-hidden="true" /> Refresh
        </button>
      </div>
    </div>

    @if (outOfDateSince(); as since) {
      <div role="status" data-testid="out-of-date" class="mb-4 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100">
        Out of date since {{ since }}, retrying. The list below may not show recent changes.
      </div>
    }

    <div class="mb-4 flex flex-wrap items-center gap-3">
      <ui-tabs label="Queue" [tabs]="tabs()" [value]="queue()" (valueChange)="setQueue($event)" />
      <ui-form-field label="Status" class="w-48">
        <select uiSelect data-testid="status-filter" [value]="status()" (change)="setStatus($any($event.target).value)">
          <option value="">Any status</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ labelOf(s) }}</option>
          }
        </select>
      </ui-form-field>
    </div>

    <section uiCard [id]="'panel-' + queue()" role="tabpanel" [attr.aria-labelledby]="'tab-' + queue()" [attr.tabindex]="loading() || rows().length === 0 ? 0 : -1" class="focus:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      @if (loading()) {
        <div class="p-6"><ui-skeleton-table [rows]="6" [columns]="7" /></div>
      } @else if (error(); as e) {
        <ui-error-state [headingLevel]="2" [title]="e.status === 403 ? 'Not allowed' : 'Could not load claims'" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="reload(true)" />
      } @else if (rows().length === 0) {
        <div class="p-6">
          <ui-empty-state [headingLevel]="2"
            [title]="queue() === 'unassigned' ? 'No unassigned claims' : 'No claims here'"
            [description]="status() ? 'Nothing matches this status. Clear the filter to see everything.' : 'New submissions in your markets will show up here.'"
          />
        </div>
      } @else {
        <div uiTableContainer>
          <table uiTable data-testid="queue-table" [attr.aria-label]="'Claims, ' + queue() + ' queue'">
            <thead uiThead>
              <tr uiTr>
                <th uiTh scope="col">Claim</th>
                <th uiTh scope="col">Market</th>
                <th uiTh scope="col">Line / type</th>
                <th uiTh scope="col">Age</th>
                <th uiTh scope="col">Status</th>
                <th uiTh scope="col">Due</th>
                <th uiTh scope="col">Flags</th>
                @if (queue() !== 'unassigned') { <th uiTh scope="col">Officer</th> }
                <th uiTh scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody uiTbody>
              @for (c of rows(); track c.id) {
                <tr uiTr [attr.data-testid]="'row-' + c.claimNumber" [class]="c.overdue ? 'bg-red-50 dark:bg-red-950/20' : ''">
                  <td uiTd class="whitespace-nowrap font-medium"><a [routerLink]="['/staff/claims', c.id]" class="text-primary underline-offset-4 hover:underline">{{ c.claimNumber }}</a></td>
                  <td uiTd>{{ c.market }}</td>
                  <td uiTd class="whitespace-nowrap">{{ labelOf(c.line) }} <span class="text-muted-foreground">/ {{ labelOf(c.claimType) }}</span></td>
                  <td uiTd class="whitespace-nowrap tabular-nums">{{ age(c) }}</td>
                  <td uiTd><claim-status-badge [status]="c.status" /></td>
                  <td uiTd class="whitespace-nowrap tabular-nums">
                    @let d = due(c);
                    <span [class]="d.overdue ? 'font-semibold text-red-700 dark:text-red-400' : ''">
                      @if (d.overdue) { <span class="sr-only">Overdue: </span> }{{ d.text }}
                    </span>
                    <span class="block text-xs text-muted-foreground">{{ d.what }}</span>
                  </td>
                  <td uiTd>
                    <div class="flex flex-wrap gap-1">
                      @if (c.overdue) { <span uiBadge variant="destructive" class="whitespace-nowrap">Overdue</span> }
                      @if (c.waitingOnClaimant) { <span uiBadge variant="secondary" class="whitespace-nowrap">Waiting on claimant</span> }
                      @if (c.late) { <span uiBadge variant="outline" class="whitespace-nowrap border-amber-500 text-amber-700 dark:text-amber-300">Late report</span> }
                    </div>
                  </td>
                  @if (queue() !== 'unassigned') { <td uiTd class="whitespace-nowrap">{{ c.assignedOfficerName ?? '–' }}</td> }
                  <td uiTd class="text-right">
                    @if (queue() === 'unassigned') {
                      <button uiButton size="sm" type="button" [attr.aria-disabled]="picking() !== null ? 'true' : null" (click)="pickUp(c)" [attr.aria-label]="'Pick up ' + c.claimNumber">
                        @if (picking() === c.id) { <lucide-icon [img]="icons.Loader2" class="mr-1 h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" /> }
                        Pick up
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="flex flex-wrap items-center justify-between gap-2 border-t px-4 py-3 text-sm">
          <span class="text-muted-foreground" data-testid="page-info" tabindex="-1">
            Page {{ (page().page ?? 0) + 1 }} of {{ page().totalPages || 1 }} · {{ page().totalElements ?? 0 }} claims
          </span>
          <div class="flex gap-2">
            <button uiButton variant="outline" size="sm" type="button" [disabled]="(page().page ?? 0) <= 0" (click)="go(-1)">Previous</button>
            <button uiButton variant="outline" size="sm" type="button" [disabled]="(page().page ?? 0) + 1 >= (page().totalPages ?? 1)" (click)="go(1)">Next</button>
          </div>
        </div>
      }
    </section>
  `,
})
export class QueuePage {
  private readonly api = inject(StaffApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  /** Last result count that was announced: a poll that finds the same number says nothing. */
  private announcedTotal: number | null = null;
  /** The request parameters of the rows on screen. A failed load for other parameters must not pass them off as current. */
  private shown: string | null = null;

  protected readonly icons = { Loader2, RefreshCw };
  protected readonly statuses = CLAIM_STATUSES;
  protected readonly labelOf = label;
  protected readonly queue = signal<Queue>('unassigned');
  protected readonly status = signal<ClaimStatus | ''>('');
  protected readonly pageNo = signal(0);
  protected readonly page = signal<ClaimPage>({});
  protected readonly rows = computed<readonly StaffClaimSummary[]>(() => this.page().items ?? []);
  protected readonly loading = signal(true);
  protected readonly refreshing = signal(false);
  protected readonly error = signal<ProblemError | null>(null);
  protected readonly picking = signal<string | null>(null);
  protected readonly updatedAt = signal('');
  /** Set by a failed background refresh, cleared by the next success. */
  protected readonly outOfDateSince = signal<string | null>(null);
  /** Only the latest list request may write state; a pick-up makes earlier ones stale. */
  private readonly guard = requestGuard();
  private readonly now = signal(Date.now());

  protected readonly tabs = computed(() => [
    { id: 'unassigned', label: 'Unassigned' },
    { id: 'mine', label: 'Mine' },
    { id: 'all', label: this.auth.role() === 'MANAGER' ? 'All (team)' : 'All' },
  ]);

  constructor() {
    const q = inject(ActivatedRoute).snapshot.queryParamMap.get('queue');
    if (q === 'mine' || q === 'all' || q === 'unassigned') this.queue.set(q);
    void this.reload(false);
    poll(() => this.reload(true, true), 30_000);
  }

  protected age(c: StaffClaimSummary): string {
    return ageOf(c.reportedAt, this.now());
  }
  /** Unassigned claims are due for pick-up; claims under review are due for a decision. */
  protected due(c: StaffClaimSummary): { text: string; overdue: boolean; what: string } {
    const pickup = c.status === 'SUBMITTED';
    const d = dueIn(pickup ? c.pickupDueAt : c.decisionDueAt, this.now());
    return { ...d, overdue: d.overdue, what: pickup ? 'pick-up due' : 'decision due' };
  }

  protected setQueue(id: string): void {
    this.queue.set(id as Queue);
    this.pageNo.set(0);
    void this.reload(false);
  }
  protected setStatus(v: string): void {
    this.status.set(v as ClaimStatus | '');
    this.pageNo.set(0);
    void this.reload(false);
  }
  protected async go(delta: number): Promise<void> {
    this.pageNo.update((p) => Math.max(0, p + delta));
    // The foreground load replaces the table, buttons included. Focus then goes to the page info text, which is stable.
    if (await this.reload(false)) this.focusLater('[data-testid="page-info"]');
  }

  /** Move focus once the next render has put the element in place. */
  private focusLater(selector: string, fallback = '[role="tabpanel"]'): void {
    afterNextRender(() => (this.host.querySelector<HTMLElement>(selector) ?? this.host.querySelector<HTMLElement>(fallback))?.focus(), { injector: this.injector });
  }

  /** `quiet` keeps the rows on screen while refreshing (the 30 s poll and manual refresh). True when the list is now current. */
  protected async reload(quiet: boolean, fromPoll = false): Promise<boolean> {
    const current = this.guard.begin();
    const key = `${this.queue()}|${this.status()}|${this.pageNo()}`;
    if (quiet) this.refreshing.set(!fromPoll);
    else this.loading.set(true);
    try {
      const page = await this.api.listClaims({ queue: this.queue(), status: this.status(), page: this.pageNo(), size: PAGE_SIZE });
      if (!current()) return false;
      this.page.set(page);
      this.shown = key;
      this.error.set(null);
      this.outOfDateSince.set(null);
      this.now.set(Date.now());
      this.updatedAt.set(new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' }));
      const total = page.totalElements ?? 0;
      if (!fromPoll || total !== this.announcedTotal) {
        const filter = this.status() ? `, status ${label(this.status() as string).toLowerCase()}` : '';
        this.announcer.polite(`${total} ${total === 1 ? 'claim' : 'claims'} in the ${this.queue()} queue${filter}.`);
      }
      this.announcedTotal = total;
      return true;
    } catch (e) {
      if (!current()) return false;
      const p = ProblemError.from(e);
      // A failed background refresh of what is on screen keeps the rows and says they are old. Anything else (a foreground
      // load, or a load for other parameters than the rows show) has nothing valid to keep, so it shows the error state.
      if (!quiet || this.rows().length === 0 || this.shown !== key) {
        // The error state is role=alert: the one announcement.
        this.error.set(p);
      } else if (this.outOfDateSince() === null) {
        // The visible banner (role=status) is the one announcement for this.
        this.outOfDateSince.set(new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }));
      }
      return false;
    } finally {
      if (current()) {
        this.loading.set(false);
        this.refreshing.set(false);
      }
    }
  }

  protected async pickUp(c: StaffClaimSummary): Promise<void> {
    // aria-disabled does not stop clicks, so this guard is what stops a second pick-up while one is in flight.
    if (!c.id || this.picking()) return;
    const rows = this.rows();
    const at = rows.findIndex((r) => r.id === c.id);
    const neighbour = rows[at + 1] ?? rows[at - 1];
    this.picking.set(c.id);
    let outcome: 'picked' | 'conflict' | 'failed' = 'failed';
    try {
      await this.api.command(c.id, etagForVersion(c.version), { kind: 'pick-up' });
      outcome = 'picked';
      // It is no longer available whatever the refresh does: drop the row now, and discard any list read that started before.
      this.guard.invalidate();
      this.page.update((p) => ({ ...p, items: (p.items ?? []).filter((r) => r.id !== c.id), totalElements: Math.max(0, (p.totalElements ?? 1) - 1) }));
      this.toast.success(`You picked up ${c.claimNumber}. It is now in Mine.`);
      // The row, and the button that had focus, are gone: put focus on the next row's button, else the previous, else the table panel.
      this.focusLater(neighbour ? `[data-testid="row-${neighbour.claimNumber}"] button` : '[role="tabpanel"]');
    } catch (e) {
      const p = ProblemError.from(e);
      if (p.status === 409) outcome = 'conflict';
      else if (p.code === 'CONFLICT_OF_INTEREST') this.toast.error(`You cannot work on ${c.claimNumber}: it is on your own policy.`);
      else this.toast.error(p.detail ?? p.title);
    } finally {
      this.picking.set(null);
    }
    const refreshed = await this.reload(true);
    if (outcome === 'conflict') {
      this.toast.error(
        `${c.claimNumber} was just picked up by another officer, or changed. ${refreshed ? 'The list has been refreshed.' : 'The list could not be refreshed; reload it to see the current state.'}`,
      );
    }
  }
}
