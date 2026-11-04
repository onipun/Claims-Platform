# Review Round 3: Final, Full

**Scope:** whole repository, snapshot `final-1`, tree hash `33bd6a6f0d80ef45`.
**Reviewers:** security (Opus), Java, database, silent failures, tests, TypeScript, accessibility. Each read the code; none ran it.
**Result:** 0 Critical, 13 Important after merging and triage, plus Advisory.
**Trend of Important findings:** 17 (backend, full), 5 (backend, delta), 13 here (whole stack, full). This round has a wider scope, so the numbers aren't like for like.

## Important

| # | Source | Finding | Decision |
|---|---|---|---|
| R1 | TS, Silent | **Regression from the race fix.** A poll or answer overlapping `reload()` leaves `reloading` stuck on, so the claimant's Reload stops working. `onUploaded` then reports a failed refresh after a good upload | Fix |
| R2 | Silent | `claim-detail.page.ts` reload and poll discard the error, so 403 and 404 look like a network blip | Fix |
| R3 | A11y | Claimant Reload gives no feedback: focus drops to the body on success, nothing shows on failure | Fix |
| R4 | A11y | Staff "Nothing was saved. Reload" stays on screen after a successful reload | Fix |
| R5 | Java | Concurrent document review loses an update, and the timeline then holds both outcomes | Fix. `@Version` on `ClaimDocument`, 409 |
| R6 | Java, Security | Upload and review don't synchronise with claim commands. A document can commit on a claim that just settled, and the 50-document cap can be exceeded | Fix. Lock the claim row in those paths |
| R7 | Database | Insights `market char(2)` compared with `text[]` may stop any market index being used. From reading only | Verify with `EXPLAIN`; fix if confirmed |
| R8 | Database | Staff All and Mine queues sort with no supporting index | Fix. Verify with `EXPLAIN` |
| R9 | Tests | Document lists per claim type are marked Built, but only own damage has a test, and the migration test checks counts only | Fix |
| R10 | Tests | D2 recorded as fixed with no test that fails without it | Fix. Clock earlier than the incident |
| R11 | Silent | Fault scripts' lag check skips partitions showing `-`, so lag can read 0 while a partition is unread. "Resumes from its committed offset" isn't actually compared. Reconcile still proves nothing was lost | Fix, then re-run the fault suite |
| R12 | Main thread | **Motor claims have no accident location.** Every loss notice asks where it happened | Fix. Required on motor; `LIFECYCLE.md` first |
| R13 | Tests | No test found for a claim's line not matching its policy | Fix if absent; see "Disputed" |

## Reclassified, for the candidate to see

| Source | Finding | Decision |
|---|---|---|
| Security, rated Important | No per-claimant limit: one token can create claims and fill the volume with 500 MB per claim, inflating exposure figures | **Not fixing; stated gap.** Same class as T2, no rate limit, already a named next step. nginx rate limits would also interfere with the fault and fuzz workloads. R6 closes the cap race. Goes in the threat model and report |

## Disputed findings

The plan says a false positive needs the candidate's agreement. These are recorded rather than dropped.

| Source | Finding | Evidence against |
|---|---|---|
| Tests | No test for invariant 5, at most one open information request | `ClaimInvariantsDbTest.java:150` asserts it after every successful command |
| Tests | No test that a policy belongs to the claimant | `ClaimantApiTest.java:180`, `anotherClaimantsPolicyLooksLikeAPolicyThatDoesNotExist` |

## Advisory

| Source | Finding | Decision |
|---|---|---|
| Security | A manager removed from a market keeps a token, and the missing limit row reads as unlimited. Worse than the recorded stale-token row | Fix. A missing row means no authority |
| Security, Java | Staff list shows the staff member's own claims, though detail returns 403 | Fix. Exclude them |
| Security | NUL characters may cause a 500 and put details in the log | Fix. 422, `logServerErrorDetail=false` |
| Security | Nothing enforces "no third-party scripts" | Fix. CSP, `frame-ancestors`, `nosniff`, `Referrer-Policy` in nginx; Playwright must stay green |
| Security | Tokens have no audience | **Not fixing.** Same user's authority, no escalation today; stated |
| Database | `info_request` lacks an index on `claim_id`; possibly unused indexes | Fix; drop only what `EXPLAIN` or the query list shows unused |
| Database | `claim.claimant_id` isn't tied to the policy holder | Fix. Composite foreign key |
| Database | Status and amount columns can disagree (`SETTLED` with no approved amount) | Fix. `CHECK`s keyed on status |
| Java | N+1 on the claimant's policy list | Fix |
| Java | Outbox rows never purged | **Not fixing.** Stated |
| Java | Poller holds a connection for up to 10s during a broker outage | **Not fixing.** Default pool of 10 absorbs it; stated |
| Tests | `TransitionsTest` assumption can skip the code-to-spec check | Fix. Assert |
| Tests | Estimate of 0, no police report, third-party and injury round trip | Fix. Small |
| Tests | Weak selector checks in some specs | **Not fixing.** They fail if the element is missing, which is their purpose |
| Tests | Breach checks are agent-run, and not reproducible from the repo | **Not fixing.** Stated in the report |
| Silent | `fault-poison` unsupported-schema records unproven; one check in `fault-poller-ack` can't fail; `topic_end`, `projection_fp` and `reconcile` `q` hide failures | Fix |
| Silent | A dead-lettered event's only signal is an ERROR log | Fix. Distinct log line. No alerting: observability was removed by the candidate |
| TS | Refresh after an answer never runs, because `sending` is still true | Fix |
| TS | A failed poll can show the previous queue's rows under the new tab | Fix |
| TS | Route focus lands on `main` before data pages render their `h1` | Fix |
| TS | `claim-actions` destroyed check works by accident | Fix |
| A11y | Doubled announcements; queue count ignores the filter; "highlighted fields" relies on sight | Fix |

## Fix rounds

Recorded below as they complete.

### Fix group: backend and scripts

**Covers:** R5–R13 and the Security, Database, Java, Tests and script advisories.

**Evidence, from the agent's run:** `mvn verify` exit 0, 497 + 42 tests; smoke 91/0; reconcile 9/0; fault suite 7 of 7, twice in a row from clean; Schemathesis 9 runs, 0 server errors and 0 schema violations after the contract change. Breaches shown failing: document lock, authority row check, a `CHECK`, and the `ClaimInvariantsDbTest` clock reset, which closes D2.

**`EXPLAIN`, 300k rows:** R7 confirmed: the market index couldn't serve the filter until `market` became `varchar`. R8: All and Mine now use an index with no sort. The page-total `count(*)` is still a scan.

**Disputed, third case:** R13's line mismatch was already covered by `ClaimantApiTest.wrongLineIsRejected`.

**Open:** Playwright's journey is blocked until the location field is on the form.

**Decisions:** `style-src 'unsafe-inline'` stays, because Angular injects component styles and a static nginx can't supply a nonce; scripts stay `'self'`. `location` lives in the existing `loss_details` JSON, with no new column.

### Fix group: frontend

**Covers:** R1–R4, the R12 form field, and the TS and A11y advisories.

**Evidence, from the agent's run:** `ng test` 188 passed (baseline 175); `tsc` clean; `gen:api:check` ok. Fresh stack A: smoke 91/0, reconcile 9/0, Playwright 3 passed (journey and both CSP tests). Fresh stack B: Playwright 3 passed. R1 breach: reverting the `finally` fix fails its test with "expected true to be false". Browser check against the live stack with real 30 s polling: R1 Reload worked twice in a row; R4 notice cleared and the draft note stayed.

**Found by running it:** the route-focus retry made `claim-journey` fail about half the time, by pulling focus off the Account menu the user had reached while waiting. The retry now leaves focus alone in that case; 10 passes in a row after that, with a spec.

**Unproven:** no dedicated test for the `destroyed` reorder or queue count text; no staff-detail spec for the location row. One cold-stack run took 24.6 s against about 6 s, unexplained, and still passed.

**Decision:** R4's notice clears for `stale` and `unknown` only; authority and conflict notices stay, because they remain true after a reload.

### Delta review of those fixes

**Scope:** snapshots `final-1` to `final-2`, 2,809 diff lines across 65 files. **Reviewers:** code, silent failures, tests, TypeScript. They read the code and ran nothing. **Result:** 0 Critical, 5 Important, all frontend. Backend and scripts: none. **Stop rule:** second consecutive fix round with new blocking findings.

| # | Source | Finding | Decision |
|---|---|---|---|
| D-1 | TS; confirmed by the main thread | **Regression from the route-focus fix.** After clicking a sidebar link, focus stays on that link, so a data page whose `h1` arrives late gets no focus move and no announcement (`route-focus.ts:61-62`) | Fix. Compare with the element focused at navigation |
| D-2 | TS, Silent | Upload during a running Reload shows "Uploaded", but the read may predate it, so the list lacks the file until the next poll (`claim-detail.page.ts:411-416`) | Fix. Re-read on `busy` |
| D-3 | Silent | A failed Reload's alert stays on screen after a later poll succeeds (`claim-detail.page.ts:216`) | Fix. Clear on `apply()` |
| D-4 | Tests | Nothing proves R4 keeps authority and conflict notices or clears `unknown` | Fix |
| D-5 | Tests | Nothing proves the forced refresh after an answer fires; the spec accepts zero requests | Fix |

**Not fixing, stated:** claim-detail reads have no client timeout, so a hung Reload blocks polling and later clicks until the gateway's proxy timeout ends it. `q` in `reconcile.sh:293` leaves one temp file per call.

### Fix group: D-1 to D-5

**Evidence, from the agent's run:** `ng test` 199 passed (baseline 188); `tsc` clean; `gen:api:check` ok; Playwright 3 passed in each of 5 runs on one fresh stack. Live check, `officer.sg`: after clicking the sidebar "Claims" link, `document.activeElement` was the `h1`. Each breach failed its tests, then was restored: `force` dropped, R4 kind filter removed, `followed` comparison removed, `busy` branch removed.

**Review of this group:** read by the main thread only, from the diff against `final-2`; no new finding. No separate reviewer ran, to limit cost. So the stop rule's count stays at two rounds, resting on one reader for the last one.

### Intermittent failure: `OutboxDeliveryTest.twoConcurrentPollersPublishEveryRowInOrder`

**Symptom:** failed once in 4 full `mvn verify` runs with `unpublished()` at 10. Found during the backend fix group, not by a reviewer.

**Root cause: a test defect; the poller is not at fault.** The test gave each poller 60 polls. A lock miss takes about 0.15 ms and a batch holds the lock about 6 ms, so a poller can spend its polls just missing, and if both run out, rows stay unpublished. A probe reproduced it in 14 of 100 repeats, once with the exact symptom: both pollers at 60 polls, 3 hits each, 10 rows left.

| Hypothesis | Evidence | Verdict |
|---|---|---|
| Rows in flight or load | `poll()` is synchronous and the count didn't change after 1.5 s; 0 of 60 failures under 48 CPU spinners against 14 of 100 idle | Rejected |
| Isolation or a stuck head row | Leftover rows always belonged to that repeat; 1 to 4 more polls drained them | Rejected |
| Poller defect | 0 WARNs; every failure at exactly 60 polls; round-1 poller fails at the same rate (13 of 100); with no poll limit 0 of 60, up to 419 polls | Rejected |

**Fix:** a 60 s deadline replaces the poll count (`OutboxDeliveryTest.java:209`). All 40 rows took at most 511 ms under load. No production code changed.

**The test can still fail.** Run by the main thread, in a scratch copy with `tryAcquirePublisherLock()` returning true: 3 of 3 failed, with 14, 20 and 29 version regressions and 40, 35 and 40 duplicates. With the lock in place, every stability run reported 0 of each.

**Stability, from the agent's run:** 10 class repeats, all exit 0; 2 full `mvn -q verify` runs, exit 0; 497 + 42 tests.

**Not explained:** failures clustered in later repeats, never the first, which suggests a warmed-up JVM but wasn't proved. The original failure's output is gone, so the match rests on the identical symptom.

### Full suite and PIT

**Run by the main thread, logs read directly:** `mvn verify` exit 0; `ng test` 199; `tsc`, `gen:api:check`, build clean; fresh stack: smoke 91/0, reconcile 9/0, Playwright 3 passed; fault suite 7 of 7 from clean volumes; Schemathesis seed 20260101, 9 runs, 6,206 requests, 0 failures; reconcile 9/0 again after fuzzing; `scripts/fresh-clone.sh` passed each step.

**Correction:** earlier records said 498 backend tests. That count included a leftover report from `ZzClockMoverTest`, whose source no longer exists; the real figure was 497, and these records now say so. The final figure is 498 + 42, with the test added below.

**PIT re-run:** the first PIT evidence predated five domain changes, so it was run again: 221 of 224, with two new survivors in the authority row check (`AuthorityCheck.check:10`, `Actor.hasRowIn:34`), which only integration tests covered, and PIT runs unit tests. Added a unit test. Result 223 of 224; the survivor is the known equivalent mutant `Line.allows:19`. `mvn verify` run again: exit 0, 498 + 42.

