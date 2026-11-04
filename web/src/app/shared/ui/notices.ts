import { Component, input, output } from '@angular/core';
import { LucideAngularModule, AlertTriangle, Inbox } from 'lucide-angular';
import { UiButton } from './button';

/** Amber caveat strip (FleetROS warning-notice.tsx recipe). A caveat, not a failure: the page still works. */
@Component({
  selector: 'ui-warning-notice',
  host: {
    role: 'status',
    class: 'block rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-950/40 dark:text-amber-200',
  },
  template: `<ng-content />`,
})
export class UiWarningNotice {}

/** Error state. Same job as FleetROS error-display.tsx, without the illustration. */
@Component({
  selector: 'ui-error-state',
  imports: [LucideAngularModule, UiButton],
  host: { class: 'block' },
  template: `
    <div role="alert" class="flex min-h-[240px] flex-col items-center justify-center p-12 text-center">
      <div class="mb-6 flex h-14 w-14 items-center justify-center rounded-full bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300">
        <lucide-icon [img]="icon" class="h-7 w-7" aria-hidden="true" />
      </div>
      <div class="max-w-md">
        <div role="heading" [attr.aria-level]="headingLevel()" class="mb-3 text-2xl font-bold text-foreground">{{ title() }}</div>
        <p class="mb-6 text-sm leading-relaxed text-muted-foreground">{{ message() }}</p>
        @if (errorId()) {
          <p class="mb-6 text-xs text-muted-foreground">Reference: <code>{{ errorId() }}</code></p>
        }
        @if (retryable()) {
          <button uiButton type="button" (click)="retry.emit()">Try again</button>
        }
      </div>
    </div>
  `,
})
export class UiErrorState {
  readonly title = input('Something went wrong');
  readonly message = input.required<string>();
  readonly errorId = input<string | null>();
  readonly retryable = input(true);
  /** Level of the title heading (`aria-level`); pick the one that fits under the page's own headings. Default 3. */
  readonly headingLevel = input(3);
  readonly retry = output();
  protected readonly icon = AlertTriangle;
}

@Component({
  selector: 'ui-empty-state',
  imports: [LucideAngularModule],
  host: { class: 'block' },
  template: `
    <div class="flex min-h-[200px] flex-col items-center justify-center rounded-lg border border-dashed p-12 text-center">
      <lucide-icon [img]="icon" class="mb-4 h-10 w-10 text-muted-foreground" aria-hidden="true" />
      <div role="heading" [attr.aria-level]="headingLevel()" class="text-lg font-semibold text-foreground">{{ title() }}</div>
      @if (description()) {
        <p class="mt-1 max-w-md text-sm text-muted-foreground">{{ description() }}</p>
      }
      <div class="mt-6 empty:hidden"><ng-content /></div>
    </div>
  `,
})
export class UiEmptyState {
  readonly title = input.required<string>();
  readonly description = input<string>();
  /** Level of the title heading (`aria-level`). Default 3. */
  readonly headingLevel = input(3);
  protected readonly icon = Inbox;
}
