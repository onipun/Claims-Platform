# Walkthrough

A guided tour for a reviewer: about 20 minutes to run the demo and read the core code. Results and known gaps are in the [report](REPORT.md).

## Start it

```bash
docker compose down -v          # migrations were edited in place: clear any older volume
docker compose up --build -d --wait
```

Open http://127.0.0.1:14200. All accounts use the password `Password1!`.

## The demo

### 1. Claimant reports (`claimant.sg@demo.test`)

1. Report a claim on policy `MOT-SG-100001`, type "Damage to my own vehicle". Fill in the date and time, description, estimate, vehicle registration, driver and accident location.
2. Note the claim number, then upload a PNG or PDF to the first document on the checklist.

To see the late-report flag, use a date before the previous working day. The rule exists only where a source supports it, here Singapore motor: due by the end of the next working day. The form offers an optional reason, and the officer sees the flag. It never rejects the claim.

### 2. Officer works it (`officer.sg@demo.test`, limit 10,000)

1. Pick up the claim from the queue. Open it under My claims.
2. Request information. The claim now waits on the claimant.

### 3. Claimant answers (`claimant.sg@demo.test`)

Open the claim and answer. Internal notes and reserves don't appear anywhere on this side.

### 4. Officer hits their limit (`officer.sg@demo.test`)

Assess the claim with a case reserve, then approve an amount above 10,000. The server refuses, and the screen says it's above your approval authority.

### 5. Manager decides (`manager.sg@demo.test`)

1. Open Insights and note Paid for SG Motor.
2. Under All (team), open the claim, approve it, and settle it.
3. Back on Insights, Paid rises a moment later. The dashboard is a read model fed by Kafka, so it can lag, and it shows how fresh its figures are.

### 6. Claimant sees the decision (`claimant.sg@demo.test`)

The claim shows the decision and amount.

### Stop a service during the demo

```bash
docker compose stop insights-service     # the dashboard fails; claims keep working
docker compose up -d insights-service    # it catches up from its committed offset
docker compose stop kafka                # claims keep working; events wait in the outbox
docker compose up -d kafka               # the outbox drains
docker compose --profile test run --rm reconcile   # the ledger, projection and API agree
```

This is the central design claim: anything a person waits on is REST, and anything that can lag is Kafka.

## What to read first

| Order | File | Why |
|---|---|---|
| 1 | [`docs/domain/LIFECYCLE.md`](domain/LIFECYCLE.md) | The rules everything else implements |
| 2 | `services/claims-service/src/main/java/com/chubb/claims/claim/domain/` (`Transitions`, `ClaimDecider`, `ActorPermissions`, `AuthorityCheck`) | The rules as pure code, with no framework |
| 3 | `services/claims-service/src/main/java/com/chubb/claims/outbox/OutboxPoller.java` | Ordering, the single-publisher lock, publish before mark |
| 4 | `services/insights-service/src/main/java/com/chubb/insights/projection/ClaimEventConsumer.java`, `config/KafkaConsumerConfig.java` | Version-based upsert, retries, dead-letter topic |
| 5 | `scripts/reconcile.sh`, `scripts/fault-*.sh` | How the claims are checked from outside the code |
| 6 | `web/e2e/claim-journey.spec.ts` | The whole journey in one file |
| 7 | [`docs/reviews/final-round.md`](reviews/final-round.md), [`docs/AI_JOURNAL.md`](AI_JOURNAL.md) | What was found, what was challenged, who decided |

## "Why not X?"

| Why not… | Because | Cost accepted | ADR |
|---|---|---|---|
| One service? | "A dashboard failure can't stop claims" would be a promise, not a boundary | Two deployables and a lagging read model | [0002](adr/0002-two-services.md) |
| Publish to Kafka inside the request? | The database and the broker can't commit together, so a crash could lose an event or announce one that never happened | A poller, and duplicates the consumer must absorb | [0003](adr/0003-transactional-outbox.md) |
| Events carrying only changes? | A snapshot and version let duplicates and reordering be ignored, and let a compacted topic rebuild the read model | Larger events | [0004](adr/0004-event-snapshot-and-version.md) |
| Several concurrent publishers? | On a compacted topic, `[v1, v0]` keeps v0, so a rebuild would restore a stale claim | One publisher's throughput | [0018](adr/0018-compacted-event-topic.md) |
| Last write wins? | A stale screen could overwrite a newer decision | Users sometimes reload | [0006](adr/0006-optimistic-locking-if-match.md) |
| One DTO with hidden fields? | Separate types mean a reserve can't leak through a forgotten annotation | Some repetition | [0007](adr/0007-separate-claimant-and-staff-dtos.md) |
| Updating a reserve column in place? | Exposure must be restated as at a past date, and history can't be editable | More rows and more queries | [0008](adr/0008-append-only-reserve-ledger.md) |
| WebSockets? | Figures that can lag don't need them, and polling with "as of" is honest about freshness | Up to one poll's delay | [0013](adr/0013-polling-with-as-of.md) |
| Angular Material? | The candidate chose to follow the FleetROS design system | Components built on the CDK | [0017](adr/0017-fleetros-tokens-angular-tailwind-cdk.md) |

## Questions worth asking

- **What would break first in production?** No rate limits, and the unpurged outbox. Nothing here has been load tested.
- **What's the weakest evidence?** Domain realism: no practitioner has reviewed it. Then fuzzing, which rarely got past validation on claim submission.
- **What did the AI get wrong?** The journal and review records list these, including a wrong record about document review that only a live test caught, and a focus fix that caused a regression in the next round.
