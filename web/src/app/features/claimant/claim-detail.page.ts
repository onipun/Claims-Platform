import { Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAngularModule, ArrowLeft, Loader2, MessageCircleQuestion } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { requestGuard } from '../../core/util/latest';
import { poll } from '../../core/util/poll';
import { Announcer, UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { formatWhen } from './claimant.format';
import { REASON_LABEL, STATUS_EXPLANATION, claimTypeLabel, humanise, lineLabel } from './claimant.labels';
import type { Checklist, ClaimDetail, Tagged } from './claimant.types';
import { DocumentsPanel } from './documents-panel';

export const POLL_MS = 30_000;
export const MAX_ANSWER = 4000;

/** How a reload ended. Only 'failed' is the user's problem: a superseded one was overtaken by newer data. */
export type ReloadOutcome = 'ok' | 'failed' | 'superseded' | 'busy';

/** Why a read failed, in words that do not pass a 403 or a 404 off as a network blip. */
export function problemText(p: ProblemError): string {
  if (p.status === 404) return 'This claim could not be found. It may have been removed or no longer be on your account.';
  if (p.status === 403) return 'You no longer have access to this claim.';
  return p.detail ?? p.title;
}

/** Wall-clock HH:MM for "out of date since". */
const clock = (): string => new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });

type Phase = 'loading' | 'error' | 'ready';

@Component({
  selector: 'app-claim-detail-page',
  imports: [FormsModule, RouterLink, LucideAngularModule, UI, DocumentsPanel],
  template: `
    <div class="mx-auto max-w-4xl space-y-6">
      <a routerLink="/claimant/claims" class="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <lucide-icon [img]="icons.ArrowLeft" class="h-4 w-4" aria-hidden="true" />All claims
      </a>

      @if (outOfDateSince(); as since) {
        <div role="status" class="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100" data-testid="out-of-date">
          Out of date since {{ since }}, retrying.@if (outOfDateReason(); as why) { {{ why }} }
        </div>
      }
      @if (reloadFailure(); as f) {
        @if (f.where === 'claim') {
          <div role="alert" class="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" data-testid="reload-error">Could not reload the claim. {{ f.text }} What you typed is kept.</div>
        }
      }

      @switch (phase()) {
        @case ('loading') {
          <div class="space-y-4" aria-busy="true"><ui-skeleton-card /><ui-skeleton-card /><ui-skeleton-card /></div>
        }
        @case ('error') {
          <section uiCard>
            <ui-error-state
              [title]="error()?.status === 404 ? 'Claim not found' : 'Something went wrong'"
              [message]="error()?.status === 404 ? 'We could not find this claim. It may not belong to your account.' : (error()?.detail ?? error()?.title ?? '')"
              [errorId]="error()?.errorId"
              [retryable]="error()?.status !== 404"
              (retry)="load()" />
          </section>
        }
        @case ('ready') {
          @if (claim(); as c) {
            <header class="space-y-2">
              <div class="flex flex-wrap items-center gap-3">
                <h1 class="break-all text-2xl font-bold tracking-tight" data-testid="claim-number">{{ c.claimNumber }}</h1>
                <claim-status-badge [status]="c.status" />
              </div>
              <p class="text-sm text-muted-foreground">{{ type(c.claimType) }} &middot; {{ line(c.line) }} &middot; policy {{ c.policyNumber }}</p>
            </header>

            <section uiCard class="p-5" aria-labelledby="status-title" data-testid="status-panel">
              <h2 id="status-title" class="mb-1 font-semibold">Where your claim stands</h2>
              <p class="text-sm" data-testid="status-explanation">{{ explanation() }}</p>
            </section>

            @if (c.late) {
              <ui-warning-notice>This claim was reported after the usual deadline. It has not been rejected for that.@if (c.lateReason) { Your reason: {{ c.lateReason }} }</ui-warning-notice>
            }

            @if (c.openQuestion; as q) {
              <section uiCard class="border-orange-300 dark:border-orange-800" aria-labelledby="q-title" data-testid="question-panel">
                <div uiCardHeader>
                  <h2 id="q-title" uiCardTitle class="flex items-center gap-2 text-lg focus:outline-none" tabindex="-1"><lucide-icon [img]="icons.MessageCircleQuestion" class="h-5 w-5 text-orange-600" aria-hidden="true" />We need your answer</h2>
                  <p uiCardDescription>Asked {{ when(q.askedAt) }}</p>
                </div>
                <form uiCardContent class="space-y-4" (ngSubmit)="sendAnswer()" novalidate>
                  <blockquote class="whitespace-pre-line break-words rounded-md bg-muted px-4 py-3 text-sm" data-testid="question-text">{{ q.question }}</blockquote>

                  @if (stale()) {
                    <div class="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100" data-testid="stale-notice">
                      This claim changed. Reload to see it. Your answer is kept below and hasn't been sent.
                      <div class="mt-2"><button uiButton variant="outline" size="sm" type="button" [attr.aria-disabled]="reloading() ? 'true' : null" (click)="reloadClaim()">Reload claim</button></div>
                    </div>
                  }
                  @if (answerProblem(); as p) {
                    <div role="alert" class="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200" data-testid="answer-error">{{ answerErrorText(p) }}
                      @if (outcomeUnknown(p)) { <div class="mt-2"><button uiButton variant="outline" size="sm" type="button" [attr.aria-disabled]="reloading() ? 'true' : null" (click)="reloadClaim()">Reload claim</button></div> }
                    </div>
                  }

                  <ui-required-legend />
                  <ui-form-field label="Your answer" [required]="true" [error]="answerFieldError()">
                    <textarea uiTextarea rows="5" name="answer" [ngModel]="answer()" (ngModelChange)="answer.set($event)" [readOnly]="sending()"></textarea>
                  </ui-form-field>
                  <button uiButton type="submit" [attr.aria-disabled]="sending() ? 'true' : null" [attr.aria-busy]="sending()">
                    @if (sending()) { <lucide-icon [img]="icons.Loader2" class="mr-2 h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />Sending... } @else { Send answer }
                  </button>
                </form>
              </section>
            } @else if (answeredNotice()) {
              <div id="answered-notice" tabindex="-1" role="group" aria-labelledby="answered-text" class="focus:outline-none"><ui-warning-notice><span id="answered-text">Thank you. Your answer was sent and the claim is back with your claims officer.</span></ui-warning-notice></div>
            }

            @if (c.decision; as d) {
              <section uiCard class="p-5" aria-labelledby="decision-title" data-testid="decision-panel"
                [class.border-green-300]="!!d.amount" [class.border-red-300]="!d.amount">
                <h2 id="decision-title" class="mb-2 font-semibold">{{ d.amount ? 'Decision: approved' : 'Decision: not accepted' }}</h2>
                @if (d.amount) {
                  <p class="text-2xl font-bold" data-testid="decision-amount">{{ d.amount | money: d.currency || c.currency }}</p>
                  <p class="text-xs text-muted-foreground">Approved amount</p>
                } @else if (d.reasonCode) {
                  <p class="font-medium" data-testid="decision-reason">{{ reason(d.reasonCode) }}</p>
                }
                @if (d.explanation) { <p class="mt-3 whitespace-pre-line break-words text-sm">{{ d.explanation }}</p> }
                @if (d.decidedAt) { <p class="mt-3 text-xs text-muted-foreground">Decided {{ when(d.decidedAt) }}</p> }
              </section>
            }

            <section uiCard aria-labelledby="details-title">
              <div uiCardHeader><h2 id="details-title" uiCardTitle class="text-lg">Claim details</h2></div>
              <dl uiCardContent class="grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
                <div><dt class="text-muted-foreground">Incident</dt><dd class="font-medium">{{ when(c.incidentAtLocal, false) }}@if (c.marketZone) { <span class="font-normal text-muted-foreground"> ({{ c.marketZone }})</span> }</dd></div>
                <div><dt class="text-muted-foreground">Reported</dt><dd class="font-medium">{{ when(c.reportedAt) }}</dd></div>
                <div><dt class="text-muted-foreground">Your estimate</dt><dd class="font-medium">{{ c.estimate | money: c.currency }}</dd></div>
                <div><dt class="text-muted-foreground">Contact phone</dt><dd class="font-medium">{{ c.contactPhone || 'Not given' }}</dd></div>
                @if (c.description) { <div class="sm:col-span-2"><dt class="text-muted-foreground">Description</dt><dd class="whitespace-pre-line break-words">{{ c.description }}</dd></div> }
                @if (c.motor; as m) {
                  <div><dt class="text-muted-foreground">Vehicle</dt><dd class="font-medium">{{ m.vehicleRegistration }}</dd></div>
                  <div><dt class="text-muted-foreground">Driver</dt><dd class="font-medium">{{ m.driverName }}</dd></div>
                  @if (m.location) { <div class="sm:col-span-2"><dt class="text-muted-foreground">Accident location</dt><dd class="font-medium break-words" data-testid="accident-location">{{ m.location }}</dd></div> }
                  @if (m.policeReport) { <div><dt class="text-muted-foreground">Police report</dt><dd class="font-medium">{{ m.policeReport }}</dd></div> }
                  @if (m.injury) { <div class="sm:col-span-2"><dt class="text-muted-foreground">Injuries</dt><dd class="break-words">{{ m.injuryDescription || 'Yes' }}</dd></div> }
                  @if (m.thirdParty; as t) {
                    <div class="sm:col-span-2"><dt class="text-muted-foreground">Other party</dt><dd>{{ [t.name, t.phone, t.insurer, t.vehicleNumber].filter(x => !!x).join(' · ') }}</dd></div>
                  }
                }
                @if (c.property; as p) {
                  <div class="sm:col-span-2"><dt class="text-muted-foreground">Address</dt><dd class="font-medium break-words">{{ p.address }}</dd></div>
                  <div><dt class="text-muted-foreground">Cause</dt><dd class="font-medium break-words">{{ p.cause }}</dd></div>
                  @if (p.occupancy) { <div><dt class="text-muted-foreground">Occupancy</dt><dd class="font-medium">{{ p.occupancy }}</dd></div> }
                }
              </dl>
            </section>

            @if (checklist(); as cl) {
              @if (uploadNotice() === 'ok') {
                <p role="status" class="text-sm text-green-700 dark:text-green-300" data-testid="upload-notice">Uploaded</p>
              } @else if (uploadNotice() === 'refresh-failed') {
                <div role="alert" class="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100" data-testid="upload-notice">
                  Uploaded, but we couldn't refresh the list.@if (reloadFailure(); as f) { {{ f.text }} }
                  <button uiButton variant="outline" size="sm" type="button" class="ml-2" [attr.aria-disabled]="reloading() ? 'true' : null" (click)="retryUploadRefresh()">Reload</button>
                </div>
              }
              <claimant-documents-panel [claimId]="c.id ?? ''" [checklist]="cl" [zone]="c.marketZone" (uploaded)="onUploaded()" />
            } @else if (checklistFailed()) {
              <section uiCard><ui-error-state title="Documents could not be loaded" message="Try again in a moment." (retry)="reloadClaim()" /></section>
            }

            <section uiCard aria-labelledby="timeline-title">
              <div uiCardHeader><h2 id="timeline-title" uiCardTitle class="text-lg">Timeline</h2></div>
              <div uiCardContent>
                @if (timeline().length) {
                  <ol class="relative space-y-5 border-l pl-6" data-testid="timeline">
                    @for (t of timeline(); track $index) {
                      <li class="relative">
                        <span class="absolute -left-[1.85rem] top-1.5 h-2.5 w-2.5 rounded-full bg-primary" aria-hidden="true"></span>
                        <p class="break-words text-sm font-medium">{{ t.message || status(t.toStatus) || human(t.type) }}</p>
                        <p class="text-xs text-muted-foreground">{{ when(t.at) }}@if (t.actorName) { &middot; {{ t.actorName }} }</p>
                      </li>
                    }
                  </ol>
                } @else {
                  <p class="text-sm text-muted-foreground">Nothing has happened on this claim yet.</p>
                }
              </div>
            </section>
          }
        }
      }
    </div>
  `,
})
export class ClaimDetailPage {
  private readonly api = inject(ClaimantApi);
  /** Route param `:id`. */
  readonly id = input.required<string>();

  protected readonly icons = { ArrowLeft, Loader2, MessageCircleQuestion };
  protected readonly phase = signal<Phase>('loading');
  protected readonly error = signal<ProblemError | null>(null);
  protected readonly claim = signal<ClaimDetail | null>(null);
  protected readonly checklist = signal<Checklist | null>(null);
  protected readonly checklistFailed = signal(false);
  private readonly etag = signal<string | null>(null);
  /** Only the latest read may write state; an id change or a successful command makes earlier reads stale. */
  private readonly guard = requestGuard();
  protected readonly outOfDateSince = signal<string | null>(null);
  protected readonly outOfDateReason = signal<string | null>(null);
  /** The last reload that failed, and which control started it (so the message appears next to it). */
  protected readonly reloadFailure = signal<{ text: string; where: 'claim' | 'upload' } | null>(null);
  /** Bumped by a new claim: a reload from the previous claim must not clear the flag of the new one. */
  private reloadRun = 0;
  protected readonly uploadNotice = signal<'ok' | 'refresh-failed' | null>(null);

  // Answer form. The text lives here, not in the claim, so a reload or a poll never clears it.
  readonly answer = signal('');
  protected readonly sending = signal(false);
  private readonly announcer = inject(Announcer);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  protected readonly stale = signal(false);
  protected readonly reloading = signal(false);
  protected readonly answered = signal(false);
  protected readonly answerProblem = signal<ProblemError | null>(null);
  protected readonly answerTouched = signal(false);

  protected readonly type = claimTypeLabel;
  protected readonly line = lineLabel;
  protected readonly human = humanise;
  protected readonly status = (s: string | null | undefined) => (s ? humanise(s) : '');
  protected readonly reason = (r: string) => REASON_LABEL[r as keyof typeof REASON_LABEL] ?? humanise(r);
  protected readonly explanation = computed(() => STATUS_EXPLANATION[this.claim()?.status ?? 'SUBMITTED']);
  protected readonly timeline = computed(() => [...(this.claim()?.timeline ?? [])].reverse());
  protected readonly answeredNotice = computed(() => this.answered() && !this.claim()?.openQuestion);
  protected readonly answerFieldError = computed(() => {
    const p = this.answerProblem();
    if (p?.fieldMessage('answer')) return p.fieldMessage('answer');
    if (!this.answerTouched()) return null;
    const t = this.answer().trim();
    if (!t) return 'Enter your answer.';
    if (t.length > MAX_ANSWER) return `Your answer must be at most ${MAX_ANSWER} characters.`;
    return null;
  });

  constructor() {
    // Polls while the tab is visible (poll() pauses when hidden and refreshes on return) and stops on destroy.
    poll(() => this.refresh(), POLL_MS);
    // (Re)load whenever the route's claim id is set or changes.
    effect(() => {
      this.id();
      untracked(() => {
        this.resetForNewClaim();
        void this.load();
      });
    });
  }

  protected when(v: string | undefined, time = true): string {
    return formatWhen(v, { zone: this.claim()?.marketZone, time });
  }

  /** A different claim: nothing of the previous one (or its draft) may survive, and its reads must not land. */
  private resetForNewClaim(): void {
    this.guard.invalidate();
    this.claim.set(null);
    this.etag.set(null);
    this.checklist.set(null);
    this.checklistFailed.set(false);
    this.answer.set('');
    this.answerTouched.set(false);
    this.answerProblem.set(null);
    this.stale.set(false);
    this.answered.set(false);
    this.sending.set(false);
    this.reloadRun++;
    this.reloading.set(false);
    this.reloadFailure.set(null);
    this.outOfDateSince.set(null);
    this.outOfDateReason.set(null);
    this.uploadNotice.set(null);
  }

  /** First load, with skeletons. */
  protected async load(): Promise<void> {
    this.phase.set('loading');
    this.error.set(null);
    const current = this.guard.begin();
    try {
      const r = await this.read();
      if (!current()) return;
      this.apply(r);
      this.phase.set('ready');
      if (typeof location !== 'undefined' && location.hash === '#documents') {
        setTimeout(() => document.getElementById('documents')?.scrollIntoView({ block: 'start' }));
      }
    } catch (e) {
      if (!current()) return;
      this.error.set(ProblemError.from(e));
      this.phase.set('error');
    }
  }

  /**
   * Silent refresh (poll): keeps what is on screen if it fails, and says so. A refresh that would change the
   * version or the open question under a half-written answer is NOT applied: the user is told instead.
   */
  protected async refresh(force = false): Promise<'ok' | 'failed' | 'superseded' | 'skipped'> {
    // A reload is already reading; a poll on top of it would only supersede it. (After an answer, `force` does supersede it.)
    if (this.phase() !== 'ready' || this.sending() || (this.reloading() && !force)) return 'skipped';
    const current = this.guard.begin();
    try {
      const r = await this.read();
      if (!current()) return 'superseded';
      this.outOfDateSince.set(null);
      this.outOfDateReason.set(null);
      if (this.answer().trim() !== '' && this.changesWhatIsOnScreen(r.detail)) {
        if (!this.stale()) this.announcer.assertive('This claim changed. Reload to see it. Your answer is kept and hasn\'t been sent.');
        this.stale.set(true);
        this.applyChecklist(r);
        return 'ok';
      }
      const before = this.etag();
      const status = this.claim()?.status;
      this.apply(r);
      // A change made elsewhere (an officer acted): say so once. An unchanged poll says nothing.
      if (before !== null && r.detail.etag !== before) {
        this.announcer.polite(`This claim was updated${r.detail.body.status !== status ? `. Status is now ${humanise(r.detail.body.status)}` : ''}.`);
      }
    } catch (e) {
      // The last good data stays; say that it is no longer current, and why.
      if (!current()) return 'superseded';
      if (this.outOfDateSince() === null) this.outOfDateSince.set(clock());
      this.outOfDateReason.set(problemText(ProblemError.from(e)));
      return 'failed';
    }
    return 'ok';
  }

  private changesWhatIsOnScreen(next: Tagged<ClaimDetail>): boolean {
    const have = this.claim()?.openQuestion;
    const want = next.body.openQuestion;
    return next.etag !== this.etag() || have?.question !== want?.question || have?.askedAt !== want?.askedAt;
  }

  private async read(): Promise<{ detail: Tagged<ClaimDetail>; checklist: Checklist | null; failed: boolean }> {
    const id = this.id();
    const [detail, checklist] = await Promise.all([
      this.api.getClaim(id),
      this.api.checklist(id).then((c) => ({ c, failed: false }), () => ({ c: null, failed: true })),
    ]);
    return { detail, checklist: checklist.c, failed: checklist.failed };
  }

  private apply(r: { detail: Tagged<ClaimDetail>; checklist: Checklist | null; failed: boolean }): void {
    this.claim.set(r.detail.body);
    this.etag.set(r.detail.etag);
    this.outOfDateSince.set(null);
    this.outOfDateReason.set(null);
    // Fresh data is on screen: an earlier failed reload, and the upload's "couldn't refresh", are no longer true.
    this.reloadFailure.set(null);
    if (this.uploadNotice() === 'refresh-failed') this.uploadNotice.set('ok');
    this.applyChecklist(r);
  }

  private applyChecklist(r: { checklist: Checklist | null; failed: boolean }): void {
    if (r.checklist) this.checklist.set(r.checklist);
    this.checklistFailed.set(r.failed && this.checklist() === null);
  }

  /** Re-read the claim and keep whatever the claimant typed. */
  protected async reload(where: 'claim' | 'upload' = 'claim'): Promise<ReloadOutcome> {
    // The buttons use aria-disabled so focus stays put; this refuses the second click.
    if (this.reloading()) return 'busy';
    const run = this.reloadRun;
    this.reloading.set(true);
    this.reloadFailure.set(null);
    const current = this.guard.begin();
    try {
      const r = await this.read();
      if (!current()) return 'superseded';
      this.apply(r);
      this.stale.set(false);
      this.answerProblem.set(null);
      return 'ok';
    } catch (e) {
      if (!current()) return 'superseded';
      // Stay on the notice; the user can try again.
      this.reloadFailure.set({ text: problemText(ProblemError.from(e)), where });
      return 'failed';
    } finally {
      // Always, or a read that was overtaken would leave every later Reload refused. Only a new claim may veto it.
      if (run === this.reloadRun) this.reloading.set(false);
    }
  }

  /** A Reload the claimant pressed: say it worked and put focus where they carry on, or say it did not. */
  protected async reloadClaim(where: 'claim' | 'upload' = 'claim'): Promise<ReloadOutcome> {
    const outcome = await this.reload(where);
    if (outcome === 'ok') {
      this.announcer.polite('Claim reloaded');
      // The button that had focus may be gone with the notice it was in.
      afterNextRender(() => {
        const host = this.host;
        (host.querySelector<HTMLElement>('textarea[name="answer"]') ?? host.querySelector<HTMLElement>('#q-title') ?? host.querySelector<HTMLElement>('h1'))?.focus();
      }, { injector: this.injector });
    }
    return outcome;
  }

  protected async onUploaded(): Promise<void> {
    this.uploadNotice.set('ok');
    const outcome = await this.reload('upload');
    // Only a failed read is a failed refresh. A superseded one was overtaken by newer data.
    // A busy one means the running read may predate the upload: force a new read, which supersedes it.
    let failed = outcome === 'failed';
    if (outcome === 'busy') failed = (await this.refresh(true)) === 'failed';
    if (failed && this.uploadNotice() !== null) this.uploadNotice.set('refresh-failed');
  }

  protected async retryUploadRefresh(): Promise<void> {
    if ((await this.reloadClaim('upload')) === 'ok') this.uploadNotice.set('ok');
  }

  protected async sendAnswer(): Promise<void> {
    if (this.sending()) return;
    this.answerTouched.set(true);
    this.answerProblem.set(null);
    const text = this.answer().trim();
    if (!text || text.length > MAX_ANSWER) return;
    const id = this.id();
    this.sending.set(true);
    let refreshAfter = false;
    try {
      const res = await this.api.answer(id, text, this.etag());
      if (id !== this.id()) return;
      // The command succeeded: any read that started before it is now older than this response.
      this.guard.invalidate();
      this.claim.set(res.body);
      this.etag.set(res.etag);
      this.answer.set('');
      this.answerTouched.set(false);
      this.stale.set(false);
      this.answered.set(true);
      // The question form, and the button that had focus, are replaced by the thank-you notice. Focus on it is the one announcement.
      afterNextRender(() => this.host.querySelector<HTMLElement>('#answered-notice')?.focus(), { injector: this.injector });
      refreshAfter = true;
    } catch (e) {
      if (id !== this.id()) return;
      const p = ProblemError.from(e);
      if (p.code === 'STALE_VERSION') {
        if (!this.stale()) this.announcer.assertive('This claim changed. Reload to see it. Your answer is kept and hasn\'t been sent.');
        this.stale.set(true);
      }
      else this.answerProblem.set(p);
      // The typed answer is untouched in both cases.
    } finally {
      if (id === this.id()) this.sending.set(false);
    }
    // After `sending` is false, or refresh() would return at once. It supersedes any reload still reading.
    if (refreshAfter) void this.refresh(true);
  }

  /** The request may have been processed: the network dropped, or the server failed after it started. */
  protected outcomeUnknown(p: ProblemError): boolean {
    return p.status === 0 || p.status >= 500 || p.code === 'EMPTY_RESPONSE';
  }

  protected answerErrorText(p: ProblemError): string {
    if (p.code === 'ILLEGAL_TRANSITION') return 'This question is no longer waiting for an answer. Reload the claim to see where it stands. What you typed is kept.';
    if (p.code === 'PRECONDITION_REQUIRED' || p.status === 428) return 'Reload the claim and send your answer again. What you typed is kept.';
    if (this.outcomeUnknown(p)) return 'Unknown whether this was saved. Reload to check. What you typed is kept.';
    return p.detail ?? p.title;
  }
}
