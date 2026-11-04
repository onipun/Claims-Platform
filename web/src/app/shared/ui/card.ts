import { Directive } from '@angular/core';

// Recipes from FleetROS components/ui/card.tsx.
@Directive({ selector: '[uiCard]', host: { class: 'rounded-lg border bg-card text-card-foreground shadow-sm' } })
export class UiCard {}
@Directive({ selector: '[uiCardHeader]', host: { class: 'flex flex-col space-y-1.5 p-6' } })
export class UiCardHeader {}
@Directive({ selector: '[uiCardTitle]', host: { class: 'block text-2xl font-semibold leading-none tracking-tight' } })
export class UiCardTitle {}
@Directive({ selector: '[uiCardDescription]', host: { class: 'block text-sm text-muted-foreground' } })
export class UiCardDescription {}
@Directive({ selector: '[uiCardContent]', host: { class: 'block p-6 pt-0' } })
export class UiCardContent {}
@Directive({ selector: '[uiCardFooter]', host: { class: 'flex items-center p-6 pt-0' } })
export class UiCardFooter {}

export const UI_CARD = [UiCard, UiCardHeader, UiCardTitle, UiCardDescription, UiCardContent, UiCardFooter] as const;
