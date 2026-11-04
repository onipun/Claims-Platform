import { Component, Directive, input } from '@angular/core';

/** Base block (FleetROS: `animate-pulse rounded-md bg-muted`). Size it with classes: `<div uiSkeleton class="h-4 w-1/2">`. */
@Directive({ selector: '[uiSkeleton]', host: { class: 'block animate-pulse rounded-md bg-muted', 'aria-hidden': 'true' } })
export class UiSkeleton {}

@Component({
  selector: 'ui-skeleton-text',
  imports: [UiSkeleton],
  template: `
    <div class="space-y-2" role="status" aria-label="Loading">
      @for (w of widths(); track $index) {
        <div uiSkeleton class="h-4" [style.width]="w"></div>
      }
    </div>
  `,
})
export class UiSkeletonText {
  readonly lines = input(1);
  protected widths = () => Array.from({ length: this.lines() }, (_, i) => (i === this.lines() - 1 && this.lines() > 1 ? '80%' : '100%'));
}

@Component({
  selector: 'ui-skeleton-table',
  imports: [UiSkeleton],
  template: `
    <div class="space-y-2" role="status" aria-label="Loading">
      @for (r of rowList(); track r) {
        <div class="flex items-center gap-4 border-b py-4">
          @for (c of colList(); track c) {
            <div uiSkeleton class="h-4 flex-1"></div>
          }
        </div>
      }
    </div>
  `,
})
export class UiSkeletonTable {
  readonly rows = input(5);
  readonly columns = input(4);
  protected rowList = () => Array.from({ length: this.rows() }, (_, i) => i);
  protected colList = () => Array.from({ length: this.columns() }, (_, i) => i);
}

@Component({
  selector: 'ui-skeleton-card',
  imports: [UiSkeleton, UiSkeletonText],
  template: `
    <div class="space-y-4 rounded-lg border bg-card p-6" role="status" aria-label="Loading">
      <div uiSkeleton class="h-6 w-3/4"></div>
      <ui-skeleton-text [lines]="3" />
    </div>
  `,
})
export class UiSkeletonCard {}
