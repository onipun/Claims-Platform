import { NgTemplateOutlet } from '@angular/common';
import { Component, InjectionToken, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { LucideAngularModule, Clock, CloudOff } from 'lucide-angular';
import { AuthService } from '../../core/auth/auth.service';
import { poll } from '../../core/util/poll';
import { formatInstant } from '../../core/util/time';
import { Announcer, UI } from '../../shared/ui';
import { ageText } from './freshness';
import { hours, num, percent } from './format';
import { Feed } from './insights.feed';
import { ExposureResponse, InsightsApi, PerformanceResponse, WorkloadResponse } from './staff.api';
import type { Freshness } from './freshness';
import type { ProblemError } from '../../core/api/problem-error';

/** Clock for stale logic; tests provide a fixed one. */
export const INSIGHTS_NOW = new InjectionToken<() => number>('INSIGHTS_NOW', { providedIn: 'root', factory: () => () => Date.now() });

export const BASIS_TEXT = 'Case reserves. Excludes IBNR; not total liability.';
const DAYS = [7, 30, 90, 180, 365];

@Component({
  selector: 'app-staff-insights',
  imports: [RouterLink, LucideAngularModule, NgTemplateOutlet, UI],
  template: `
    <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl font-bold tracking-tight">Insights</h1>
        <p class="text-sm text-muted-foreground">Team-level figures from the read model. They can lag claim screens by a few seconds.</p>
      </div>
      <div class="flex flex-wrap items-center gap-3">
        <ui-form-field label="Market" class="w-44">
          <select uiSelect data-testid="market-filter" [value]="market()" (change)="setMarket($any($event.target).value)">
            <option value="">All my markets</option>
            @for (m of auth.markets(); track m) { <option [value]="m">{{ m }}</option> }
          </select>
        </ui-form-field>
      </div>
    </div>

    <ui-tabs label="Insights" [tabs]="tabs" [value]="tab()" (valueChange)="setTab($event)" />

    <div class="mt-4 space-y-3" role="tabpanel" tabindex="0" [id]="'panel-' + tab()" [attr.aria-labelledby]="'tab-' + tab()">
      <!-- status strip: as-of + stale + unavailable -->
      <div class="flex flex-wrap items-center gap-2 text-sm" data-testid="asof-strip">
        <span class="inline-flex items-center gap-1 tabular-nums" [class]="fresh().stale ? 'font-medium text-amber-800 dark:text-amber-300' : 'text-muted-foreground'">
          <lucide-icon [img]="icons.Clock" class="h-4 w-4" aria-hidden="true" />
          <span data-testid="asof">As of {{ asOfText() }}</span>
          @if (fresh().stale) { <span uiBadge variant="outline" class="border-amber-500 text-amber-800 dark:text-amber-300" data-testid="stale-badge">{{ staleBadge() }}</span> }
        </span>
        @if (loading()) { <span class="text-xs text-muted-foreground">Refreshing…</span> }
        @if (staleNote(); as note) { <p class="basis-full text-xs text-muted-foreground" data-testid="stale-note">{{ note }}</p> }
      </div>
      @if (unavailable()) {
        <div role="alert" data-testid="unavailable" class="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
          <lucide-icon [img]="icons.CloudOff" class="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
          <div>
            <strong>Dashboards unavailable.</strong> Claim work isn't affected.
            @if (data()) { The figures below are the last we received and are stale. } @else { There is nothing to show yet. }
            Retrying every 15 seconds.
            <button uiButton size="sm" variant="outline" type="button" class="ml-2" (click)="reload()">Retry now</button>
          </div>
        </div>
      }

      @if (error(); as e) {
        <section uiCard><ui-error-state [headingLevel]="2" [title]="e.status === 403 ? 'Not allowed' : 'Could not load insights'" [message]="e.detail ?? e.title" [errorId]="e.errorId" (retry)="reload()" /></section>
      } @else if (!data() && !unavailable()) {
        <section uiCard class="p-6"><ui-skeleton-table [rows]="5" [columns]="6" /></section>
      } @else if (data()) {
        @switch (tab()) {
          @case ('exposure') { <ng-container *ngTemplateOutlet="exposureT" /> }
          @case ('workload') { <ng-container *ngTemplateOutlet="workloadT" /> }
          @case ('performance') { <ng-container *ngTemplateOutlet="performanceT" /> }
        }
      }
    </div>

    <ng-template #exposureT>
      @let x = exposure.data()!;
      <div class="rounded-md border-l-4 border-l-primary bg-primary/5 px-4 py-3" data-testid="basis">
        <p class="font-semibold">{{ basisText }}</p>
        <p class="text-xs text-muted-foreground">Case exposure is the claims team's own reserve estimate on open and closed claims. Each currency is shown in its own units.</p>
      </div>
      <section uiCard>
        <div uiCardHeader><h2 uiCardTitle class="text-lg">Case exposure by market and line</h2></div>
        <div uiCardContent>
          @if (!(x.rows?.length)) {
            <ui-empty-state title="No exposure to show" description="No claims in the selected markets yet." />
          } @else {
            <div uiTableContainer scrollRegion="Case exposure by market and line, scrollable">
              <table uiTable aria-label="Case exposure by market and line" class="[&_th]:h-auto [&_th]:px-2 [&_th]:py-2 [&_th]:align-bottom [&_td]:whitespace-nowrap [&_td]:px-2 [&_td]:py-3">
                <thead uiThead><tr uiTr>
                  <th uiTh scope="col">Market</th><th uiTh scope="col">Line</th>
                  <th uiTh scope="col" class="text-right">Open</th>
                  <th uiTh scope="col" class="text-right">Case<br />outstanding</th><th uiTh scope="col" class="text-right">Provisional</th>
                  <th uiTh scope="col" class="text-right">Assessed</th><th uiTh scope="col" class="text-right">Paid</th><th uiTh scope="col" class="text-right">Incurred</th>
                </tr></thead>
                <tbody uiTbody>
                  @for (r of x.rows; track $index) {
                    <tr uiTr>
                      <td uiTd>{{ r.market }}</td><td uiTd>{{ r.line }}</td>
                      <td uiTd class="text-right tabular-nums">{{ n(r.openClaims) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ r.caseOutstanding | money: r.currency }}</td>
                      <td uiTd class="text-right tabular-nums">{{ r.provisional | money: r.currency }}</td>
                      <td uiTd class="text-right tabular-nums">{{ r.assessed | money: r.currency }}</td>
                      <td uiTd class="text-right tabular-nums">{{ r.paid | money: r.currency }}</td>
                      <td uiTd class="text-right tabular-nums">{{ r.incurred | money: r.currency }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </section>
      <section uiCard>
        <div uiCardHeader><h2 uiCardTitle class="text-lg">Subtotals by currency</h2><p uiCardDescription>Native currency only. Different currencies are never added together here.</p></div>
        <div uiCardContent>
          <div uiTableContainer scrollRegion="Subtotals by currency, scrollable">
            <table uiTable aria-label="Subtotals by currency" data-testid="currency-totals">
              <thead uiThead><tr uiTr>
                <th uiTh scope="col">Currency</th><th uiTh scope="col" class="text-right">Open</th>
                <th uiTh scope="col" class="text-right">Case<br />outstanding</th><th uiTh scope="col" class="text-right">Provisional</th>
                <th uiTh scope="col" class="text-right">Assessed</th><th uiTh scope="col" class="text-right">Paid</th><th uiTh scope="col" class="text-right">Incurred</th>
              </tr></thead>
              <tbody uiTbody>
                @for (t of x.totalsByCurrency ?? []; track t.currency) {
                  <tr uiTr class="font-medium">
                    <td uiTd>{{ t.currency }}</td><td uiTd class="text-right tabular-nums">{{ n(t.openClaims) }}</td>
                    <td uiTd class="text-right tabular-nums">{{ t.caseOutstanding | money: t.currency }}</td>
                    <td uiTd class="text-right tabular-nums">{{ t.provisional | money: t.currency }}</td>
                    <td uiTd class="text-right tabular-nums">{{ t.assessed | money: t.currency }}</td>
                    <td uiTd class="text-right tabular-nums">{{ t.paid | money: t.currency }}</td>
                    <td uiTd class="text-right tabular-nums">{{ t.incurred | money: t.currency }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      </section>
      @if (x.indicativeUsdTotal; as u) {
        <section uiCard data-testid="indicative">
          <div uiCardHeader>
            <h2 uiCardTitle class="text-lg">Indicative {{ u.currency ?? 'USD' }} total <span uiBadge variant="outline" class="ml-2 align-middle">Indicative</span></h2>
            <p uiCardDescription>{{ u.note ?? 'Converted with fixed configured rates, not market rates. Never use it for a decision.' }}</p>
          </div>
          <div uiCardContent class="space-y-3">
            <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Case outstanding</p><p class="font-semibold tabular-nums">{{ u.caseOutstanding | money: u.currency }}</p></div>
              <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Paid</p><p class="font-semibold tabular-nums">{{ u.paid | money: u.currency }}</p></div>
              <div class="rounded-md border p-3"><p class="text-xs text-muted-foreground">Incurred</p><p class="font-semibold tabular-nums">{{ u.incurred | money: u.currency }}</p></div>
            </div>
            <p class="flex flex-wrap gap-x-3 text-sm" data-testid="rates">
              <span>Rates used ({{ u.currency ?? 'USD' }} per 1 unit):</span>
              @for (r of rates(u.usdPerUnitRates); track r[0]) { <span class="whitespace-nowrap tabular-nums">{{ r[0] }} = {{ r[1] }}</span> }
            </p>
            @if (u.excludedCurrencies?.length) {
              <ui-warning-notice>Not included (no rate): {{ u.excludedCurrencies!.join(', ') }}.</ui-warning-notice>
            }
          </div>
        </section>
      }
    </ng-template>

    <ng-template #workloadT>
      @let w = workload.data()!;
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4" data-testid="team-tiles">
        <div uiCard class="p-4"><p class="text-sm text-muted-foreground">Open claims</p><p class="text-2xl font-semibold tabular-nums">{{ n(w.team?.openClaims) }}</p></div>
        <div uiCard class="p-4"><p class="text-sm text-muted-foreground">Overdue</p><p class="text-2xl font-semibold tabular-nums">{{ n(w.team?.overdue) }}</p></div>
        <div uiCard class="p-4"><p class="text-sm text-muted-foreground">Waiting on claimant</p><p class="text-2xl font-semibold tabular-nums">{{ n(w.team?.waitingOnClaimant) }}</p></div>
        <div uiCard class="p-4"><p class="text-sm text-muted-foreground">Unassigned</p><p class="text-2xl font-semibold tabular-nums">{{ n(w.team?.unassigned) }}</p></div>
      </div>
      <section uiCard>
        <div uiCardHeader><h2 uiCardTitle class="text-lg">Workload per officer</h2>
          <p uiCardDescription>To move work between officers, open the claim from <a routerLink="/staff/claims" class="underline">Claims</a> and use Assign (managers).</p></div>
        <div uiCardContent>
          @if (!(w.officers?.length)) {
            <ui-empty-state title="No officers with open claims" />
          } @else {
            <div uiTableContainer scrollRegion="Workload per officer, scrollable">
              <table uiTable aria-label="Workload per officer">
                <thead uiThead><tr uiTr>
                  <th uiTh scope="col">Officer</th><th uiTh scope="col" class="text-right">Open</th><th uiTh scope="col" class="text-right">Overdue</th>
                  <th uiTh scope="col" class="text-right">Waiting on claimant</th><th uiTh scope="col" class="text-right">Oldest (days)</th>
                </tr></thead>
                <tbody uiTbody>
                  @for (o of w.officers; track o.officerId) {
                    <tr uiTr>
                      <td uiTd class="font-medium">{{ o.officerName }}</td>
                      <td uiTd class="text-right tabular-nums">{{ n(o.openClaims) }}</td>
                      <td uiTd class="text-right tabular-nums" [class]="(o.overdue ?? 0) > 0 ? 'font-semibold text-red-700 dark:text-red-400' : ''">{{ n(o.overdue) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ n(o.waitingOnClaimant) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ n(o.oldestAgeDays) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </section>
      <section uiCard>
        <div uiCardHeader><h2 uiCardTitle class="text-lg">Ageing of open claims</h2></div>
        <div uiCardContent>
          <div uiTableContainer scrollRegion="Ageing buckets, scrollable">
            <table uiTable aria-label="Ageing buckets" data-testid="ageing">
              <thead uiThead><tr uiTr>
                <th uiTh scope="col" class="text-right">0–2 days</th><th uiTh scope="col" class="text-right">3–7 days</th>
                <th uiTh scope="col" class="text-right">8–14 days</th><th uiTh scope="col" class="text-right">15+ days</th>
              </tr></thead>
              <tbody uiTbody><tr uiTr>
                <td uiTd class="text-right tabular-nums">{{ n(w.ageing?.days0to2) }}</td><td uiTd class="text-right tabular-nums">{{ n(w.ageing?.days3to7) }}</td>
                <td uiTd class="text-right tabular-nums">{{ n(w.ageing?.days8to14) }}</td><td uiTd class="text-right tabular-nums">{{ n(w.ageing?.days15plus) }}</td>
              </tr></tbody>
            </table>
          </div>
        </div>
      </section>
    </ng-template>

    <ng-template #performanceT>
      @let p = performance.data()!;
      <div class="flex flex-wrap items-end gap-3">
        <ui-form-field label="Window" class="w-44">
          <select uiSelect [value]="days()" (change)="setDays($any($event.target).value)">
            @for (d of dayOptions; track d) { <option [value]="d">Last {{ d }} days</option> }
          </select>
        </ui-form-field>
        <span class="pb-2 text-xs text-muted-foreground">A dash means no claim qualified; it is not zero.</span>
      </div>
      <ng-container *ngTemplateOutlet="perfTable; context: { $implicit: overall(p), title: 'Overall', first: 'Scope' }" />
      <ng-container *ngTemplateOutlet="perfTable; context: { $implicit: p.byMarket ?? [], title: 'By market', first: 'Market' }" />
      <ng-container *ngTemplateOutlet="perfTable; context: { $implicit: p.byOfficer ?? [], title: 'By officer', first: 'Officer' }" />
    </ng-template>

    <ng-template #perfTable let-rows let-title="title" let-first="first">
      <section uiCard>
        <div uiCardHeader><h2 uiCardTitle class="text-lg">{{ title }}</h2></div>
        <div uiCardContent>
          @if (!rows.length) {
            <ui-empty-state title="Nothing decided in this window" />
          } @else {
            <div uiTableContainer [scrollRegion]="title + ', scrollable'">
              <table uiTable [attr.aria-label]="title">
                <thead uiThead><tr uiTr>
                  <th uiTh scope="col">{{ first }}</th><th uiTh scope="col" class="text-right">Decided</th><th uiTh scope="col" class="text-right">Approval rate</th>
                  <th uiTh scope="col" class="text-right">Pick-up time (avg)</th><th uiTh scope="col" class="text-right">Pick-up time (median)</th>
                  <th uiTh scope="col" class="text-right">Cycle: to decision</th><th uiTh scope="col" class="text-right">Cycle: to settlement</th>
                </tr></thead>
                <tbody uiTbody>
                  @for (r of rows; track $index) {
                    <tr uiTr>
                      <td uiTd class="font-medium">{{ r.name ?? r.key ?? '–' }}</td>
                      <td uiTd class="text-right tabular-nums">{{ n(r.decided) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ pct(r.approvalRate) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ hrs(r.avgHoursToFirstPickup) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ hrs(r.medianHoursToFirstPickup) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ hrs(r.avgHoursToDecision) }}</td>
                      <td uiTd class="text-right tabular-nums">{{ hrs(r.avgHoursToSettlement) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </section>
    </ng-template>
  `,
})
export class InsightsPage {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(InsightsApi);
  private readonly now = inject(INSIGHTS_NOW);

  protected readonly icons = { Clock, CloudOff };
  protected readonly basisText = BASIS_TEXT;
  protected readonly dayOptions = DAYS;
  protected readonly n = num;
  protected readonly pct = percent;
  protected readonly hrs = hours;
  protected readonly tabs = [
    { id: 'exposure', label: 'Case exposure' },
    { id: 'workload', label: 'Workload' },
    { id: 'performance', label: 'Performance' },
  ];
  protected readonly tab = signal<'exposure' | 'workload' | 'performance'>('exposure');
  protected readonly market = signal('');
  protected readonly days = signal(30);

  private markets = () => (this.market() ? [this.market()] : []);
  readonly exposure = new Feed<ExposureResponse>(() => this.api.exposure(this.markets()), (d) => d.asOf, this.now);
  readonly workload = new Feed<WorkloadResponse>(() => this.api.workload(this.markets()), (d) => d.asOf, this.now);
  readonly performance = new Feed<PerformanceResponse>(() => this.api.performance(this.markets(), this.days()), (d) => d.asOf, this.now);

  private readonly feed = computed(() => ({ exposure: this.exposure, workload: this.workload, performance: this.performance })[this.tab()] as Feed<unknown>);
  protected readonly data = computed(() => this.feed().data());
  protected readonly loading = computed(() => this.feed().loading());
  protected readonly unavailable = computed(() => this.feed().unavailable());
  protected readonly error = computed<ProblemError | null>(() => this.feed().error());
  protected readonly fresh = computed<Freshness>(() => this.feed().fresh());
  protected readonly asOfText = computed(() => {
    const d = this.data() as { asOf?: string | null } | null;
    const a = d?.asOf;
    const f = this.fresh();
    if (!a || Number.isNaN(Date.parse(a))) return 'unknown';
    return `${formatInstant(a)} (${ageText(f.ageSeconds)})`;
  });

  /** Why the figures are flagged. An idle system is not a broken one: only unavailable means broken. */
  protected readonly staleKind = computed<'none' | 'unavailable' | 'unknown' | 'idle'>(() => {
    if (!this.fresh().stale) return 'none';
    if (this.unavailable()) return 'unavailable';
    return this.fresh().ageSeconds == null ? 'unknown' : 'idle';
  });
  protected readonly staleBadge = computed(() => {
    const k = this.staleKind();
    return k === 'unavailable' ? 'Stale' : k === 'unknown' ? 'Event time unknown' : 'No recent events';
  });
  protected readonly staleNote = computed(() => {
    const k = this.staleKind();
    if (k === 'idle') return `The last event was ${ageText(this.fresh().ageSeconds)}. Figures may be behind if claims are being worked.`;
    if (k === 'unknown') return 'The service gave no event time, so how current these figures are cannot be told.';
    return null;
  });

  private readonly announcer = inject(Announcer);

  constructor() {
    // Announce the moment the figures BECOME stale (fresh to stale on the same tab, with data on screen). Not on every tick of "as of".
    let wasStale: boolean | null = null;
    let wasTab = '';
    effect(() => {
      const stale = this.fresh().stale;
      const tab = this.tab();
      const hasData = this.data() != null;
      untracked(() => {
        if (hasData && stale && wasStale === false && wasTab === tab) {
          this.announcer.polite(this.unavailable()
            ? `The ${tab} figures are now stale. They were last updated ${this.asOfText()}.`
            : `No recent events. The ${tab} figures may be behind if claims are being worked. The last event was ${this.asOfText()}.`);
        }
        wasStale = hasData ? stale : null;
        wasTab = tab;
      });
    });
    const t = inject(ActivatedRoute).snapshot.queryParamMap.get('tab');
    if (t === 'workload' || t === 'performance' || t === 'exposure') this.tab.set(t);
    void this.reload();
    poll(() => this.reload(), 15_000);
    poll(() => {
      this.exposure.touch();
      this.workload.touch();
      this.performance.touch();
    }, 5_000);
  }

  protected reload(): Promise<void> {
    return this.feed().load();
  }
  protected setTab(id: string): void {
    this.tab.set(id as 'exposure');
    void this.reload();
  }
  protected setMarket(v: string): void {
    this.market.set(v);
    this.clearData();
    void this.reload();
  }
  protected setDays(v: string): void {
    this.days.set(Number(v));
    this.performance.reset();
    void this.reload();
  }
  /** A different market is a different question: drop every snapshot so none is shown for the wrong scope. */
  private clearData(): void {
    this.exposure.reset();
    this.workload.reset();
    this.performance.reset();
  }
  protected overall(p: PerformanceResponse) {
    return p.overall ? [{ ...p.overall, name: p.overall.name ?? 'All claims' }] : [];
  }
  protected rates(r: Record<string, string> | undefined): [string, string][] {
    return Object.entries(r ?? {});
  }
}

