# ADR 0011: Locally issued RS256 JWT, verified by insights through JWKS

Status: accepted (plan decision D11)

## Context
Both services need to authenticate staff. Insights must be able to verify a token without being able to create one. A real deployment would use an identity provider; this repository has to start with one command.

## Decision
`claims-service` authenticates users (BCrypt) and signs RS256 tokens with a private key. It publishes the public key at `/api/v1/.well-known/jwks.json`. `insights-service` verifies tokens against that JWKS URI and holds no private key. The private key lives in a directory on the `claims-data` volume, so tokens survive a claims restart. Tokens last one hour (`app.jwt.ttl: PT1H`).

## Consequences
- A token signed by any other key is rejected, even with the same key id.
- Insights fetches keys on first use and caches them. If claims and insights restart together, insights answers 401 until claims is back (known and in the failure table of the plan).
- Costs, all stated in `docs/THREAT_MODEL.md`: no login rate limit or lockout, no revocation (role and markets are trusted for up to an hour), no audience claim, and the token is kept in browser memory or storage rather than an HttpOnly cookie.
- The first swap for production is an identity provider such as Keycloak.

## Alternatives rejected
- **Shared HMAC secret.** Insights could then mint tokens.
- **Keycloak now.** The production answer, but a heavy addition for a local-only stack.

## Where it is in the code
- `services/claims-service/src/main/java/com/chubb/claims/identity/JwtKeyStore.java:30` (key on a volume), `TokenService.java`, `AuthController.java`.
- `services/insights-service/src/main/java/com/chubb/insights/config/SecurityConfig.java:30-32` (`NimbusJwtDecoder.withJwkSetUri`).
- Tests: `LoginAndJwksTest.loginIssuesAnRs256TokenWithTheDocumentedClaims`, `jwksIsPublicAndHasNoPrivateParts`; `JwksSecurityTest.tokenSignedByAnotherKeyIs401EvenWithTheSameKeyId` (`:139`); fault `scripts/fault-jwks.sh`.
