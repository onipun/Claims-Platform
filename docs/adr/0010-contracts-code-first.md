# ADR 0010: Code-first OpenAPI, committed; generated TypeScript types; JSON Schema for events

Status: accepted (plan decision D10)

## Context
The backend is built first and owns the contract. The Angular app and the event consumer both need a precise description of it, and nothing should drift silently.

## Decision
- Each service generates its OpenAPI document from code; the result is committed under `contracts/openapi/`.
- A test fails if the committed file differs from what the running application produces.
- The Angular client types are generated from those files (`npm run gen:api`), and `npm run gen:api:check` fails if the committed output is stale.
- The event contract is a JSON Schema (`contracts/events/claim-event.v1.schema.json`) with one example per event type. The producer's tests validate every event against it; the consumer's test validates the same examples.

## Consequences
- A change to an endpoint or event shows up as a diff in `contracts/`, reviewable on its own.
- Schemathesis fuzzes the committed OpenAPI documents (`docs/evidence/schemathesis/run.sh`).
- Costs: after changing a controller the contract file must be regenerated and committed, and the TypeScript types regenerated. There is no shared Java library, so event classes exist once per service.

## Alternatives rejected
- **Spec-first.** Friction for a backend-led build.
- **Avro with a schema registry.** Right at scale, but one more moving part.

## Where it is in the code
- `services/claims-service/src/test/java/com/chubb/claims/OpenApiContractTest.java:26` (`committedSpecMatchesTheRunningApplication`), and the same class in insights-service.
- `web/scripts/gen-api.mjs` (reads `../contracts/openapi`); `contracts/events/claim-event.v1.schema.json:7`.
- Tests: `ClaimEventSchemaTest.thereIsOneValidExampleForEveryEventType` (`:30`), `ClaimEventConsumerTest.everyExampleIsValidAgainstTheSchema` (`:132`).
