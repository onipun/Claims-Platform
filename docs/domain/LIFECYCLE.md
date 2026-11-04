# Claim Lifecycle: Rules of Record

This is the specification for the domain rules. Code and tests must match it. Change this file first.

## Transition table

A blank cell means the action is illegal in that state and returns 409 `ILLEGAL_TRANSITION`.

| State | PICK_UP | ASSIGN | ADD_NOTE | REQUEST_INFO | ANSWER_INFO | ASSESS | APPROVE | REJECT | SETTLE |
|---|---|---|---|---|---|---|---|---|---|
| `SUBMITTED` | `UNDER_REVIEW` | `UNDER_REVIEW` | | | | | | | |
| `UNDER_REVIEW` | | same | same | `AWAITING_INFO` | | `ASSESSED` | | `REJECTED` | |
| `AWAITING_INFO` | | same | same | | `UNDER_REVIEW` | | | | |
| `ASSESSED` | | same | same | `AWAITING_INFO` | | `ASSESSED` | `APPROVED` | `REJECTED` | |
| `APPROVED` | | same | same | | | | | | `SETTLED` |
| `SETTLED` | | | | | | | | | |
| `REJECTED` | | | | | | | | | |

Document review (accept or re-request) follows the ADD_NOTE row: on `SETTLED` and `REJECTED` the cell is blank, so it returns 409 `ILLEGAL_TRANSITION`, after the same access checks (steps 1 and 2).

`same` means the state doesn't change, but the claim row is still updated, so its version increases.

Answering from `AWAITING_INFO` always returns to `UNDER_REVIEW`, even if the request was made from `ASSESSED`. New information means the assessment has to be looked at again.

## Order of checks

Every command runs these steps in this order. The first failure wins.

1. Scope lookup: the claim must be visible to the caller's markets (or, for the claimant, owned by them), otherwise 404.
2. Access: the actor may touch this claim, otherwise 404 (no role or market access, or not the assigned officer or a manager) or 403 `CONFLICT_OF_INTEREST` (the actor is the claim's policyholder).
3. `If-Match`: missing is 428 `PRECONDITION_REQUIRED`, stale is 409 `STALE_VERSION`.
4. Transition table: a blank cell is 409 `ILLEGAL_TRANSITION`. So PICK_UP on an `UNDER_REVIEW` claim, ANSWER_INFO on `UNDER_REVIEW`, and ASSIGN with a bad target on `SETTLED` all give `ILLEGAL_TRANSITION`.
5. Preconditions of the action: the ASSIGN target must be a staff member of the claim's market (422 `INVALID_ASSIGNEE`) and not the policyholder (403 `CONFLICT_OF_INTEREST`).
6. Amount rules (422 `INVALID_AMOUNT`) and required text (422 `VALIDATION_FAILED`).
7. Authority (403 `AUTHORITY_EXCEEDED`).

Because step 2 comes before step 3, an unassigned officer who posts a note without `If-Match` gets the access result (404), not 428.

Codes that cannot be reached were removed: a claim in `SUBMITTED` never has an officer (so PICK_UP never meets an assigned claim), and `AWAITING_INFO` holds exactly when a request is open (so ANSWER_INFO never meets a missing request).

## Who may act (step 2 and 5)

| Action | Actor | Further conditions |
|---|---|---|
| PICK_UP | `OFFICER` or `MANAGER` with the claim's market | |
| ASSIGN | `MANAGER` with the claim's market | Target is an `OFFICER` or `MANAGER` with that market, and not the claim's policyholder |
| ADD_NOTE, REQUEST_INFO, ASSESS, REJECT, SETTLE | The assigned officer, or a `MANAGER` with the market | |
| APPROVE | Same | Amount within the actor's authority for the claim's market, otherwise 403 `AUTHORITY_EXCEEDED` |
| ANSWER_INFO | The `CLAIMANT` who owns the claim | |
| Any staff action or staff read of the claim | | Actor isn't the claim's policyholder, otherwise 403 `CONFLICT_OF_INTEREST` |

Staff reads of a claim (detail, financials, documents list) apply the same conflict check: the policyholder gets 403 `CONFLICT_OF_INTEREST`.

Authority limits are stored per user and market (`user_market.authority_limit`), in that market's currency. A `MANAGER` limit of null is unlimited in that market. An `OFFICER` with a null limit has no authority.

## Amount rules

| Action | Rule | Failure |
|---|---|---|
| Submit | `estimate >= 0` | 422 `INVALID_AMOUNT` |
| ASSESS | `0 <= reserve <= coverage limit` | 422 `INVALID_AMOUNT` |
| APPROVE | `0 < amount <= coverage limit` | 422 `INVALID_AMOUNT` |
| SETTLE | Paid amount equals the approved amount; partial payments are out of scope | 422 `INVALID_AMOUNT` |

Amounts must fit the currency's minor units: IDR 0 decimal places in this system, all others 2.

## Ledger effects

| Event | Entry | Reserve status |
|---|---|---|
| Submitted | `RESERVE_SET min(max(estimate − deductible, 0), limit)` | `PROVISIONAL` |
| Assessed | `RESERVE_SET` officer's figure | `ASSESSED` |
| Approved | `RESERVE_SET` approved amount | `ASSESSED` |
| Settled | `PAYMENT` approved amount | `ASSESSED` |
| Rejected | `RESERVE_SET 0` | `ASSESSED` |

- `reserve` is the latest `RESERVE_SET` amount.
- `paid` is the sum of `PAYMENT` amounts.
- `case outstanding` is `reserve − paid`.
- `incurred` is `paid + case outstanding`.

## Reject reason codes

`NOT_COVERED`, `POLICY_NOT_IN_FORCE`, `EXCLUDED_CAUSE`, `INSUFFICIENT_EVIDENCE`, `BELOW_DEDUCTIBLE`, `OTHER`. Each comes with a claimant-visible explanation.

## Submission rules

- The policy belongs to the claimant and its line matches the claim.
- The incident is within the policy's effective dates, in the market's time zone, and isn't in the future.
- The claim type is valid for the line.
  - `MOTOR`: `OWN_DAMAGE`, `THIRD_PARTY_DAMAGE`, `TOTAL_LOSS`, `WINDSCREEN`, `THEFT`.
  - `PROPERTY`: `FIRE`, `WATER_DAMAGE`, `STORM`, `THEFT`, `OTHER`.
- Motor claims require vehicle registration, driver name and the accident location (`motor.location`, at most 200 characters; error field `lossDetails.location`). The location is stored in the claim's loss details and shown to the claimant and to staff. It isn't in the Kafka event, because the insights projection has no use for it. Claims stored before this rule have no location and still read normally.
- Property claims require the loss address and cause.

## Documents

- Uploads and reviews lock the claim row (`SELECT ... FOR UPDATE`), so document writes for one claim run one at a time and in order with claim commands. The status check and the 50-document cap see committed state: a document never lands on a claim that has just settled, and the cap holds under parallel uploads.
- A document is reviewed once. A review of a document that is no longer `UPLOADED` returns 409 `DOCUMENT_NOT_REVIEWABLE` (the loser of two concurrent reviews gets this). A new upload makes a fresh `UPLOADED` document. `ClaimDocument` also carries a `@Version`; a lost optimistic update returns 409 `STALE_VERSION`.
- A claimant can't upload to a closed claim (`SETTLED` or `REJECTED`): `409` with code `CLAIM_CLOSED`, as `DocumentService.upload` does. Tests: `DocumentApiTest.noUploadOnTerminalClaim` (REJECTED) and `noUploadOnSettledClaim`.

## Late reporting

Only markets with a sourced rule produce a flag.

| Market | Line | Rule | Source |
|---|---|---|---|
| SG | MOTOR | Due by the end of the next working day after the incident, in `Asia/Singapore`. Saturday and Sunday aren't working days | [GIA](https://gia.org.sg/motor-insurance/22-premium-renewal-of-policy/348-motor-claims-framewor) |

- A late claim is flagged, with the claimant's stated reason if given. It's never rejected automatically, because GIA excuses a valid reason.
- GIA's own example must pass: a Saturday-night accident is due Monday.
- Public holidays aren't modelled. This is a stated shortcut, and it can only ever flag a claim as late wrongly, never miss one.
- GIA also gives "within 24 hours" for reporting and taking the vehicle in. The next-working-day wording is used as the outer limit.

## Service levels

Placeholder configuration, not sourced: pick up within 24 hours, decide within 14 days of submission. The clock doesn't pause while waiting on the claimant. That's a stated shortcut, and those claims are shown as waiting on the claimant.

## Invariants

A seeded random-sequence test on the real `Claim` (no database) and a smaller Testcontainers test through the real services must hold these:

1. A terminal state never changes.
2. `case outstanding >= 0`.
3. `reserve <= coverage limit`.
4. The claim's reserve and paid figures equal what the ledger derives.
5. At most one open information request per claim.
6. Every status change has an activity entry.
7. Every successful command increases the version by exactly 1.
8. Every successful command writes exactly one outbox event, carrying the new version.
