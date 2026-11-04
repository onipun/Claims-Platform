import { Component, ElementRef, input, model, viewChildren } from '@angular/core';

export interface TabItem {
  id: string;
  label: string;
  disabled?: boolean;
}

/**
 * Tab list (WAI-ARIA tabs pattern: roving tabindex, Arrow/Home/End, automatic activation).
 * Panels are the caller's: `<div role="tabpanel" [id]="'panel-' + id" [attr.aria-labelledby]="'tab-' + id" tabindex="0">`.
 * `tabindex="0"` is for panels whose first content is not focusable (a table, text); omit it when the first thing inside is a control.
 * The list wraps onto a second row on a narrow screen instead of overflowing (WCAG 1.4.10).
 * FleetROS recipes from components/ui/tabs.tsx.
 */
@Component({
  selector: 'ui-tabs',
  host: { class: 'block' },
  template: `
    <div
      role="tablist"
      [attr.aria-label]="label()"
      class="inline-flex min-h-10 max-w-full flex-wrap items-center justify-center gap-1 rounded-md bg-muted p-1 text-muted-foreground"
      (keydown)="onKey($event)"
    >
      @for (t of tabs(); track t.id) {
        <button
          #tab
          type="button"
          role="tab"
          [id]="'tab-' + t.id"
          [attr.aria-selected]="value() === t.id"
          [attr.aria-controls]="'panel-' + t.id"
          [attr.tabindex]="value() === t.id ? 0 : -1"
          [disabled]="t.disabled"
          [attr.data-state]="value() === t.id ? 'active' : 'inactive'"
          class="inline-flex items-center justify-center whitespace-nowrap rounded-sm px-3 py-1.5 text-sm font-medium ring-offset-background transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow-sm"
          (click)="value.set(t.id)"
        >
          {{ t.label }}
        </button>
      }
    </div>
  `,
})
export class UiTabs {
  readonly tabs = input.required<readonly TabItem[]>();
  readonly value = model.required<string>();
  readonly label = input('Sections');
  private readonly buttons = viewChildren<ElementRef<HTMLButtonElement>>('tab');

  protected onKey(e: KeyboardEvent): void {
    const enabled = this.tabs().filter((t) => !t.disabled);
    if (!enabled.length) return;
    const i = enabled.findIndex((t) => t.id === this.value());
    let next = -1;
    if (e.key === 'ArrowRight') next = (i + 1) % enabled.length;
    else if (e.key === 'ArrowLeft') next = (i - 1 + enabled.length) % enabled.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = enabled.length - 1;
    if (next < 0) return;
    e.preventDefault();
    const id = enabled[next].id;
    this.value.set(id);
    this.buttons().find((b) => b.nativeElement.id === 'tab-' + id)?.nativeElement.focus();
  }
}
