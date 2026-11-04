# ADR 0003: Transactional outbox with a poller

Status: accepted (plan decision D3)

## Context
Saving a claim and publishing its event cannot be one atomic step across Postgres and Kafka. A crash between the two would either lose the event or announce a claim that was never saved.

## Decision
Every command writes an `outbox_event` row in the same database transaction as the claim, ledger and activity rows. `OutboxEventRecorder` is `@Transactional(propagation = MANDATORY)`, so recording outside a transaction is an error. A poller reads unpublished rows in id order, sends them with `acks=all` and an idempotent producer, waits for the broker acknowledgement, then sets `published_at`.

## Consequences
- A Kafka outage never fails a claim request. Rows accumulate and drain in order on recovery (`docs/evidence/faults/kafka-down.txt`).
- Delivery is at least once: a crash between the broker ack and the `published_at` update re-sends the row. Consumers must tolerate duplicates (ADR 0004).
- Costs: up to the poll interval (500 ms) of added latency, and a table that is never purged (a stated gap in `docs/THREAT_MODEL.md`). The poller holds a database connection while waiting for the broker.
- Related change: the poller now runs under a single-publisher lock; see ADR 0018.

## Alternatives rejected
- **Publish after commit.** A crash after the commit loses the event with no trace.
- **Debezium (change data capture).** Correct, but adds a Kafka Connect cluster to a local stack that should start with one command.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/outbox/OutboxEventRecorder.java:34` (MANDATORY propagation), `OutboxPoller.java:38`; call site `claim/ClaimSubmissionTx.java:122`.
- `services/claims-service/src/main/resources/application.yml:36-38` (`acks: all`, `enable.idempotence: true`).
- Tests: `OutboxPublishingTest.rollbackPublishesNothingAndLeavesNoOutboxRow`, `OutboxDeliveryTest`; fault `scripts/fault-poller-ack.sh`.
