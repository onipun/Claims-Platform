# Report

A claims platform for claimants, claims officers and managers: one Angular app, two Spring Boot services, REST and Kafka. Start with the [README](../README.md) to run it, and the [walkthrough](WALKTHROUGH.md) for a guided tour.

This report states what was built, what was cut, what shows it works, and what remains weak. All results are from 2026-11-04 on one Linux machine.

## Read this first

- **No commit history.** The candidate instructed that nothing be committed, so the working tree has no Git history yet. Review rounds identify code by snapshots outside the repository. The brief asks for a repository with history; that step remains with the candidate.
- **Built largely by AI, under direction.** Claude Code did the planning, code, tests and reviews. The [journal](AI_JOURNAL.md) separates the candidate's decisions from the AI's. The candidate's decisions were mainly on scope and process; most technical decisions are AI proposals under that direction.
- **Domain realism is unverified.** No claims practitioner has reviewed this. Rules come from public sources ([SOURCES](domain/SOURCES.md)). SLAs, authority limits and FX rates are placeholders.
- **No performance figures, no scan results.** The candidate removed load testing, the observability stack and security scanners from scope.

## What was built

| Area | Built |
|---|---|
| Claimant | Report a motor or property incident, late-report flag, document checklist and upload, tracking, answering questions, decisions, updates feed |
| Officer | Work queue, safe pick-up, notes, information requests, document review, reserves, approval within authority, rejection with reason code, settlement |
| Manager | Reassignment, approval above an officer's limit, team workload and performance, case exposure by market and line |
| Records | Append-only reserve ledger and activity trail, enforced by database triggers |
| Architecture | `claims-service` decides and is the system of record. `insights-service` reacts and holds a read model. Transactional outbox to a compacted Kafka topic. See the [ADRs](adr/README.md) |

Each public source item is mapped in [TRACEABILITY](domain/TRACEABILITY.md): 17 built, 1 partial, 9 out of scope, 1 partly out of scope, plus the accident location row.

## What was cut

- Email, SMS and push; self-registration, password reset and single sign-on.
- Reporting through agents and repairers (`reporting_channel` exists but is always `ONLINE`), fraud detection, automatic triage, payment execution.
- Reopening, partial payments, recoveries, loss-adjustment expenses, reinsurance, IBNR.
- Policy administration (seeded), malware scanning, localisation, live FX rates.
- Observability, load testing and automated security scans, by the candidate's decision.

## Evidence by success metric

Each metric was set in the PRD to be judged by something other than the author.

| Metric | Target | Result | Evidence |
|---|---|---|---|
| Domain fidelity | Every source item mapped | All mapped. **Limit:** ACORD PDFs couldn't be fetched, so only their section names are used | [TRACEABILITY](domain/TRACEABILITY.md) |
| Deadline rule | GIA's Saturday-night example passes | Passes | `LateReportRuleTest`, `ClaimantApiTest.sgMotorSaturdayNightIncidentIsDueMonday…` |
| Exposure accuracy | Dashboard equals independent SQL from the ledger | 9 of 9 checks, 0 differences | `scripts/reconcile.sh`, which shares no code with the services |
| Lifecycle integrity | No command sequence breaks an invariant | Passes. Mutation testing on the domain rules: 223 of 224 killed. The survivor, `Line.allows:19`, is equivalent: `EnumSet.contains(null)` already returns false | `ClaimInvariantsPropertyTest`, `ClaimInvariantsDbTest`, [PIT](evidence/pit/summary.txt) |
| No lost events | Killing processes and brokers loses and double-counts nothing | 7 of 7 fault scenarios, from clean volumes | [faults](evidence/faults/ALL.txt) |
| API honesty | Running API conforms to its contract | 9 runs, 6,206 requests, 0 failures. **Limit below** | [Schemathesis](evidence/schemathesis/out/reach.md) |
| Access control | A claimant can't reach another's claim, note or document | Test matrix passes. Threat model maps each threat to a test or a stated gap | `ClaimantApiTest`, `StaffApiClaimsTest`, [THREAT_MODEL](THREAT_MODEL.md) |
| Accessibility | No serious or critical violations on key screens | axe clean on each main screen, with real data. **Limit:** no screen reader was used | `web/e2e/claim-journey.spec.ts` |
| Journey | One journey across all three roles | Passes | `web/e2e/claim-journey.spec.ts` |

## The final run

Run once in sequence after the last fix, from fresh volumes.

| Check | Result |
|---|---|
| `mvn verify` | Exit 0. 498 + 42 tests, 0 failures |
| `ng test` | 199 tests in 28 files |
| `tsc`, `gen:api:check`, production build | Clean |
| Compose fresh start | Healthy in 25 s |
| Smoke through the gateway | 91 passed, 0 failed |
| Reconcile | 9 passed, 0 failed, before and after fuzzing |
| Playwright | 3 passed |
| Fault suite | 7 of 7 |
| Schemathesis, seed 20260101 | 9 runs, 0 failures |
| `scripts/fresh-clone.sh` | All steps pass from a clean copy of the working tree |

After that run, PIT was repeated because the domain code had changed since its first run. That exposed two survivors in the authority rule. A unit test was added, and `mvn verify` and PIT were run again. The rest of the suite wasn't repeated, because only that test changed. Mutation testing isn't part of `mvn verify`; run it with `mvn org.pitest:pitest-maven:mutationCoverage`.

## What testing found that reviews didn't

- Right after an upstream stopped, nginx could hang for up to 60 s on its cached address. The fault run found it, and no reviewer had flagged it.
- Schemathesis found four inputs that returned a 500, and response fields typed non-null that could be null.
- A live test showed the server allowed document review on closed claims, although the review record said this was enforced.
- A two-poller test failed once in 4 runs. The cause was the test's poll count, not the poller. With the lock removed in a scratch copy, the test failed 3 of 3 runs with 14 to 29 version regressions, so it does protect ordering.

## Reviews

| Round | Scope | Important findings |
|---|---|---|
| [1](reviews/round-1.md) | Backend, full | 17 |
| [2](reviews/round-2-delta.md) | Backend fixes | 5 |
| [Frontend](reviews/frontend-round-1.md) | Frontend, full | 18 |
| [Final](reviews/final-round.md) | Whole repository | 13 |
| [Final delta](reviews/final-round.md) | Fixes from the final round | 5, all frontend |

All were fixed, or recorded with a reason. No round found a Critical issue. Reviewers read the code and didn't run it. The last fix group, D-1 to D-5, was read by the orchestrating AI only and had no separate reviewer. Three reviewer findings of missing tests were false positives, because the test already existed; they are recorded in the final round as disputed, pending the candidate's agreement.

## Known weak spots

**Evidence**
- Schemathesis rarely got past validation on `POST /claimant/claims`: of 270 requests with a token, none returned 201. Few staff POSTs succeeded. Deep command paths rest on the integration tests, not on fuzzing.
- Faults not exercised: Postgres down, web down, and unpublished rows sent when `claims-service` recovers.
- "Breach" checks, where a fix is removed to show its test fails, were done by hand or by agents and can't be reproduced from the repository.
- There's no dedicated N+1 test, and the page total's `count(*)` is still a scan. `EXPLAIN` was checked on 300k rows, but no load test was run.
- A few frontend items have no dedicated spec: the `destroyed` check reorder, the queue count text and the staff location row.
- One cold-stack Playwright run took 24.6 s instead of about 6 s, unexplained. It passed.

**Security** (full list in the [threat model](THREAT_MODEL.md))
- No rate limit or lockout, and no per-claimant limit: one token can create claims without bound and fill the volume at 500 MB per claim, which also inflates exposure figures.
- The browser token is in `sessionStorage`, not an HttpOnly cookie. A stated shortcut, with a strict `script-src` as mitigation.
- Plaintext Kafka with no ACLs; no malware scanning; tokens have no audience; role and markets are trusted from the token for up to an hour.
- `npm audit` reports 7 vulnerabilities in development tooling, 1 high, not fixed. No dependency or image scan was run.

**Product**
- The dashboard's "No recent events" can't tell an idle system from a stopped pipeline, because there's no heartbeat.
- `outbox_event` is never purged.
- A hung claim read blocks that page's polling until the gateway's proxy timeout ends it.
- Indicative USD rates, SLAs and authority limits are placeholders.

**Design system**
- FleetROS tokens are followed except for the accent foreground, `--input` and `--muted-foreground`, darkened to meet WCAG AA. This was an AI decision under the candidate's instruction to follow FleetROS, flagged for the candidate.

## What comes next

In order: a practitioner's review of the lifecycle and fields; rate limits and per-claimant limits; heartbeat and alerting on the dead-letter topic; load tests and dependency scans; outbox retention; the secure cookie.
