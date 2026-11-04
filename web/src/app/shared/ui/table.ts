import { Directive, input } from '@angular/core';

// Recipes from FleetROS components/ui/table.tsx.
/**
 * Scroll container. Give it `scrollRegion="Name"` when the table inside has no focusable content: it then
 * becomes a focusable, named region so a keyboard user can scroll it (WCAG 2.1.1). Tables with links or
 * buttons in every row do not need it, and an unnamed focus stop would only add noise.
 */
@Directive({
  selector: '[uiTableContainer]',
  host: {
    class: 'relative block w-full overflow-auto',
    '[attr.tabindex]': 'scrollRegion() ? 0 : null',
    '[attr.role]': 'scrollRegion() ? "region" : null',
    '[attr.aria-label]': 'scrollRegion() || null',
  },
})
export class UiTableContainer {
  readonly scrollRegion = input<string>();
}
@Directive({ selector: 'table[uiTable]', host: { class: 'w-full caption-bottom text-sm' } })
export class UiTable {}
@Directive({ selector: 'thead[uiThead]', host: { class: '[&_tr]:border-b' } })
export class UiThead {}
@Directive({ selector: 'tbody[uiTbody]', host: { class: '[&_tr:last-child]:border-0' } })
export class UiTbody {}
@Directive({ selector: 'tr[uiTr]', host: { class: 'border-b transition-colors hover:bg-muted/50 data-[state=selected]:bg-muted' } })
export class UiTr {}
@Directive({ selector: 'th[uiTh]', host: { class: 'h-12 px-4 text-left align-middle font-medium text-muted-foreground' } })
export class UiTh {}
@Directive({ selector: 'td[uiTd]', host: { class: 'p-4 align-middle' } })
export class UiTd {}

export const UI_TABLE = [UiTableContainer, UiTable, UiThead, UiTbody, UiTr, UiTh, UiTd] as const;
