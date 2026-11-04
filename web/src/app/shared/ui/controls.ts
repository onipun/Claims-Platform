import { Directive } from '@angular/core';
import { fieldContext } from './form-field';

// Recipes from FleetROS components/ui/input.tsx, textarea.tsx and the select trigger in select.tsx.
const FIELD_BASE =
  'rounded-md border bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 transition-colors';
const OK = 'border-input focus-visible:ring-ring';
const BAD = 'border-red-500 dark:border-red-600 focus-visible:ring-red-500 dark:focus-visible:ring-red-600 bg-red-50 dark:bg-red-950/20';

abstract class FieldControl {
  protected readonly ctx = fieldContext();
  protected id = () => this.ctx?.controlId ?? null;
  protected invalid = () => (this.ctx?.invalid() ? 'true' : 'false');
  protected describedBy = () => this.ctx?.describedBy() ?? null;
}

const HOST = {
  '[attr.id]': 'id()',
  '[attr.aria-invalid]': 'invalid()',
  '[attr.aria-describedby]': 'describedBy()',
};

@Directive({
  selector: 'input[uiInput]',
  host: {
    ...HOST,
    '[class]': 'classes()',
  },
})
export class UiInput extends FieldControl {
  protected classes = () =>
    `flex h-10 w-full file:border-0 file:bg-transparent file:text-sm file:font-medium ${FIELD_BASE} ${this.ctx?.invalid() ? BAD : OK}`;
}

@Directive({
  selector: 'textarea[uiTextarea]',
  host: { ...HOST, '[class]': 'classes()' },
})
export class UiTextarea extends FieldControl {
  protected classes = () => `flex min-h-[80px] w-full ${FIELD_BASE} ${this.ctx?.invalid() ? BAD : OK}`;
}

/** A native `<select>` with the FleetROS trigger look. Native keeps full keyboard and screen-reader behaviour. */
@Directive({
  selector: 'select[uiSelect]',
  host: { ...HOST, '[class]': 'classes()' },
})
export class UiSelect extends FieldControl {
  protected classes = () =>
    `flex h-10 w-full items-center justify-between ${FIELD_BASE} ${this.ctx?.invalid() ? BAD : OK}`;
}
