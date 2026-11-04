import { Component, ElementRef, effect, inject, input, signal, computed, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAngularModule, CheckCircle2, Loader2, ShieldCheck } from 'lucide-angular';
import { ProblemError } from '../../core/api/problem-error';
import { loadable } from '../../core/util/loadable';
import { UI } from '../../shared/ui';
import { ClaimantApi } from './claimant.api';
import { formatDay } from './claimant.format';
import { CLAIM_TYPES_BY_LINE, CLAIM_TYPE_LABEL, lineLabel } from './claimant.labels';
import type { ClaimDetail, Policy } from './claimant.types';
import { ClaimFormValue, EMPTY_CLAIM_FORM, FieldErrors, IdempotencyGuard, buildRequest, isThirdPartyShown, serverFieldToControl, validateClaim } from './claim-form';

@Component({
  selector: 'app-report-claim-page',
  imports: [ReactiveFormsModule, RouterLink, LucideAngularModule, UI],
  template: `
    <div class="mx-auto max-w-3xl space-y-6">
      @if (created(); as c) {
        <section uiCard data-testid="confirmation" aria-labelledby="done-title">
          <div uiCardHeader class="items-start">
            <lucide-icon [img]="icons.CheckCircle2" class="h-10 w-10 text-green-600" aria-hidden="true" />
            <h1 id="done-title" uiCardTitle class="pt-2 focus:outline-none" tabindex="-1" aria-describedby="done-number">We have received your claim</h1>
            <p uiCardDescription>Keep this claim number. It confirms we have your report.</p>
          </div>
          <div uiCardContent class="space-y-6">
            <p id="done-number" class="break-all rounded-md bg-muted px-4 py-3 text-2xl font-bold tracking-wide" data-testid="claim-number">{{ c.claimNumber }}</p>
            @if (c.late) {
              <ui-warning-notice>This report was made after the usual reporting deadline. We will not reject it for that, and your reason, if you gave one, is passed to the claims officer.</ui-warning-notice>
            }
            <div>
              <h2 class="mb-2 font-semibold">What happens next</h2>
              <ol class="list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
                <li><span class="font-medium text-foreground">Upload your documents.</span> Documents help us settle faster. The list of what we need is on your claim.</li>
                <li>A claims officer reviews your claim. We may ask you a question, and you will see it on your claim.</li>
                <li>We tell you the decision here, in Updates.</li>
              </ol>
            </div>
            <div class="flex flex-wrap gap-3">
              <a uiButton [routerLink]="['/claimant/claims', c.id]" fragment="documents">Upload documents</a>
              <a uiButton variant="outline" [routerLink]="['/claimant/claims', c.id]">View this claim</a>
              <button uiButton variant="ghost" type="button" (click)="another()">Report another claim</button>
            </div>
          </div>
        </section>
      } @else {
        <div>
          <h1 class="text-2xl font-bold tracking-tight">Report a claim</h1>
          <p class="text-sm text-muted-foreground">Tell us what happened. It takes a few minutes, and you can add documents afterwards.</p>
        </div>

        <section aria-labelledby="pick-policy">
          <h2 id="pick-policy" class="mb-3 text-lg font-semibold">1. Choose the policy</h2>
          @switch (policies.state().status) {
            @case ('loading') { <div class="grid gap-3 sm:grid-cols-2" aria-busy="true"><ui-skeleton-card /><ui-skeleton-card /></div> }
            @case ('error') {
              <section uiCard><ui-error-state [message]="policies.error()?.detail ?? policies.error()?.title ?? ''" [errorId]="policies.error()?.errorId" (retry)="policies.load()" /></section>
            }
            @case ('empty') { <ui-empty-state title="No policies found" description="You need a policy to report a claim. Contact your insurer if you expect to see one." /> }
            @case ('ready') {
              <div role="radiogroup" aria-labelledby="pick-policy" class="grid gap-3 sm:grid-cols-2" data-testid="policy-choice">
                @for (p of policies.data(); track p.id) {
                  <label class="flex cursor-pointer gap-3 rounded-lg border bg-card p-4 transition-colors hover:bg-muted/40 has-[:checked]:border-primary has-[:checked]:ring-2 has-[:checked]:ring-primary has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
                    <input type="radio" name="policy" class="mt-1" [value]="p.id" [checked]="policy()?.id === p.id" (change)="choose(p)" />
                    <span class="min-w-0 space-y-1 text-sm">
                      <span class="flex flex-wrap items-center gap-2"><span class="break-all font-medium">{{ p.policyNumber }}</span><span uiBadge variant="secondary">{{ line(p.line) }}</span></span>
                      <span class="block text-muted-foreground">{{ day(p.effectiveFrom) }} to {{ day(p.effectiveTo) }}</span>
                      <span class="block">Coverage limit {{ p.coverageLimit | money: p.currency }}</span>
                      <span class="block">Deductible {{ p.deductible | money: p.currency }}</span>
                    </span>
                  </label>
                }
              </div>
            }
          }
        </section>

        @if (policy(); as pol) {
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="space-y-6" data-testid="claim-form">
            @if (banner(); as b) {
              <div role="alert" data-testid="form-error" class="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">
                {{ b.text }}
                @if (b.extra.length) { <ul class="mt-1 list-disc pl-5">@for (x of b.extra; track x) { <li>{{ x }}</li> }</ul> }
                @if (b.ref) { <span class="mt-1 block text-xs">Reference: {{ b.ref }}</span> }
              </div>
            }

            <ui-required-legend />
            <section uiCard>
              <div uiCardHeader><h2 uiCardTitle class="text-lg">2. What happened</h2></div>
              <div uiCardContent class="grid gap-4 sm:grid-cols-2">
                <ui-form-field class="sm:col-span-2" label="Type of claim" [required]="true" [error]="err('claimType')">
                  <select uiSelect formControlName="claimType">
                    <option value="">Choose...</option>
                    @for (t of types(); track t) { <option [value]="t">{{ typeLabel[t] }}</option> }
                  </select>
                </ui-form-field>
                <ui-form-field label="Date of incident" [required]="true" [error]="err('incidentDate')" [hint]="zoneHint()">
                  <input uiInput type="date" formControlName="incidentDate" [attr.min]="pol.effectiveFrom" [attr.max]="pol.effectiveTo" />
                </ui-form-field>
                <ui-form-field label="Time of incident" [required]="true" [error]="err('incidentTime')">
                  <input uiInput type="time" formControlName="incidentTime" />
                </ui-form-field>
                <ui-form-field class="sm:col-span-2" label="Description" [error]="err('description')" hint="What happened, in your own words.">
                  <textarea uiTextarea rows="4" formControlName="description"></textarea>
                </ui-form-field>
                <ui-form-field label="Estimated loss ({{ pol.currency }})" [required]="true" [error]="err('estimate')" hint="Your best estimate, before any assessment.">
                  <input uiInput type="text" inputmode="decimal" autocomplete="off" formControlName="estimate" />
                </ui-form-field>
                <ui-form-field label="Contact phone" [error]="err('contactPhone')">
                  <input uiInput type="tel" autocomplete="tel" formControlName="contactPhone" />
                </ui-form-field>
                <ui-form-field class="sm:col-span-2" label="Reason for late reporting (optional)" [error]="err('lateReason')" hint="Only needed if you are reporting after the usual deadline. A late report is never rejected for that alone.">
                  <textarea uiTextarea rows="2" formControlName="lateReason"></textarea>
                </ui-form-field>
              </div>
            </section>

            @if (pol.line === 'MOTOR') {
              <section uiCard data-testid="motor-fields">
                <div uiCardHeader><h2 uiCardTitle class="text-lg">3. Vehicle and people involved</h2></div>
                <div uiCardContent class="grid gap-4 sm:grid-cols-2">
                  <ui-form-field label="Vehicle registration" [required]="true" [error]="err('vehicleRegistration')">
                    <input uiInput type="text" autocapitalize="characters" formControlName="vehicleRegistration" />
                  </ui-form-field>
                  <ui-form-field label="Driver at the time" [required]="true" [error]="err('driverName')">
                    <input uiInput type="text" autocomplete="name" formControlName="driverName" />
                  </ui-form-field>
                  <ui-form-field class="sm:col-span-2" label="Accident location" [required]="true" [error]="err('location')" hint="Where it happened, for example a street, junction or car park.">
                    <input uiInput type="text" autocomplete="off" formControlName="location" />
                  </ui-form-field>
                  <ui-form-field class="sm:col-span-2" label="Police report reference (optional)" [error]="err('policeReport')">
                    <input uiInput type="text" formControlName="policeReport" />
                  </ui-form-field>

                  @if (value().claimType !== 'THIRD_PARTY_DAMAGE') {
                    <label class="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" formControlName="hasThirdParty" /> Another vehicle or person was involved</label>
                  }
                  @if (thirdParty()) {
                    <fieldset class="grid gap-4 rounded-md border p-4 sm:col-span-2 sm:grid-cols-2" data-testid="third-party">
                      <legend class="px-1 text-sm font-medium">Other party</legend>
                      <ui-form-field label="Name" [error]="err('tpName')"><input uiInput type="text" formControlName="tpName" /></ui-form-field>
                      <ui-form-field label="Phone" [error]="err('tpPhone')"><input uiInput type="tel" formControlName="tpPhone" /></ui-form-field>
                      <ui-form-field label="Insurer" [error]="err('tpInsurer')"><input uiInput type="text" formControlName="tpInsurer" /></ui-form-field>
                      <ui-form-field label="Vehicle number" [error]="err('tpVehicle')"><input uiInput type="text" formControlName="tpVehicle" /></ui-form-field>
                    </fieldset>
                  }

                  <label class="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" formControlName="injury" /> Someone was injured</label>
                  @if (value().injury) {
                    <ui-form-field class="sm:col-span-2" label="Describe the injuries" [error]="err('injuryDescription')">
                      <textarea uiTextarea rows="3" formControlName="injuryDescription"></textarea>
                    </ui-form-field>
                  }
                </div>
              </section>
            } @else {
              <section uiCard data-testid="property-fields">
                <div uiCardHeader><h2 uiCardTitle class="text-lg">3. The property</h2></div>
                <div uiCardContent class="grid gap-4 sm:grid-cols-2">
                  <ui-form-field class="sm:col-span-2" label="Address of the loss" [required]="true" [error]="err('address')">
                    <input uiInput type="text" autocomplete="street-address" formControlName="address" />
                  </ui-form-field>
                  <ui-form-field label="Cause of the loss" [required]="true" [error]="err('cause')" hint="For example: burst pipe, kitchen fire.">
                    <input uiInput type="text" formControlName="cause" />
                  </ui-form-field>
                  <ui-form-field label="Occupancy (optional)" [error]="err('occupancy')" hint="For example: owner-occupied, rented out, vacant.">
                    <input uiInput type="text" formControlName="occupancy" />
                  </ui-form-field>
                </div>
              </section>
            }

            <div class="flex flex-wrap items-center gap-3">
              <button uiButton type="submit" [attr.aria-disabled]="submitting() ? 'true' : null" [attr.aria-busy]="submitting()" data-testid="submit-claim">
                @if (submitting()) { <lucide-icon [img]="icons.Loader2" class="mr-2 h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />Submitting... } @else { Submit claim }
              </button>
              <p class="flex items-center gap-1 text-xs text-muted-foreground"><lucide-icon [img]="icons.ShieldCheck" class="h-4 w-4" aria-hidden="true" />Pressing submit twice will not create two claims.</p>
            </div>
          </form>
        }
      }
    </div>
  `,
})
export class ReportClaimPage {
  private readonly api = inject(ClaimantApi);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  /** Optional `?policyId=` from the Policies page. */
  readonly policyId = input<string>();

  protected readonly icons = { CheckCircle2, Loader2, ShieldCheck };
  protected readonly typeLabel = CLAIM_TYPE_LABEL;
  protected readonly line = lineLabel;
  protected readonly day = (v: string | undefined) => formatDay(v);
  protected readonly policies = loadable<Policy[]>(() => this.api.policies());
  protected readonly policy = signal<Policy | null>(null);
  protected readonly form = this.fb.group({ ...EMPTY_CLAIM_FORM });
  protected readonly value = signal<ClaimFormValue>({ ...EMPTY_CLAIM_FORM });
  protected readonly submitting = signal(false);
  protected readonly submitted = signal(false);
  protected readonly problem = signal<ProblemError | null>(null);
  protected readonly serverErrors = signal<FieldErrors>({});
  protected readonly created = signal<ClaimDetail | null>(null);
  private readonly idem = new IdempotencyGuard();

  protected readonly types = computed(() => {
    const l = this.policy()?.line;
    return l ? CLAIM_TYPES_BY_LINE[l] : [];
  });
  protected readonly thirdParty = computed(() => this.policy()?.line === 'MOTOR' && isThirdPartyShown(this.value()));
  private readonly clientErrors = computed(() => validateClaim(this.value(), this.policy()));
  protected readonly zoneHint = computed(() => 'In the local time where the incident happened.');

  /** Whether the control for a form field is rendered right now (mirrors the template's conditions). */
  private onScreen(c: keyof ClaimFormValue): boolean {
    const motor = this.policy()?.line === 'MOTOR';
    switch (c) {
      case 'vehicleRegistration': case 'driverName': case 'location': case 'policeReport': return motor;
      case 'tpName': case 'tpPhone': case 'tpInsurer': case 'tpVehicle': return this.thirdParty();
      case 'injuryDescription': return motor && !!this.value().injury;
      case 'address': case 'cause': case 'occupancy': return !motor;
      default: return true;
    }
  }

  protected readonly banner = computed(() => {
    const p = this.problem();
    if (!p) return null;
    // A server error counts as "highlighted" only if its field is mapped to a control that is on screen now.
    const unmapped = p.fieldErrors
      .filter((f) => { const c = f.field ? serverFieldToControl(f.field) : null; return !c || !this.onScreen(c); })
      .map((f) => f.message || f.field || '')
      .filter(Boolean);
    const highlighted = p.fieldErrors.length - unmapped.length;
    let text: string;
    if (p.code === 'NETWORK') text = 'Cannot reach the server. Nothing was lost: check your connection and press Submit again.';
    else if (p.code === 'IDEMPOTENCY_KEY_REUSED') text = 'Your details changed since an earlier attempt. Please check them and submit again.';
    else if (p.status >= 500) text = 'The service is unavailable right now. Press Submit again in a moment; this will not create a second claim.';
    else if (p.fieldErrors.length) {
      text = highlighted === 0 ? 'The claim could not be submitted:'
        : unmapped.length ? `Fix the ${highlighted} ${highlighted === 1 ? 'field' : 'fields'} with errors and the problems listed here:` : `Fix the ${highlighted} ${highlighted === 1 ? 'field' : 'fields'} with errors.`;
    }
    else text = p.detail ?? p.title;
    return { text, extra: unmapped, ref: p.errorId };
  });

  constructor() {
    void this.policies.load().then(() => {
      const id = this.policyId();
      const list = this.policies.data();
      const match = id && list ? list.find((x) => x.id === id) : list?.length === 1 ? list[0] : undefined;
      if (match) this.choose(match);
    });
    let prev = this.form.getRawValue();
    this.form.valueChanges.subscribe(() => {
      const now = this.form.getRawValue();
      const srv = this.serverErrors();
      if (Object.keys(srv).length) {
        const next = { ...srv };
        for (const k of Object.keys(now) as (keyof ClaimFormValue)[]) if (now[k] !== prev[k]) delete next[k];
        this.serverErrors.set(next);
      }
      prev = now;
      this.value.set(now);
    });
    effect(() => {
      this.policy();
      untracked(() => this.clearForLine());
    });
  }

  /** Switching policy resets the line-specific parts, since a claim type valid for one line is not for the other. */
  private clearForLine(): void {
    const t = this.form.controls.claimType.value;
    const l = this.policy()?.line;
    if (t && l && !CLAIM_TYPES_BY_LINE[l].includes(t as never)) this.form.controls.claimType.setValue('');
  }

  protected choose(p: Policy): void {
    this.policy.set(p);
    this.problem.set(null);
    this.serverErrors.set({});
  }

  protected err(name: keyof ClaimFormValue): string | null {
    const srv = this.serverErrors()[name];
    if (srv) return srv;
    const touched = this.form.controls[name].touched || this.submitted();
    return touched ? (this.clientErrors()[name] ?? null) : null;
  }

  protected async submit(): Promise<void> {
    if (this.submitting()) return;
    const pol = this.policy();
    if (!pol) return;
    this.submitted.set(true);
    this.problem.set(null);
    this.value.set(this.form.getRawValue());
    if (Object.keys(this.clientErrors()).length) {
      this.form.markAllAsTouched();
      setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
      return;
    }
    const body = buildRequest(this.form.getRawValue(), pol);
    const key = this.idem.keyFor(body);
    this.submitting.set(true);
    try {
      const res = await this.api.submit(body, key);
      this.idem.reset();
      this.created.set(res.body);
      // The form (and the submit button that had focus) is replaced by the confirmation: focus its heading. Focus is the one announcement; the heading is described by the claim number.
      setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('#done-title')?.focus());
    } catch (e) {
      const p = ProblemError.from(e);
      this.problem.set(p);
      if (p.code === 'IDEMPOTENCY_KEY_REUSED') this.idem.reset();
      const mapped: FieldErrors = {};
      for (const f of p.fieldErrors) {
        const c = f.field ? serverFieldToControl(f.field) : null;
        if (c && f.message && !(c in mapped)) mapped[c] = f.message;
      }
      this.serverErrors.set(mapped);
      setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"], [role="alert"]')?.focus?.());
    } finally {
      this.submitting.set(false);
    }
  }

  protected another(): void {
    this.created.set(null);
    this.form.reset({ ...EMPTY_CLAIM_FORM });
    this.value.set({ ...EMPTY_CLAIM_FORM });
    this.submitted.set(false);
    this.problem.set(null);
    this.serverErrors.set({});
    this.idem.reset();
  }
}
