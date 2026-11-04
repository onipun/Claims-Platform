# Review Round 2: Backend Fixes, Delta

**Scope:** the diff between `snapshots/round-1` and the code after fix groups 1 and 2 (about 4,000 lines).
**Reviewers:** code correctness, silent failures, test strength. Each read the code; none ran it.
**Main thread ran:** `mvn verify` after group 2: exit 0, 440 + 37 tests, no failures, errors or skips.
**Main thread confirmed in the code:** D1, D2, D5.
**Result:** 0 Critical, 5 Important, plus Advisory. No product defect was found in I1–I17 apart from D5; the other four Important findings are tests that prove less than they claim.

## Important

| # | Source | Finding | Decision |
|---|---|---|---|
| D1 | Tests | I1 has no real guard. `src/test/resources/application.properties` loads `db/demo` for all tests, and `DefaultProfileTest` pins its own locations. If the real defaults gained `db/demo`, known passwords would ship and no test would fail | Fix. Assert on the real YAML files |
| D2 | Tests | `ClaimInvariantsDbTest` doesn't set the clock, and other tests leave it moved. Its result can depend on test order | Fix |
| D3 | Tests | Invariants 2–5 are read from the database only at the end of a sequence, so a later command can hide a violation | Fix. Check after every successful command |
| D4 | Tests | Invariant 1 is probed by chance. Most probes after a terminal state fail on access before reaching the transition table | Fix. Run each action as an authorised actor, and assert `ILLEGAL_TRANSITION` with no change in SQL |
| D5 | Tests, main thread | `claim_paid_within_approved` accepts any `paid` when `approved_amount` is null, because of `coalesce`. The earlier fix doesn't enforce the rule it was meant to | Fix the constraint |

## Advisory

| Source | Finding | Decision |
|---|---|---|
| Silent | Client disconnects during download log as server errors; 5xx after the response started tries to write a body | Fix |
| Silent | A server-side multipart failure (temp disk full) returns a quiet 400, the I15 pattern again | Fix. Log and 5xx when the cause is an I/O failure |
| Silent | Poller's WARN has no stack trace, so a code bug looks like a broker timeout | Fix |
| Silent | Stuck WARN can name a head row already published | Fix |
| Silent | A poller that never gets the lock says nothing, so publishing can stop silently | Fix. Warn at the stuck interval |
| Silent | `exposure_is` treats a missing field as 0; `die` reasons missing from the failed list; stale status in a message | Fix |
| Correctness | Ledger currency isn't tied to the claim's currency | Fix. Composite foreign key |
| Correctness | Editing V3, V5 and V7 in place, and `V1000` sorting above later migrations, break an existing demo volume | **Document.** README: reset with `docker compose down -v`. Nothing has shipped |
| Correctness | Default profile starts with no way to create a user; the demo script ships in the jar | **Document** in the threat model. Provisioning is out of scope |
| Correctness | A commit that succeeds but reports failure could delete a stored file. From the reviewer's memory of Spring, unverified here | **Not fixing.** Narrow window; stated |
| Silent | File handle leak if commit fails after a download opens | **Not fixing.** Narrow window; stated |
| Tests | Stuck WARN test can't tell kept state from reset; head row after a partial batch and the ack timeout untested; `paid` not in the IDR check test; failed-command check omits some columns; insights docs default untested; seeder back-dating and offset reset untested; conflict of interest on document content and review untested | Fix |
| Tests | Matrix fixture recreated per case; `multi.officer` left in the shared database | **Not fixing.** Harmless today |
| Silent | Unverified whether a JWKS outage in insights is logged or only looks like a 401 | Task 19 tests it |

## Fix rounds

Recorded below as they complete.

### Fix group: D1–D5 and advisories

**Evidence, from the agent's run:** `mvn verify` exit 0 after reverting breaches, 458 + 41 tests; contract drift tests pass and `contracts/` is unchanged; fresh compose start with 5 services healthy; smoke 91/0; reconcile 9/0. Breach checks: `db/demo` in the base YAML failed `DemoLocationsConfigTest`; removing the ledger key and restoring the old `paid` check failed three `MigrationTest` cases; allowing `ADD_NOTE` on `SETTLED` failed `ClaimInvariantsDbTest`.

**`ClaimInvariantsDbTest`:** 162 commands succeeded and 83 failed (66%), plus 322 terminal probes refused across 37 terminal claims. Invariants 2–5 are checked after every successful command.

**Not proven:** D2. The agent's check moved the clock forward to 2031, which can't produce the failure the reviewer described: a clock earlier than the incident date. The reset is in place and correct, but no failing case was shown.

**Done by the main thread:** the threat model line on the two narrow windows; the stale status in smoke's baseline message; scripts made executable. The agent had looked for this record before it was written, and skipped those.

**Side effect:** the ledger currency key means a claim with ledger rows can't change currency. That's the intent.
