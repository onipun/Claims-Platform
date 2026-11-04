# ADR 0018: Compacted event topic, with a single-publisher lock

Status: accepted (plan decision D18; the lock was added during the review rounds)

## Context
Events are full snapshots, so the latest event per claim is enough to rebuild the read model. Time-based retention would drop events for a claim that stays open longer than the retention period.

## Decision
`claims.events.v1` (3 partitions, key = claim id) uses `cleanup.policy=compact`. Compaction keeps the last record per key, so the last record must be the newest. To guarantee that, the outbox poller takes a transaction-scoped advisory lock (`pg_try_advisory_xact_lock`) at the start of each poll and returns if another poller holds it. Without it, a second poller could skip a row a first poller still held, send the newer version, and commit first, leaving `[v1, v0]`; compaction would then keep stale v0. The dead-letter topic `claims.events.v1.DLT` keeps messages 7 days.

## Consequences
- Insights can rebuild its read model by replaying the topic from the start; growth is bounded by the number of claims.
- Costs: only one poller publishes at a time, which limits throughput to a single publisher (fine here, a ceiling at scale); history lives in the ledger, not the topic; compaction is not instant, so the topic can briefly hold several versions of one claim.
- Changed during the work: the first poller skipped rows it could not lock, which allows the `[v1, v0]` order above. The advisory lock replaced that; the review notes in `docs/reviews/round-2-delta.md:27` mention the lock, and `OutboxDeliveryTest` stages the failure exactly. The plan's D18 row already describes the final design.

## Alternatives rejected
- **Time-based retention.** A claim open longer than retention could not be rebuilt.
- **Infinite retention.** Stores history the ledger already owns.

## Where it is in the code
- `docker-compose.yml:77-82` (`cleanup.policy=compact`; DLT `retention.ms=604800000`) and `:78` (3 partitions).
- `services/claims-service/src/main/java/com/chubb/claims/outbox/OutboxStore.java:36` (`pg_try_advisory_xact_lock`); `OutboxPoller.java:25` (Javadoc on the lock).
- Tests: `OutboxDeliveryTest.aSecondPollerPublishesNothingWhileTheFirstHoldsTheLockSoTheTopicStaysInOrder` (`:258`), `twoConcurrentPollersPublishEveryRowInOrder` (`:189`).
