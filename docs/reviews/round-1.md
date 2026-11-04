# Review Round 1: Backend, Full

**Scope:** `services/`, `contracts/`, `scripts/`, compose and nginx.
**Reviewed snapshot:** tree hash `e7356ab7e3943bf3` (no commits exist, at the candidate's instruction).
**Reviewers:** Java, silent failures, tests, database, security (Opus). Each reviewed independently.
**Result:** 0 Critical, 17 Important, plus Advisory. All Important findings are being fixed, and so are most Advisory ones.

## Important

| # | Source | Finding | Decision |
|---|---|---|---|
| I1 | Security | Known-password accounts are created by migrations in every profile | Fix. Move users and policies to a demo-only migration location |
| I2 | Security | A staff note on an *accepted* document reaches the claimant | Fix. Show only on re-request, through a claimant view |
| I3 | Security | A polyglot upload keeps an attacker-chosen `.html` extension | Fix. Rebuild the extension from the detected type |
| I4 | Security | Postgres, Kafka and APIs are published on all interfaces | Fix. Bind host ports to `127.0.0.1` |
| I5 | Java | Blank transition cells return codes other than `ILLEGAL_TRANSITION` | Fix. Order: access, transition, preconditions, amount, authority |
| I6 | Java | One authority limit is compared across markets' currencies | Fix. Move the limit to `user_market` |
| I7 | Java, Tests (both) | Invariants run on `ClaimAggregate`, a second copy of the rules, and already differ from `Claim` | Fix. Test the real `Claim`, delete the copy, and add a database-level run |
| I8 | Tests | T7: most commands have no test that an activity is recorded | Fix |
| I9 | Tests | Nothing proves approval rewrites the reserve to the approved amount | Fix |
| I10 | Database | Nothing in the database enforces IDR scale; the ledger has no currency | Fix |
| I11 | Database | Claim's market, line and currency aren't tied to its policy, though market is the tenant key | Fix. Composite foreign key and `CHECK`s |
| I12 | Database | Three insights indexes can't be used by any query | Fix. Drop them |
| I13 | Silent failures | A stuck outbox row stops all publishing after one WARN | Fix. Periodic WARN with head row id and age |
| I14 | Silent failures | Failing to get the lock logs a false "recovered" | Fix |
| I15 | Silent failures | Every download I/O error becomes an unlogged 404, and every store failure an unlogged 400 | Fix. Log, and return 5xx |
| I16 | Silent failures | Framework 5xx errors aren't logged | Fix |
| I17 | Silent failures | `assert_num` passes on a missing field, weakening the smoke test | Fix |

T16 (the fault run the tests reviewer also flagged) is already scheduled as Task 19.

## Advisory

| Source | Finding | Decision |
|---|---|---|
| Java | `If-Match` is checked before access | Fix |
| Java | Reusing an idempotency key with a different body returns the original | Fix. Store a request hash; 422 on mismatch |
| Java | Orphan file if a commit fails after upload | Fix |
| Java, Silent | Demo seeder shifts a global clock while the app is serving | Fix. Thread-local offset |
| Java | Role and markets are trusted from the token for up to an hour | **Not fixing.** Stated in the threat model; the short-lived token is the accepted trade-off for stateless verification |
| Java, Silent | A newer schema version goes to the DLT, with no automatic replay | **Not fixing.** Stated as a deployment rule: upgrade consumers first |
| Silent | Smoke's note check doesn't assert status first | Fix |
| Silent | A partial seed is accepted on restart | **Not fixing.** Demo only; `docker compose down -v` resets it |
| Silent | Failed orphan cleanup isn't logged; stream leak if audit save fails | Fix |
| Tests | T14 untested through nginx. Multipart overhead may trip nginx first | Fix. Raise nginx to 11 MB so the API's limit governs, and test through the gateway |
| Tests | Kafka-outage health untested; seeder untested; TRUNCATE untested; ASSIGN version bump untested; a weak matrix assertion; a no-op assertion | Fix |
| Database | Append-only depends on table ownership | **Not fixing.** Stated in the threat model; the production answer is a non-owner app role |
| Database | `paid <= approved` isn't enforced; claim number breaks at 1,000,000; several real queries lack indexes | Fix |
| Database | Transaction stays open during Kafka acks | **Not fixing.** Bounded by the ack timeout, and one poller only |
| Security | Conflict of interest not enforced on reads | Fix. The identity-linking gap goes in the threat model |
| Security | API docs public in every profile | Fix. Off by default, on for demo and tests |
| Security | A claimant's ETag reveals that something internal changed | **Not fixing.** It reveals only that a change happened. Stated in the threat model. A note between viewing and answering gives the claimant a 409 and a reload |

## Fix rounds

Recorded below as they complete.

### Fix group 1: domain, data, tests

**Covers:** I1, I5–I12; `If-Match` order, idempotency hash, conflict on reads, the listed tests, indexes and constraints.

**Evidence, from the agent's run:** `mvn verify` green with 460 tests (425 claims, 35 insights); fresh compose start; smoke 83/0; reconcile 9/0. Breach checks: moving preconditions before the transition table failed `AccessRulesTest` and `StaffApiClaimsTest`; removing the composite policy foreign key failed `MigrationTest`.

**Confirmed by the main thread in the code:** check order in `ClaimDecider`; `ClaimAggregate` gone from the code; demo users only in `db/demo`; V7 constraints present; `ALREADY_ASSIGNED` and `NO_OPEN_INFO_REQUEST` no longer referenced. Removing those two codes is a change from the earlier spec: they could never be reached.

**Challenged:** `ClaimInvariantsDbTest` reached few states: 113 of 1,260 commands succeeded, and only 2 sequences ended `SETTLED`. Sent to group 2 to raise the success rate.

**Scope limit:** IDR whole-amount checks cover `claim` and `financial_entry`. `policy` has no currency column, so its amounts aren't constrained.

### Fix group 2: operations, failures, security

**Covers:** I2–I4, I13–I17, the remaining advisories, and the stronger `ClaimInvariantsDbTest`.

**Evidence, from the agent's run:** fresh compose start with all 5 services healthy; smoke 91/0; reconcile 9/0; gateway upload of 10,485,759 bytes accepted and 10,485,761 bytes refused with the API's 413; `docker compose config` and `ss -ltn` show every host port on 127.0.0.1. Breach checks: skipping the `PAYMENT` ledger write failed `ClaimInvariantsDbTest`; dropping the extension rebuild failed `DocumentApiTest`; restoring `published >= 0` failed `OutboxPollerLoggingTest`.

**`ClaimInvariantsDbTest`:** commands succeeding went from 113 of 1,260 (9%) to 162 of 354 (46%); `SETTLED` from 2 to 23 of 40 sequences; `REJECTED` from 5 to 14.

**Decisions:** claimant `reviewNote` hidden by mapping in the service, with no new DTO, so the contract is unchanged. An unreadable stored file for an existing record is a 500. API docs are also off by default in insights-service, for consistency.

**Limit:** no test forces a real commit failure. The rollback path is exercised through a failing audit save.
