# Architecture decision records

One record per decision in the design plan (D1 to D18). Each states the context, the decision, what it costs, the alternatives rejected, and where it exists in the code (`file:line` or a test name). Where a decision changed during the work, the record says what changed.

| ADR | Decision | Plan ID |
|---|---|---|
| [0001](0001-java-21-spring-boot.md) | Java 21 and Spring Boot | D1 |
| [0002](0002-two-services.md) | Two services: claims decides, insights reacts | D2 |
| [0003](0003-transactional-outbox.md) | Transactional outbox with a poller | D3 |
| [0004](0004-event-snapshot-and-version.md) | Events carry a snapshot and a version | D4 |
| [0005](0005-sync-queue-async-team-views.md) | REST for an officer's own work, read model for team views | D5 |
| [0006](0006-optimistic-locking-if-match.md) | Optimistic locking with `If-Match` | D6 |
| [0007](0007-separate-claimant-and-staff-dtos.md) | Separate claimant and staff controllers and DTOs | D7 |
| [0008](0008-append-only-reserve-ledger.md) | Append-only reserve ledger, provisional reserve from submission | D8 |
| [0009](0009-currency-per-market.md) | One currency per market, USD only indicative | D9 |
| [0010](0010-contracts-code-first.md) | Code-first OpenAPI, generated TS types, JSON Schema for events | D10 |
| [0011](0011-local-rs256-jwt-and-jwks.md) | Local RS256 JWT, insights verifies through JWKS | D11 |
| [0012](0012-postgres-database-per-service.md) | PostgreSQL, one database per service | D12 |
| [0013](0013-polling-with-as-of.md) | Polling with "as of" | D13 |
| [0014](0014-transition-table.md) | Lifecycle as a transition table | D14 |
| [0015](0015-documents-on-volume-behind-interface.md) | Documents on a volume behind `DocumentStore` | D15 |
| [0016](0016-market-rules-as-data.md) | Market rules as data, each with a source | D16 |
| [0017](0017-fleetros-tokens-angular-tailwind-cdk.md) | FleetROS tokens in Angular, Tailwind and CDK | D17 |
| [0018](0018-compacted-event-topic.md) | Compacted event topic, single-publisher lock | D18 |

Related: [threat model](../THREAT_MODEL.md), [claim lifecycle](../domain/LIFECYCLE.md), [review notes](../reviews/).
