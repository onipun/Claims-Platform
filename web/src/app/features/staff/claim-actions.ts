import { Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule, AlertTriangle, Loader2, Lock } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { Announcer, ConfirmService, ToastService, UI, formatMoney } from '../../shared/ui';
import { Command, REJECT_REASONS, StaffApi, StaffClaimDetail, StaffOfficer, Versioned } from './staff.api';

/** Action id (from the API's allowedActions) to button text. An id that is not listed here gets no button. */
export const ACTION_LABELS: Readonly<Record<string, string>> = {
  PICK_UP: 'Pick up',
  ASSIGN: 'Assign',
  ADD_NOTE: 'Add note',
  REQUEST_INFO: 'Request info',
  ASSESS: 'Assess',
  APPROVE: 'Approve',
  REJECT: 'Reject',
  SETTLE: 'Settle',
};
const AMOUNT = /^\d+(\.\d+)?$/;

type Notice = { kind: 'stale' | 'authority' | 'conflict' | 'error' | 'unknown'; text: string };

/**
 * Command panel. Buttons are rendered from `claim.allowedActions` only; the screen never decides
 * what is legal. Every command goes out with the ETag of the last read. Drafts live in forms that
 * are reset only after a success, so a 409 never loses typed input.
 */
@Component({
  selector: 'app-claim-actions',
  imports: [ReactiveFormsModule, LucideAngularModule, UI],
  template: `
    <section uiCard aria-labelledby="actions-h">
      <div uiCardHeader>
        <h2 id="actions-h" uiCardTitle class="text-lg focus:outline-none" tabindex="-1">Actions</h2>
        <p uiCardDescription>Only what you may do right now on this claim.</p>
      </div>
      <div uiCardContent class="space-y-4">
        @if (notice(); as n) {
          <div role="alert" [attr.data-testid]="'notice-' + n.kind" class="flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
            [class]="n.kind === 'conflict' ? 'border-red-300 bg-red-50 text-red-900 dark:bg-red-950/40 dark:text-red-100' : n.kind === 'stale' ? 'border-amber-300 bg-amber-50 text-amber-900 dark:bg-amber-950/40 dark:text-amber-100' : 'border-red-200 bg-red-50 text-red-800 dark:bg-red-950/40 dark:text-red-200'">
            <lucide-icon [img]="n.kind === 'conflict' ? icons.Lock : icons.AlertTriangle" class="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
            <div class="flex-1">
              {{ n.text }}
              @if (n.kind === 'stale' || n.kind === 'unknown') {
                <button uiButton size="sm" variant="outline" type="button" class="ml-2 mt-1" (click)="reloadClaim.emit()">Reload claim</button>
              }
            </div>
          </div>
        }

        @if (actions().length === 0) {
          <p class="text-sm text-muted-foreground" data-testid="no-actions">No actions are available on this claim for you.</p>
        } @else {
          <div class="flex flex-wrap gap-2" role="group" aria-label="Available actions">
            @for (a of actions(); track a) {
              <button uiButton type="button" [variant]="active() === a ? 'default' : 'outline'" size="sm" [attr.data-action]="a"
                [attr.aria-pressed]="a === 'PICK_UP' ? null : active() === a" [attr.aria-disabled]="busy() ? 'true' : null" (click)="choose(a)">
                {{ labels[a] }}
              </button>
            }
          </div>
        }

        @if (active()) { <ui-required-legend /> }
        @switch (active()) {
          @case ('ADD_NOTE') {
            <form [formGroup]="note" (ngSubmit)="submitNote()" class="space-y-3" novalidate>
              <ui-form-field label="Note" [required]="true" [error]="err('note', 'text')" hint="Notes are internal. The claimant never sees them.">
                <textarea uiTextarea formControlName="text" rows="3"></textarea>
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Save internal note</button>
            </form>
          }
          @case ('REQUEST_INFO') {
            <form [formGroup]="info" (ngSubmit)="submitInfo()" class="space-y-3" novalidate>
              <ui-form-field label="Question for the claimant" [required]="true" [error]="err('info', 'question')" hint="The claimant will see this text. The claim moves to Awaiting info.">
                <textarea uiTextarea formControlName="question" rows="3"></textarea>
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Send request to claimant</button>
            </form>
          }
          @case ('ASSESS') {
            <form [formGroup]="assess" (ngSubmit)="submitAssess()" class="space-y-3" novalidate>
              <ui-form-field [label]="'Case reserve (' + claim().currency + ')'" [required]="true" [error]="err('assess', 'reserve')"
                [hint]="'Your estimate of what this claim will cost. Between 0 and the coverage limit' + (limitText() ? ' (' + limitText() + ')' : '') + '.'">
                <input uiInput formControlName="reserve" inputmode="decimal" autocomplete="off" />
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Record assessment</button>
            </form>
          }
          @case ('APPROVE') {
            <form [formGroup]="approve" (ngSubmit)="submitApprove()" class="space-y-3" novalidate>
              <ui-form-field [label]="'Approved amount (' + claim().currency + ')'" [required]="true" [error]="err('approve', 'amount')"
                [hint]="authorityHint()">
                <input uiInput formControlName="amount" inputmode="decimal" autocomplete="off" />
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Approve</button>
            </form>
          }
          @case ('REJECT') {
            <form [formGroup]="reject" (ngSubmit)="submitReject()" class="space-y-3" novalidate>
              <ui-form-field label="Reason" [required]="true" [error]="err('reject', 'reason')">
                <select uiSelect formControlName="reason">
                  <option value="">Choose a reason</option>
                  @for (r of reasons; track r.value) { <option [value]="r.value">{{ r.label }}</option> }
                </select>
              </ui-form-field>
              <ui-form-field label="Explanation" [required]="true" [error]="err('reject', 'explanation')" hint="The claimant will see this explanation and the reason. This decision is final.">
                <textarea uiTextarea formControlName="explanation" rows="3"></textarea>
              </ui-form-field>
              <button uiButton size="sm" variant="destructive" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Reject claim…</button>
            </form>
          }
          @case ('SETTLE') {
            <form [formGroup]="settle" (ngSubmit)="submitSettle()" class="space-y-3" novalidate>
              <ui-form-field [label]="'Payment amount (' + claim().currency + ')'" [required]="true" [error]="err('settle', 'amount')"
                [hint]="'Must equal the approved amount' + (approvedText() ? ' (' + approvedText() + ')' : '') + '.'">
                <input uiInput formControlName="amount" inputmode="decimal" autocomplete="off" />
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Settle…</button>
            </form>
          }
          @case ('ASSIGN') {
            <form [formGroup]="assign" (ngSubmit)="submitAssign()" class="space-y-3" novalidate>
              @if (officersError(); as oe) {
                <p class="text-sm text-red-700" role="alert" data-testid="officers-error">{{ oe }} <button type="button" class="underline" (click)="loadOfficers()">Retry</button></p>
              }
              <ui-form-field label="Assign to" [required]="true" [error]="err('assign', 'officerId')">
                <select uiSelect formControlName="officerId">
                  <option value="">{{ officers() ? 'Choose an officer' : officersError() ? 'Officers unavailable' : 'Loading officers…' }}</option>
                  @for (o of officers() ?? []; track o.id) {
                    <option [value]="o.id">{{ o.name }} ({{ o.markets?.join(', ') }})</option>
                  }
                </select>
              </ui-form-field>
              <button uiButton size="sm" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Assign</button>
            </form>
          }
        }
        @if (busy()) {
          <p class="flex items-center gap-2 text-xs text-muted-foreground" role="status">
            <lucide-icon [img]="icons.Loader2" class="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" /> Working…
          </p>
        }
      </div>
    </section>
  `,
})
export class ClaimActions {
  readonly claim = input.required<StaffClaimDetail>();
  readonly etag = input.required<string | null>();
  readonly changed = output<Versioned<StaffClaimDetail>>();
  readonly reloadClaim = output();

  private readonly api = inject(StaffApi);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  private destroyed = false;

  private readonly announcer = inject(Announcer);

  constructor() {
    inject(DestroyRef).onDestroy(() => (this.destroyed = true));
    // A load put a new claim object (and usually a new ETag) on screen. "Nothing was saved. Reload" is then
    // out of date, so it goes; the typed drafts are separate state and stay. The first run is the page's own first load.
    let first = true;
    effect(() => {
      this.claim();
      this.etag();
      untracked(() => {
        if (first) {
          first = false;
          return;
        }
        const n = this.notice();
        if (n && (n.kind === 'stale' || n.kind === 'unknown')) {
          this.notice.set(null);
          this.announcer.polite('Claim reloaded');
        }
      });
    });
  }

  protected readonly icons = { AlertTriangle, Loader2, Lock };
  protected readonly labels = ACTION_LABELS;
  protected readonly reasons = REJECT_REASONS;
  protected readonly active = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly officers = signal<StaffOfficer[] | null>(null);
  protected readonly officersError = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});
  private readonly touched = signal<Record<string, boolean>>({});

  /** The only source of buttons. */
  protected readonly actions = computed(() => (this.claim().allowedActions ?? []).filter((a) => a in ACTION_LABELS));

  protected readonly note = this.fb.group({ text: ['', [Validators.required, Validators.maxLength(4000)]] });
  protected readonly info = this.fb.group({ question: ['', [Validators.required, Validators.maxLength(4000)]] });
  protected readonly assess = this.fb.group({ reserve: ['', [Validators.required, Validators.pattern(AMOUNT)]] });
  protected readonly approve = this.fb.group({ amount: ['', [Validators.required, Validators.pattern(AMOUNT)]] });
  protected readonly reject = this.fb.group({
    reason: ['', Validators.required],
    explanation: ['', [Validators.required, Validators.maxLength(4000)]],
  });
  protected readonly settle = this.fb.group({ amount: ['', [Validators.required, Validators.pattern(AMOUNT)]] });
  protected readonly assign = this.fb.group({ officerId: ['', Validators.required] });

  protected limitText = () => formatMoney(this.claim().ledger?.coverageLimit, this.claim().currency);
  protected approvedText = () => (this.claim().ledger?.approvedAmount ? formatMoney(this.claim().ledger?.approvedAmount, this.claim().currency) : '');
  protected authorityHint(): string {
    const lim = this.claim().yourAuthorityLimit;
    return lim ? `Your approval authority in this market is ${formatMoney(lim, this.claim().currency)}. Above it a manager must approve.` : 'Amounts above your approval authority need a manager.';
  }

  protected choose(a: string): void {
    // The buttons use aria-disabled (so focus is not lost while a command is in flight); this guard is what refuses the click.
    if (this.busy()) return;
    this.notice.set(null);
    this.fieldErrors.set({});
    if (a === 'PICK_UP') {
      void this.send({ kind: 'pick-up' }, 'Claim picked up.');
      return;
    }
    this.active.set(this.active() === a ? null : a);
    const l = this.claim().ledger;
    if (a === 'ASSESS' && !this.assess.value.reserve && l?.reserve) this.assess.patchValue({ reserve: l.reserve });
    if (a === 'APPROVE' && !this.approve.value.amount && l?.reserve && Number(l.reserve) > 0) this.approve.patchValue({ amount: l.reserve });
    if (a === 'SETTLE' && !this.settle.value.amount && l?.approvedAmount) this.settle.patchValue({ amount: l.approvedAmount });
    if (a === 'ASSIGN' && this.officers() === null) void this.loadOfficers();
  }

  protected async loadOfficers(): Promise<void> {
    this.officersError.set(null);
    try {
      this.officers.set(await this.api.officers());
    } catch (e) {
      const p = ProblemError.from(e);
      this.officersError.set(
        p.status === 403 ? 'You are not allowed to list officers.'
        : p.status === 0 && p.code === 'NETWORK' ? 'Cannot reach the server to list officers.'
        : `Could not load officers${p.detail ? `: ${p.detail}` : '.'}`,
      );
    }
  }

  /** Message for a field: server message first, otherwise our own once the user has tried to submit. */
  protected err(form: string, field: string): string | null {
    const server = this.fieldErrors()[field];
    if (server) return server;
    if (!this.touched()[form]) return null;
    const c = (this[form as 'note'] as { controls: Record<string, import('@angular/forms').AbstractControl> }).controls[field];
    if (!c || c.valid) return null;
    if (c.errors?.['pattern']) return 'Enter a number such as 1500 or 1500.50.';
    if (c.errors?.['maxlength']) return 'This is too long.';
    return field === 'reason' || field === 'officerId' ? 'Choose an option.' : 'This field is required.';
  }

  private valid(form: 'note' | 'info' | 'assess' | 'approve' | 'reject' | 'settle' | 'assign'): boolean {
    if (this.busy()) return false;
    this.touched.update((t) => ({ ...t, [form]: true }));
    this.fieldErrors.set({});
    this[form].markAllAsTouched();
    return this[form].valid && !this.busy();
  }

  protected submitNote(): void {
    if (this.valid('note')) void this.send({ kind: 'notes', body: { text: this.note.getRawValue().text.trim() } }, 'Internal note added.', () => this.note.reset());
  }
  protected submitInfo(): void {
    if (this.valid('info')) void this.send({ kind: 'info-requests', body: { question: this.info.getRawValue().question.trim() } }, 'Request sent to the claimant.', () => this.info.reset());
  }
  protected submitAssess(): void {
    if (this.valid('assess')) void this.send({ kind: 'assessment', body: { reserve: this.assess.getRawValue().reserve.trim() } }, 'Assessment recorded.', () => this.assess.reset());
  }
  protected submitApprove(): void {
    if (this.valid('approve')) void this.send({ kind: 'approval', body: { amount: this.approve.getRawValue().amount.trim() } }, 'Claim approved.', () => this.approve.reset());
  }
  protected submitAssign(): void {
    if (this.valid('assign')) void this.send({ kind: 'assign', body: { officerId: this.assign.getRawValue().officerId } }, 'Claim assigned.', () => this.assign.reset());
  }
  protected async submitReject(): Promise<void> {
    if (!this.valid('reject')) return;
    const v = this.reject.getRawValue();
    const at = this.version();
    const ok = await this.confirmed({
      title: `Reject ${this.claim().claimNumber}?`,
      message: 'The claimant will see the reason and your explanation. Rejection closes the claim and sets the reserve to zero.',
      confirmLabel: 'Reject claim',
      destructive: true,
    });
    if (!ok) return;
    await this.send({ kind: 'rejection', body: { reason: v.reason as (typeof REJECT_REASONS)[number]['value'], explanation: v.explanation.trim() } }, 'Claim rejected.', () => this.reject.reset(), at);
  }
  protected async submitSettle(): Promise<void> {
    if (!this.valid('settle')) return;
    const amount = this.settle.getRawValue().amount.trim();
    const at = this.version();
    const ok = await this.confirmed({
      title: `Settle ${this.claim().claimNumber}?`,
      message: `Record a payment of ${formatMoney(amount, this.claim().currency)}. This closes the claim and cannot be undone.`,
      confirmLabel: 'Settle claim',
    });
    if (!ok) return;
    await this.send({ kind: 'settlement', body: { amount } }, 'Claim settled.', () => this.settle.reset(), at);
  }

  /** The claim and version the officer is looking at right now; a confirmed action must apply to exactly this. */
  private version(): { id: string; etag: string | null } {
    return { id: this.claim().id!, etag: this.etag() };
  }

  /** Busy while the dialog is open, so nothing else can be started under it. */
  private async confirmed(options: Parameters<ConfirmService['confirm']>[0]): Promise<boolean> {
    this.busy.set(true);
    try {
      return await this.confirm.confirm(options);
    } finally {
      this.busy.set(false);
    }
  }

  private async send(cmd: Command, success: string, reset?: () => void, at: { id: string; etag: string | null } = this.version()): Promise<void> {
    this.busy.set(true);
    this.notice.set(null);
    try {
      const res = await this.api.command(at.id, at.etag, cmd);
      reset?.();
      this.active.set(null);
      this.touched.set({});
      this.toast.success(success);
      // The officer may have moved to another claim while this was in flight; nothing here belongs on that screen.
      if (this.destroyed) return;
      // The submit button is about to disappear with its form: park focus on this card's heading.
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#actions-h')?.focus(), { injector: this.injector });
      this.changed.emit(res);
    } catch (e) {
      if (!this.destroyed) this.fail(ProblemError.from(e));
    } finally {
      this.busy.set(false);
    }
  }

  private fail(p: ProblemError): void {
    if (p.code === 'STALE_VERSION' || p.code === 'ILLEGAL_TRANSITION' || p.status === 409 || p.status === 428) {
      this.notice.set({ kind: 'stale', text: 'This claim changed since you opened it, so nothing was saved. Reload to see the latest. What you typed is kept.' });
    } else if (p.status === 0 || p.status >= 500 || p.code === 'EMPTY_RESPONSE') {
      // The command may have been processed even though we never saw the answer.
      this.notice.set({ kind: 'unknown', text: 'Unknown whether this was saved. Reload to check.' });
    } else if (p.code === 'AUTHORITY_EXCEEDED') {
      const lim = this.claim().yourAuthorityLimit;
      this.notice.set({ kind: 'authority', text: `This amount is above your approval authority${lim ? ` (${formatMoney(lim, this.claim().currency)})` : ''}. A manager needs to approve it.` });
    } else if (p.code === 'CONFLICT_OF_INTEREST') {
      this.notice.set({ kind: 'conflict', text: 'You cannot act on this claim because you are its policyholder.' });
    } else {
      const fields = p.fieldMessages;
      if (Object.keys(fields).length) this.fieldErrors.set(fields);
      this.notice.set({ kind: 'error', text: p.detail ?? p.title });
    }
  }
}
