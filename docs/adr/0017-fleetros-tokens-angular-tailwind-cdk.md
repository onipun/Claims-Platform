# ADR 0017: FleetROS design tokens in Angular with Tailwind and Angular CDK

Status: accepted (plan decision D17; originally an instruction from the repository owner)

## Context
The owner asked for the visual language of an existing product, FleetROS, rather than a new one. The UI needs accessible dialogs, focus handling and live announcements.

## Decision
The web app is Angular with Tailwind CSS 3 and Angular CDK. Colours, radii and shadows are CSS custom properties in `web/src/styles/tokens.css`, used through Tailwind's config. CDK supplies overlay, focus trap and live-announcer behaviour; there is no component library.

## Consequences
- One consistent design language, with light and dark themes.
- Departures from FleetROS, made for WCAG AA contrast: the accent foreground, the input border and the muted foreground (darkened to 4.81:1 on the muted background). The owner has not yet confirmed these.
- The Content-Security-Policy keeps `style-src 'unsafe-inline'`, because Angular injects component styles and a static nginx cannot supply a nonce. Scripts stay `'self'`.
- Costs: more hand-written component code than with a component library, and Tailwind 3 is pinned.

## Alternatives rejected
- **Angular Material.** A different visual language.

## Where it is in the code
- `web/src/styles/tokens.css`, `web/tailwind.config.js:16`, `web/angular.json:29` (tokens loaded as a global style).
- CDK use: `web/src/app/shared/ui/dialog.ts`, `announcer.ts`, `web/src/app/layout/shell.ts`.
- Tests: Playwright with axe in `web/e2e/claim-journey.spec.ts`, CSP checks in `web/e2e/csp.spec.ts`.
