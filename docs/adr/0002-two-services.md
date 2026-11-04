# ADR 0002: Two services, claims and insights

Status: accepted (plan decision D2)

## Context
One part of the system decides: validates, applies rules, records money, and must be consistent. Another part reacts: dashboards for exposure, workload and performance, which may lag by seconds. A dashboard failure must not stop a claimant reporting a claim.

## Decision
- `claims-service` owns the claim lifecycle, authentication, documents and the ledger.
- `insights-service` consumes events from Kafka into its own read model and serves `/api/v1/insights/*`.
- They share no database and no library. The only call between them is insights fetching public signing keys (see ADR 0011).

## Consequences
- Insights can be stopped and claims keeps working (fault run `docs/evidence/faults/insights-down.txt`). The manager dashboards return 502 or 504 while it is down, and the UI says so.
- Costs: two deployables, eventual consistency between them (dashboards carry `asOf`), a reconciliation script (`scripts/reconcile.sh`) to show the two agree, and duplicated boilerplate (error handling, security config).
- ArchUnit rules keep each service's internal layers honest: `services/claims-service/src/test/java/com/chubb/claims/arch/ArchitectureTest.java:17`, `services/insights-service/src/test/java/com/chubb/insights/ArchitectureTest.java`.
- The fault run changed one claim in the plan: while nginx holds an old address, dashboards answer 504 for the first seconds, not only 502. `proxy_connect_timeout 2s` bounds it.

## Alternatives rejected
- **One service.** Kafka would become decorative and "a dashboard failure cannot stop claims" would be a promise, not a boundary.
- **One service per concept** (documents, identity, ledger...). Nothing here needs those parts to scale or fail separately.

## Where it is in the code
- `pom.xml:14-15`; `docker-compose.yml` services `claims-service` and `insights-service`; `scripts/fault-insights-down.sh`.
- Change after the plan: event type `ClaimUpdated` was added for staff notes, which bump the claim version (`services/claims-service/src/main/java/com/chubb/claims/outbox/ClaimEventType.java`). The plan listed seven event types; the code has eight.
