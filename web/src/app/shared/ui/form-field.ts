import { Component, Directive, InjectionToken, computed, inject, input, signal } from '@angular/core';
import { LucideAngularModule, AlertCircle } from 'lucide-angular';
import { cn } from './cn';

let nextId = 0;

/** What a control needs from its surrounding form field: a stable id and the error/hint element ids. */
export interface FormFieldContext {
  readonly controlId: string;
  readonly invalid: () => boolean;
  readonly describedBy: () => string | null;
}
export const FORM_FIELD = new InjectionToken<FormFieldContext>('FORM_FIELD');

/** Visible key for the asterisk: put it once at the top of a form that has required fields. */
@Component({
  selector: 'ui-required-legend',
  host: { class: 'block text-xs text-muted-foreground' },
  template: `<span class="text-red-700 dark:text-red-400" aria-hidden="true">*</span> required`,
})
export class UiRequiredLegend {}

// Recipes from FleetROS components/ui/label.tsx and form-field.tsx.
@Directive({
  selector: 'label[uiLabel]',
  host: { class: 'text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70' },
})
export class UiLabel {}

/**
 * Label + control + error/hint. The label's `for` is bound to the projected control (a `uiInput`,
 * `uiTextarea` or `uiSelect`), which also gets `aria-invalid` and `aria-describedby` pointing at the
 * error text, announced via role="alert".
 */
@Component({
  selector: 'ui-form-field',
  imports: [LucideAngularModule, UiLabel],
  providers: [{ provide: FORM_FIELD, useExisting: UiFormField }],
  host: { '[class]': 'hostClass()' },
  template: `
    @if (label()) {
      <label uiLabel [for]="controlId" [class]="labelClass()">
        {{ label() }}
        @if (required()) {
          <span class="text-red-700 dark:text-red-400" aria-hidden="true">*</span>
          <span class="sr-only">(required)</span>
        }
      </label>
    }
    <ng-content />
    @if (error()) {
      <div [id]="errorId" role="alert" class="flex items-start gap-2 text-sm text-red-600 dark:text-red-400">
        <lucide-icon [img]="alertIcon" class="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
        <span>{{ error() }}</span>
      </div>
    } @else if (hint()) {
      <p [id]="hintId" class="text-xs text-muted-foreground">{{ hint() }}</p>
    }
  `,
})
export class UiFormField implements FormFieldContext {
  readonly label = input<string>();
  readonly error = input<string | null | undefined>();
  readonly hint = input<string>();
  readonly required = input(false);

  readonly controlId = `ui-field-${++nextId}`;
  readonly errorId = `${this.controlId}-error`;
  readonly hintId = `${this.controlId}-hint`;
  protected readonly alertIcon = AlertCircle;

  readonly invalid = computed(() => !!this.error());
  readonly describedBy = computed(() => (this.error() ? this.errorId : this.hint() ? this.hintId : null));
  protected readonly hostClass = signal('block space-y-2');
  protected labelClass = () => cn('flex items-center gap-2', this.error() && 'text-red-700 dark:text-red-400');
}

/** Shared control behaviour: id, aria-invalid and aria-describedby from the enclosing ui-form-field. */
export function fieldContext(): FormFieldContext | null {
  return inject(FORM_FIELD, { optional: true });
}
