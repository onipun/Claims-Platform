import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideAngularModule, ArrowLeft, EyeOff, Lock } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { requestGuard } from '../../core/util/latest';
import { UI } from '../../shared/ui';
import { ClaimActions } from './claim-actions';
import { ClaimDocuments } from './claim-documents';
import { DocumentChecklist, StaffApi, StaffClaimDetail, StaffFinancials, Versioned } from './staff.api';
import { dueIn, formatLocal, formatWhen, label } from './format';

/** Claim workspace. The route param `id` is bound with withComponentInputBinding. */
@Component({
  selector: 'app-claim-workspace',
  imports: [RouterLink, LucideAngularModule, UI, ClaimActions, ClaimDocuments],
  template: `
    <a routerLink="/staff/claims" class="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <lucide-icon [img]="icons.ArrowLeft" class="h-4 w-4" aria-hidden="true" /> Back to claims
    </a>

    @if (loading() && !claim()) {
      <div class="space-y-4"><ui-skeleton-card /><ui-skeleton-card /></div>
    } @else if (error(); as e) {
      <section uiCard>
        @if (e.code === 'CONFLICT_OF_INTEREST') {
          <div role="alert" data-testid="blocked" class="flex flex-col items-center gap-3 p-12 text-center">
            <lucide-icon [img]="icons.Lock" class="h-10 w-10 text-red-600" aria-hidden="true" />
            <h2 class="text-xl font-semibold">You cannot work on this claim</h2>
            <p class="max-w-md text-sm text-muted-foreground">This claim is on your own policy. To keep decisions independent, staff cannot read or act on claims where they are the policyholder. Ask a colleague or your manager.</p>
          </div>
        } @else if (e.status === 404) {
          <ui-empty-state [headingLevel]="2" title="Claim not found" description="It does not exist, or it is outside your markets." />
        } @else {
          <ui-error-state [headingLevel]="2" title="Could not load this claim" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="load()" />
        }
      </section>
    } @else if (claim(); as c) {
      @if (reloadError(); as re) {
        <div role="alert" data-testid="reload-error" class="mb-3 flex flex-wrap items-center gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100">
          Could not reload this claim ({{ re.detail ?? re.title }}). You are still seeing the version you opened, and what you typed is kept.
          <button uiButton size="sm" variant="outline" type="button" (click)="load()">Try again</button>
        </div>
      }
      <div class="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 class="text-2xl font-bold tracking-tight" data-testid="claim-number">{{ c.claimNumber }}</h1>
          <p class="text-sm text-muted-foreground">{{ labelOf(c.line) }} · {{ labelOf(c.claimType) }} · {{ c.market }}</p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <claim-status-badge [status]="c.status" />
          @if (c.deadlines?.overdue) { <span uiBadge variant="destructive">Overdue</span> }
          @if (c.deadlines?.waitingOnClaimant) { <span uiBadge variant="secondary">Waiting on claimant</span> }
          @if (c.late) { <span uiBadge variant="outline" class="border-amber-500 text-amber-700 dark:text-amber-300">Late report</span> }
        </div>
      </div>

      <div class="grid gap-4 xl:grid-cols-3">
        <div class="min-w-0 space-y-4 xl:col-span-2">
          <section uiCard aria-labelledby="sum-h">
            <div uiCardHeader><h2 id="sum-h" uiCardTitle class="text-lg">Summary</h2></div>
            <dl uiCardContent class="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
              <div><dt class="text-muted-foreground">Policy</dt><dd class="font-medium">{{ c.policyNumber }}</dd></div>
              <div><dt class="text-muted-foreground">Claimant</dt><dd class="font-medium">{{ c.claimant?.name ?? '–' }}</dd></div>
              <div><dt class="text-muted-foreground">Assigned officer</dt><dd class="font-medium">{{ c.assignedOfficer?.name ?? 'Unassigned' }}</dd></div>
              <div><dt class="text-muted-foreground">Currency</dt><dd class="font-medium">{{ c.currency }}</dd></div>
              <div><dt class="text-muted-foreground">Coverage limit</dt><dd class="font-medium tabular-nums">{{ c.ledger?.coverageLimit | money: c.currency }}</dd></div>
              <div><dt class="text-muted-foreground">Deductible</dt><dd class="font-medium tabular-nums">{{ c.ledger?.deductible | money: c.currency }}</dd></div>
              <div><dt class="text-muted-foreground">Claimant's estimate</dt><dd class="font-medium tabular-nums">{{ c.estimate | money: c.currency }}</dd></div>
              <div><dt class="text-muted-foreground">Incident (market local time)</dt><dd class="font-medium tabular-nums" data-testid="incident">{{ incident() }}</dd></div>
              <div><dt class="text-muted-foreground">Reported</dt><dd class="font-medium tabular-nums">{{ reported() }}</dd></div>
              <div>
                <dt class="text-muted-foreground">Due</dt>
                <dd class="font-medium tabular-nums">
                  {{ c.status === 'SUBMITTED' ? 'Pick-up ' + dueText(c.deadlines?.pickupDueAt) : 'Decision ' + dueText(c.deadlines?.decisionDueAt) }}
                </dd>
              </div>
              @if (c.late) {
                <div class="sm:col-span-2" data-testid="late-reason">
                  <dt class="text-muted-foreground">Reported late</dt>
                  <dd class="font-medium">{{ c.lateReason ? 'Claimant\\'s stated reason: ' + c.lateReason : 'No reason given.' }}</dd>
                </div>
              }
              @if (c.description) {
                <div class="sm:col-span-2"><dt class="text-muted-foreground">Description</dt><dd class="whitespace-pre-line break-words" data-testid="description">{{ c.description }}</dd></div>
              }
              @if (c.motor; as m) {
                <div><dt class="text-muted-foreground">Vehicle / driver</dt><dd class="font-medium">{{ m.vehicleRegistration }} · {{ m.driverName }}</dd></div>
                @if (m.location) { <div class="sm:col-span-2"><dt class="text-muted-foreground">Accident location</dt><dd class="font-medium break-words" data-testid="accident-location">{{ m.location }}</dd></div> }
              }
              @if (c.property; as p) {
                <div class="sm:col-span-2"><dt class="text-muted-foreground">Property</dt><dd class="font-medium">{{ p.address }} · {{ p.cause }}</dd></div>
              }
              @if (c.rejection; as r) {
                <div class="sm:col-span-2 rounded-md bg-red-50 p-3 dark:bg-red-950/30"><dt class="text-red-800 dark:text-red-200">Rejected: {{ labelOf(r.reasonCode) }}</dt><dd>{{ r.explanation }}</dd></div>
              }
              @if (c.openQuestion; as q) {
                <div class="sm:col-span-2 rounded-md bg-orange-50 p-3 dark:bg-orange-950/30"><dt class="text-orange-800 dark:text-orange-200">Open question to claimant ({{ when(q.askedAt) }})</dt><dd class="whitespace-pre-line break-words" data-testid="open-question">{{ q.question }}</dd></div>
              }
            </dl>
          </section>

          <section uiCard aria-labelledby="fin-h">
            <div uiCardHeader>
              <h2 id="fin-h" uiCardTitle class="text-lg">Financial position</h2>
              <p uiCardDescription>Case exposure for this claim in {{ c.currency }}. Case reserve only; it excludes IBNR.</p>
            </div>
            <div uiCardContent class="space-y-4">
              @if (finError(); as e) {
                <ui-error-state title="Could not load financials" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="loadSide()" />
              } @else if (fin(); as f) {
                <div class="grid grid-cols-2 gap-3 sm:grid-cols-4" data-testid="financials">
                  <div class="rounded-md border p-3">
                    <p class="text-xs text-muted-foreground">Reserve</p>
                    <p class="text-base font-semibold tabular-nums">{{ f.reserve | money: f.currency }}</p>
                    <span uiBadge [variant]="f.reserveStatus === 'ASSESSED' ? 'secondary' : 'outline'">{{ labelOf(f.reserveStatus) }}</span>
                  </div>
                  <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Paid</p><p class="text-base font-semibold tabular-nums">{{ f.paid | money: f.currency }}</p></div>
                  <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Case outstanding</p><p class="text-base font-semibold tabular-nums">{{ f.outstanding | money: f.currency }}</p></div>
                  <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Incurred</p><p class="text-base font-semibold tabular-nums">{{ f.incurred | money: f.currency }}</p></div>
                </div>
                <div uiTableContainer scrollRegion="Ledger history, scrollable">
                  <table uiTable aria-label="Ledger history">
                    <thead uiThead><tr uiTr>
                      <th uiTh scope="col">When</th><th uiTh scope="col">Entry</th><th uiTh scope="col" class="text-right">Amount</th><th uiTh scope="col">By</th><th uiTh scope="col">Reason</th>
                    </tr></thead>
                    <tbody uiTbody>
                      @for (e of f.entries ?? []; track e.id) {
                        <tr uiTr>
                          <td uiTd class="whitespace-nowrap tabular-nums">{{ when(e.at) }}</td>
                          <td uiTd>{{ e.type === 'PAYMENT' ? 'Payment' : 'Reserve set' }}</td>
                          <td uiTd class="text-right tabular-nums">{{ e.amount | money: f.currency }}</td>
                          <td uiTd>{{ e.actorName ?? 'System' }}</td>
                          <td uiTd class="text-muted-foreground">{{ e.reason ?? '–' }}</td>
                        </tr>
                      } @empty {
                        <tr uiTr><td uiTd colspan="5" class="text-center text-muted-foreground">No ledger entries yet.</td></tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <ui-skeleton-text [lines]="3" />
              }
            </div>
          </section>

          <section uiCard aria-labelledby="tl-h">
            <div uiCardHeader class="sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
              <h2 id="tl-h" uiCardTitle class="text-lg">Timeline</h2>
              <label class="flex items-center gap-2 text-sm">
                <input type="checkbox" [checked]="claimantOnly()" (change)="claimantOnly.set($any($event.target).checked)" /> Claimant-visible only
              </label>
            </div>
            <ol uiCardContent class="space-y-3" data-testid="timeline">
              @for (t of timeline(); track $index) {
                <li class="rounded-md border-l-4 px-3 py-2 text-sm" [class]="t.visibility === 'INTERNAL' ? 'border-l-amber-500 bg-amber-50 dark:bg-amber-950/30' : 'border-l-primary bg-muted/30'"
                  [attr.data-visibility]="t.visibility">
                  <div class="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    @if (t.visibility === 'INTERNAL') {
                      <span uiBadge variant="outline" class="gap-1 border-amber-500 text-amber-800 dark:text-amber-300"><lucide-icon [img]="icons.EyeOff" class="h-3 w-3" aria-hidden="true" />Internal</span>
                    } @else {
                      <span uiBadge variant="outline">Claimant can see</span>
                    }
                    <span class="tabular-nums">{{ when(t.at) }}</span>
                    <span>{{ t.actorName ?? 'System' }}</span>
                    @if (t.fromStatus && t.toStatus && t.toStatus !== t.fromStatus) { <span>{{ labelOf(t.fromStatus) }} → {{ labelOf(t.toStatus) }}</span> }
                  </div>
                  <p class="mt-1 whitespace-pre-line break-words" data-testid="timeline-message">{{ t.message || labelOf(t.type) }}</p>
                </li>
              } @empty {
                <li><ui-empty-state title="No activity yet" /></li>
              }
            </ol>
          </section>
        </div>

        <div class="min-w-0 space-y-4">
          <app-claim-actions [claim]="c" [etag]="etag()" (changed)="applied($event)" (reloadClaim)="load()" />
          <app-claim-documents
            [claimId]="c.id!" [checklist]="docs()" [loading]="docsLoading()" [loadError]="docsError()" [canReview]="canReview()"
            (retry)="loadSide()" (reviewed)="loadSide()" />
        </div>
      </div>
    }
  `,
})
export class ClaimWorkspacePage {
  readonly id = input.required<string>();
  private readonly api = inject(StaffApi);

  protected readonly icons = { ArrowLeft, EyeOff, Lock };
  protected readonly labelOf = label;
  protected readonly when = formatWhen;
  protected readonly claim = signal<StaffClaimDetail | null>(null);
  protected readonly etag = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ProblemError | null>(null);
  protected readonly fin = signal<StaffFinancials | null>(null);
  protected readonly finError = signal<ProblemError | null>(null);
  protected readonly docs = signal<DocumentChecklist | null>(null);
  protected readonly docsLoading = signal(true);
  protected readonly docsError = signal<ProblemError | null>(null);
  protected readonly claimantOnly = signal(false);
  /** A reload that failed while a claim was already open: the claim and the drafts stay, this explains. */
  protected readonly reloadError = signal<ProblemError | null>(null);
  /** Only the latest read may write state; an id change, or an applied command, makes earlier reads stale. */
  private readonly guard = requestGuard();
  private readonly sideGuard = requestGuard();

  protected readonly incident = computed(() => formatLocal(this.claim()?.incidentAtLocal, this.claim()?.marketZone));
  protected readonly reported = computed(() => formatLocal(this.claim()?.reportedAt, this.claim()?.marketZone));
  protected readonly timeline = computed(() => {
    const all = [...(this.claim()?.timeline ?? [])].sort((a, b) => Date.parse(b.at ?? '') - Date.parse(a.at ?? ''));
    return this.claimantOnly() ? all.filter((t) => t.visibility !== 'INTERNAL') : all;
  });
  /** Document review has no entry in allowedActions; it is offered to whoever may add notes (same people). The server decides. */
  protected readonly canReview = computed(() => !!this.claim()?.allowedActions?.includes('ADD_NOTE'));

  constructor() {
    effect(() => {
      this.id();
      untracked(() => {
        this.resetForNewClaim();
        void this.load();
      });
    });
  }

  protected dueText(iso: string | null | undefined): string {
    return dueIn(iso, Date.now()).text;
  }

  /** Another claim: nothing of the previous one may stay on screen or be acted on, and its reads must not land. */
  private resetForNewClaim(): void {
    this.guard.invalidate();
    this.sideGuard.invalidate();
    this.claim.set(null);
    this.etag.set(null);
    this.error.set(null);
    this.reloadError.set(null);
    this.fin.set(null);
    this.finError.set(null);
    this.docs.set(null);
    this.docsError.set(null);
    this.docsLoading.set(true);
    this.claimantOnly.set(false);
  }

  protected async load(): Promise<void> {
    const id = this.id();
    const current = this.guard.begin();
    this.loading.set(true);
    this.error.set(null);
    this.reloadError.set(null);
    try {
      const res = await this.api.getClaim(id);
      if (!current()) return;
      this.claim.set(res.data);
      this.etag.set(res.etag);
      void this.loadSide();
    } catch (e) {
      if (!current()) return;
      const p = ProblemError.from(e);
      // A failed reload keeps the claim (and the officer's typed drafts); only a first load shows the error page.
      if (this.claim()) this.reloadError.set(p);
      else this.error.set(p);
    } finally {
      if (current()) this.loading.set(false);
    }
  }

  protected async loadSide(): Promise<void> {
    const id = this.id();
    const current = this.sideGuard.begin();
    this.docsLoading.set(true);
    this.finError.set(null);
    this.docsError.set(null);
    await Promise.all([
      this.api.financials(id).then((f) => current() && this.fin.set(f), (e) => current() && this.finError.set(ProblemError.from(e))),
      this.api.documents(id).then((d) => current() && this.docs.set(d), (e) => current() && this.docsError.set(ProblemError.from(e))),
    ]);
    if (current()) this.docsLoading.set(false);
  }

  protected applied(res: Versioned<StaffClaimDetail>): void {
    // A command that finished after the officer moved to another claim must not land on this one.
    if (res.data.id !== this.id()) return;
    this.guard.invalidate();
    this.claim.set(res.data);
    this.etag.set(res.etag);
    this.reloadError.set(null);
    this.loading.set(false);
    void this.loadSide();
  }
}
