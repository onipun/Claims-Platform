# Status (resume from here)

**Owner instruction, verbatim:** "continue untill all milestone meet the dod. no need to commit anything and let it unstage"

**Consequences:** no `git init`, no commits, nothing staged. Review rounds identify code by snapshots at `<scratchpad>/snapshots/round-N`. `scripts/fresh-clone.sh` works from a clean copy of the working tree. The owner commits later. Repo remote: stays local (unanswered). Claims practitioner: unverified (unanswered).

Spec: `.claude/plans/chubb-claims-platform.plan.md`, `docs/domain/LIFECYCLE.md`.

## Tasks

- [x] 0a domain docs
- [x] 0b threat model
- [x] 1 scaffold
- [x] 2 schema
- [x] 3 rules
- [x] 4 identity
- [x] 5 claimant API
- [x] 6 staff API
- [x] 7 documents
- [x] 8 outbox
- [x] 9 contracts, ArchUnit
- [x] 10 consumer
- [x] 11 insights APIs
- [x] 12 smoke, reconcile
- [x] Review round 1 (backend), with delta round 2
- [x] 13 Angular scaffold
- [x] 14 claimant UI (live tested)
- [x] 15 staff UI (live tested)
- [x] 16 manager UI (live tested; Officers merged into Insights > Workload, /staff/officers redirects)
- [x] 17 Playwright (journey plus axe; live checks in docs/reviews/frontend-round-1.md)
- [x] Frontend review (docs/reviews/frontend-round-1.md, including live test; no separate delta: Task 22 covers it)
- [x] 18 PIT (STRONGER mutators on claim.domain; 218/219; evidence in docs/evidence/pit)
- [x] 19 fault runs (7 of 7, twice in a row from clean; evidence in docs/evidence/faults. Found and fixed the nginx hang. Not exercised: Postgres down, web down, unpublished rows sent on claims-service recovery)
- [x] 20 Schemathesis (9 runs, 0 server errors, 0 schema violations; 4 real server defects found and fixed; evidence in docs/evidence/schemathesis. Weak spot for the report: POST /claimant/claims 0 of 260 fuzz requests returned 201, and few staff POSTs succeeded)
- [x] 21 traceability (docs/domain/TRACEABILITY.md: 17 Built, 1 Partial, 9 Out of scope, 1 partial out of scope, plus the location row)
- [x] 22 final review, full suite (docs/reviews/final-round.md; run by the main thread; 498 + 42, ng 199, smoke 91, reconcile 9, Playwright 3, faults 7/7, Schemathesis 0 failures, PIT 223/224)
- [x] 23 README, ADRs, fresh start (scripts/fresh-clone.sh passed each step)
- [x] 24 report, walkthrough (docs/REPORT.md, docs/WALKTHROUGH.md)

## Open items


- Backend reviews: round 1 and delta round 2 fixed and recorded in docs/reviews/. Final review (Task 22) covers everything after. Fix groups touching Maven run one at a time.
- Frontend agents may overlap backend fixes, but stay in their own features/ folder and never run Maven or docker compose. After contracts settle: run `npm run gen:api`, then test the screens against the live backend.
- Toxiproxy and Schemathesis images both pull (checked 2026-11-04): no substitution.
- Indicative USD rates are placeholders: state in report.
- README must state the Node requirement: machine Node 24.14.0 is too old for the Angular 22 CLI; the build used a 24.22.2 tarball in the scratchpad; Docker uses node:24-alpine.
- Report must state: `npm audit` shows 7 dev-tooling vulnerabilities (1 high), not fixed; FleetROS departures: accent foreground, `--input`, and `--muted-foreground` darkened to 4.81:1 on muted for WCAG AA.
- Awaiting the owner's view, not blocking: per-claimant limit reclassified as a stated gap; three disputed test findings in docs/reviews/final-round.md; FleetROS token departures.
- README must say: migrations were edited in place; reset any existing volume with `docker compose down -v`.
