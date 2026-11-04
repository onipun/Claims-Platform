import { Component, input, output } from '@angular/core';
import { UiButton } from '../../shared/ui/button';

/**
 * A page change swaps the list for a loading state and back, so the Previous/Next button that was
 * clicked is destroyed. Callers run this after the load: focus goes to the "Page x of y" text.
 */
export function focusPagerStatus(host: HTMLElement): void {
  host.querySelector<HTMLElement>('[data-pager-status]')?.focus();
}

/** Previous / next with "Page x of y". Pages are zero-based in the API and one-based on screen. */
@Component({
  selector: 'claimant-pager',
  imports: [UiButton],
  host: { class: 'block' },
  template: `
    @if (totalPages() > 1) {
      <nav class="flex items-center justify-between gap-3 pt-4" aria-label="Pagination">
        <button uiButton variant="outline" size="sm" type="button" [disabled]="page() <= 0" (click)="go.emit(page() - 1)">Previous</button>
        <p class="text-sm text-muted-foreground focus:outline-none" tabindex="-1" data-pager-status>Page {{ page() + 1 }} of {{ totalPages() }}<span class="hidden sm:inline"> ({{ total() }} in all)</span></p>
        <button uiButton variant="outline" size="sm" type="button" [disabled]="page() + 1 >= totalPages()" (click)="go.emit(page() + 1)">Next</button>
      </nav>
    }
  `,
})
export class Pager {
  readonly page = input(0);
  readonly totalPages = input(0);
  readonly total = input(0);
  readonly go = output<number>();
}
