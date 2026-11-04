# ADR 0006: Optimistic locking with If-Match

Status: accepted (plan decision D6)

## Context
Two officers can click "pick up" on the same claim, or a manager can approve from a screen that is minutes old. Contention is rare, and a lock cannot span a person's open screen.

## Decision
The claim row has a JPA `@Version`, exposed as the ETag. Staff commands and the claimant's answer require `If-Match`: missing gives 428 `PRECONDITION_REQUIRED`, stale gives 409 `STALE_VERSION`. Staff-only changes (notes, reassignment) also bump the version, so a claimant's ETag changes without revealing what changed.

## Consequences
- The pick-up race has one winner and one clear loser. Stale screens cannot decide.
- Costs: clients must send the header (the generated API client and the UI do), and a loser must reload. Concurrent writers see 409 where a lock would have made them wait.

## Alternatives rejected
- **Pessimistic locks.** A database lock cannot be held while a person reads a screen.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/claim/Claim.java:163` (`@Version`), `StaffClaimService.java:147` and `ClaimantClaimService.java:94` (428), `web/ApiExceptionHandler.java:109` (409).
- Tests: `ClaimantApiTest.staleIfMatchIs409StaleVersion`; the concurrent-writer case at `ClaimantApiTest.java:640`; `web/src/app/features/staff/claim-workspace.races.spec.ts`.
