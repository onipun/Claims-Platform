# ADR 0014: The claim lifecycle is a transition table

Status: accepted (plan decision D14)

## Context
Seven claim states and nine actions. Which action is legal in which state must be readable by a domain reviewer and testable exhaustively.

## Decision
A single table maps (status, action) to the next status; a blank cell is an illegal transition and returns 409 `ILLEGAL_TRANSITION`. The table is documented in `docs/domain/LIFECYCLE.md`, which is the specification: "Code and tests must match it. Change this file first." `ClaimDecider` runs the checks in a fixed order (scope, access, `If-Match`, transition, preconditions, amount rules, authority; `docs/domain/LIFECYCLE.md`, "Order of checks") and the first failure wins.

## Consequences
- A test can walk every status and action and compare with the document.
- Costs: the table says what is legal, not why; preconditions such as authority limits, conflict of interest and required documents sit in separate rules that the decider applies after the table.
- Terminal states (`SETTLED`, `REJECTED`) admit nothing, including notes.

## Alternatives rejected
- **A state-machine library.** A dependency for seven states.
- **Scattered `if` statements.** Cannot be audited.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/claim/domain/Transitions.java:12` (`TABLE`), `ClaimDecider.java`.
- Tests: `TransitionsTest.gridCoversEveryStatusAndActionExactlyOnce` (`:47`), `matchesTheTableExactly` (`:56`), `terminalStatesAdmitNothing` (`:72`); `ClaimInvariantsDbTest`; PIT mutation testing of `claim.domain`, results in `docs/evidence/pit/summary.txt`.
