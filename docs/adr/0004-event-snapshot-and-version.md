# ADR 0004: Events carry a snapshot and a version

Status: accepted (plan decision D4)

## Context
Kafka delivery can duplicate and, across restarts or retries, reorder events. The read model must still end up correct.

## Decision
Each event carries the full latest snapshot of the claim plus `claimVersion`. The consumer upserts only when `event.claimVersion > stored.version`. The snapshot has no claimant PII, loss details or free text; the JSON Schema sets `additionalProperties: false` at both levels, so a new field needs a deliberate schema change.

## Consequences
- Duplicates and old events are ignored by one comparison; no dedupe table.
- Any one event is enough to repair a claim's projection, which is what makes the compacted topic useful (ADR 0018).
- Costs: events are larger than deltas, and the consumer cannot tell what changed without comparing to its stored row.
- Deployment rule: upgrade consumers before producers. A consumer that sees a newer schema major version sends the event to the dead-letter topic and nothing replays it automatically (`docs/THREAT_MODEL.md`).

## Alternatives rejected
- **Thin events with a call-back to claims.** Couples insights availability to claims.
- **Event sourcing.** Not needed: the reserve ledger (ADR 0008) already covers the audit need for money.

## Where it is in the code
- `services/insights-service/src/main/java/com/chubb/insights/projection/ProjectionStore.java:26` (`WHERE excluded.version > claim_projection.version`).
- `contracts/events/claim-event.v1.schema.json:7` and `:56`; examples in `contracts/events/examples/`.
- Tests: `ClaimEventConsumerTest.duplicateChangesNothing`, `olderVersionAfterNewerChangesNothing`, `outOfOrderJourneyShuffledWithFixedSeedEndsWhereInOrderEnds`; `ClaimEventSchemaTest.anExtraSnapshotFieldFailsValidation`; `OutboxPublishingTest.snapshotCarriesNoPersonalDataNoNotesAndNoFreeText`.
