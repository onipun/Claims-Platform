# Review Round 1: Frontend, Full

**Scope:** `web/src/app`, `web/src/styles/tokens.css`.
**Reviewed snapshot:** `snapshots/frontend-1` in the session scratchpad (no commits exist, at the candidate's instruction).
**Reviewers:** TypeScript, silent failures, accessibility. Each reviewed independently, by reading the code and some screenshots. None ran the app, and race findings aren't reproduced at runtime. Contrast figures were computed by hand.
**Main thread confirmed in the code or a screenshot:** F1 (staff workspace and claimant page), F2, F4, F6, F7, A1.
**Result:** 0 Critical, 18 Important after merging duplicates, plus Advisory.

## Important: state and failures

| # | Source | Finding | Decision |
|---|---|---|---|
| F1 | TS, Silent | Responses can land out of order on claim detail, staff workspace, queue and insights. An officer can see claim A under claim B's URL, and the action buttons post to A. Figures for the old market can show under the new filter | Fix. One request guard per screen; reset per-claim state when the id changes |
| F2 | TS | Claimant page: a background refresh adopts the new ETag while the claimant is typing, which defeats `If-Match`. An answer to one question can post against another | Fix. While text is typed, a changed version sets the stale notice and keeps the old ETag |
| F3 | Silent | Failed refreshes leave old data unmarked: after an upload, on claimant polling, on the queue. After a successful pick-up a failed refresh leaves the claim listed as available | Fix. Visible "out of date" state with retry; remove a picked-up row locally |
| F4 | Silent | Any insights 401 signs the user out. Plan 6.5 expects insights to return 401 and recover by itself after both services restart | Fix. An insights 401 shows "dashboards unavailable"; only a claims-service 401 ends the session |
| F5 | TS, Silent | Unread state survives logout, and its stored time isn't per user, so a second claimant in the same browser can miss updates | Fix. Key per user, reset on session change, poll tied to the shell, "50+" at the cap |
| F6 | A11y, main thread | Confirm dialog spans the viewport with its × over the account icon, and has no accessible name | Fix |
| F7 | Main thread | Exposure table clips Incurred at 1280px, despite the build report. Dates mix formats on the workspace | Fix |

## Important: accessibility

| # | Criterion | Finding | Decision |
|---|---|---|---|
| A1 | 2.4.1 | Skip link leaves the page, because of `<base href="/">` | Fix |
| A2 | 2.4.3, 4.1.3 | No focus move or announcement on route change | Fix |
| A3 | 2.4.3 | Focus lost when async actions disable or remove the focused control | Fix |
| A4 | 4.1.3 | Upload success, claim confirmation, polled changes and "stale" aren't announced | Fix. Announce on transitions only |
| A5 | 4.1.3 | Queue live region repeats its timestamp on every poll | Fix |
| A6 | 2.2.1, 4.1.3 | Toasts vanish on a timer with no pause, and their region is created with its content | Fix. Permanent region, pause on hover and focus, errors stay |
| A7 | 1.4.11 | Input borders about 1.2:1 | Fix. **Departs from FleetROS**, recorded in the report |
| A8 | 1.4.3 | Muted text on muted about 4.35:1 | Fix. **Departs from FleetROS**, recorded in the report |
| A9 | 1.4.10 | Insights tabs overflow at 320px | Fix |
| A10 | 2.1.1 | Scrollable tables aren't reliably reachable by keyboard | Fix |
| A11 | 2.4.6 | Every upload button has the same name | Fix |

## Tests

| Finding | Decision |
|---|---|
| The `loadable` superseded-load test can't fail, because its requests resolve in order | Fix. Controlled promises, resolved out of order |
| No spec covers any race, logout state or the unread service | Fix, with F1, F2, F5 |
| Pick-up 409 spec would pass with the 409 handling removed | Fix |
| Download specs check too little; the staff download has none | Fix |

## Advisory

| Source | Finding | Decision |
|---|---|---|
| TS | A failed reload destroys the officer's typed draft, though the screen promises to keep it | Fix |
| TS | ETag read after the confirm dialog, so a confirmed action can apply to a version the officer didn't see | Fix |
| TS | Staff API paths don't encode ids | Fix |
| TS | A late 401 from an old token can end a newer session | Fix |
| TS, Silent | Session expiry signs out with no redirect or message | Fix |
| Silent | Empty response bodies become blank objects | Fix. Treat as an error |
| Silent | A network error after a command doesn't say the outcome is unknown | Fix. "Reload to check" |
| Silent | Programming errors are reported as an outage | Fix. Separate code, logged |
| Silent | Mark-seen uses the current time, hiding updates not yet shown | Fix |
| Silent | Claimant overview reads only 50 claims and doesn't say so | Fix. Add a notice |
| Silent | "Loading officers…" stays beside the error; old error card stays beside the unavailable banner | Fix |
| A11y | Scroll padding, heading levels, `aria-label` on a span, required legend and colour, focus starts on the destructive button, drawer background not inert, small icon buttons, reduced motion | Fix |
| Silent | `?? 0` and `?? []` on fields the API always sends | **Not fixing.** The generated types mark them optional, but contract tests guard the shape. Figures in insights already show a dash for missing values |
| TS | `poll().refresh()` does nothing during a run; `c.id ?? ''` | **Not fixing.** No caller for the first; `id` is always present for the second |
| A11y | Page titles are static | **Not fixing.** The criterion is met. Named as a next step |
| Build report | Accept and Re-request show to anyone who may add a note, as the API has no specific action | **Not fixing the UI.** The decision also said the server enforces the rule. **The live test proved that wrong:** see "Live test" below |
| Build report | Lists show instants in the browser's zone with no label | **Fix differently.** Label the zone on screen; no API change. Incident times keep the market zone |

## Fix rounds

Recorded below as they complete.

### Fix group 1: state and failures

**Covers:** F1–F5, the Tests table, the TS and Silent advisories, and zone labels.

**Evidence, from the agent's run:** `ng test` 152 passed in 24 files (106 before); `tsc` clean; production build completes. Breach check: removing four guards failed 7 tests, then the guards were restored. Races are exercised by tests resolving requests out of order, not in a running app.

**Found beyond the review:** after a failed refresh, a successful "Reload" left the warning on screen; a command finishing after the officer left the page threw an error. Both fixed.

**Decisions:** a 401 from insights counts as "unavailable". The unread poll now starts from the shell. All instants carry a zone label.

**Left without a test:** the claimant overview's 50-claim notice, the wiring from the updates page to mark-seen, and the session-expired banner on the login page.

### Fix group 2: accessibility and layout

**Covers:** F6, F7, A1–A11, the A11y advisory row.

**Evidence, from the agent's run:** `ng test` 170 passed in 28 files; `tsc` clean; production build completes. axe-core, WCAG 2.2 AA tags: 0 violations on 11 pages in light and dark, against stubbed APIs. `scrollWidth` equals the viewport on all 11 pages at 320px. Contrast by script: `--input` 3.43:1 light and 3.51:1 dark; `--muted-foreground` 4.81:1 on muted. Skip link, route focus, dialog focus and drawer `inert` checked in a browser.

**Actual cause of F6:** not the suspected missing stylesheet. CDK 22 wraps the pane in a popover element with an inline `position: static`, so panel classes on the pane lost.

**Found beyond the review:** the workspace overflowed to 685px on phones; the insights rates line overflowed at 320px; `LiveAnnouncer` text was painted on screen because its hidden style wasn't loaded. All fixed.

**Departures from FleetROS:** `--input` in both themes and light `--muted-foreground`. Commented in `tokens.css`.

**Limits:** axe didn't run on validation errors, an open toast or the open drawer. Announcer transitions (A4, A5) were checked in the browser only, with no unit test. The ledger table still scrolls inside its card at 1280px, as a named, focusable region. No screen-reader software was used.

**Delta review:** the fixes here aren't re-reviewed separately. Task 22's full review covers them, after the live test, which may change the same code.

## Live test: real backend, no stubs

**Evidence, from the agent's run:** Playwright journey passed twice on one rebuilt stack (the second run had no `down -v` of its own), with axe clean on 8 journey screens; axe clean on 16 screens with real data, including validation errors, a toast and the drawer; `ng test` 174; smoke 91/0; reconcile 9/0. All 9 checks passed except the review rule below. Downloads match the upload's SHA-256; a real 409 keeps typed text on both sides; real field paths land on the right inputs.

**Partly forced:** most field-error cases and the 10 MB + 1 upload had the request rewritten in flight, because the client blocks them first; the server's response was real. `IDEMPOTENCY_KEY_REUSED` can't occur through the UI in normal use, so its key was swapped on one request.

| # | Finding | Decision |
|---|---|---|
| L1 | Server allows document review on `SETTLED` and `REJECTED` claims, and sends the claimant a "please upload again" message. `DocumentService.review` checks the actor only | Fix. Rule goes in `LIFECYCLE.md` first. This corrects the earlier recorded decision |
| L2 | Long unbroken text widened the staff page to 1950px | Fixed by the agent |
| L3 | Staff documents said "Not uploaded" beside a listed upload | Fixed by the agent |
| L4 | Size message for a file just over the limit read "10.0 MB. The limit is 10 MB" | Fixed by the agent |
| L5 | A healthy idle system shows the same "Stale" badge as Kafka down, because `asOf` is the newest event time | Fix the wording: "No recent events", figures may be behind if claims are being worked. **No heartbeat.** The limit is stated in the report |
| L6 | A server error on a hidden field gives a banner with nothing highlighted | Fix. List unmatched field errors in the banner |
| L7 | Raw code `CASE_RESERVES_EXCL_IBNR` shown on screen | Fix |
| L8 | Stale notice says "your answer was not sent" when Send was never pressed | Fix the wording |
| L9 | Queue "Pick up" at 390px wasn't examined | Check |
| L10 | A future time on today's date shows its error on the date field | **Not fixing.** The message is still clear |
| L11 | No upper cap on the estimate | **Not fixing** unless Schemathesis shows a server error. The reserve is capped by the policy limit |
