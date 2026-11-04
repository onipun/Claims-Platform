# ADR 0007: Separate claimant and staff controllers and DTOs

Status: accepted (plan decision D7)

## Context
Staff see internal notes, reserves, authority limits and officer ids. Claimants must never see them. A single type with fields hidden by role means one missed condition leaks data.

## Decision
Claimant and staff have separate controllers, services, mappers and response types. The claimant type does not have the sensitive fields at all.

## Consequences
- A leak needs a deliberate new field on the claimant type, which review and tests catch. There is no filtering to get wrong.
- Costs: more classes, and similar fields declared twice (`ClaimantClaimDetail`, `StaffClaimDetail`). Both appear in the OpenAPI contract and the generated TypeScript types.

## Alternatives rejected
- **One DTO with role-filtered JSON.** One forgotten condition leaks a note.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/claim/api/ClaimantController.java`, `StaffController.java`, `ClaimantClaimDetail.java:16` (comment listing the fields it lacks), `StaffClaimDetail.java`.
- Tests: `AuthorizationMatrixTest`, `StaffAuthorizationMatrixTest`, `ClaimantApiTest`.
