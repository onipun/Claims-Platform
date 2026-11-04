# ADR 0016: Market rules as data, each with a source

Status: accepted (plan decision D16)

## Context
Claims rules differ by market and line of business: reporting deadlines, required documents, authority. An invented rule looks as authoritative as a real one.

## Decision
Rules are rows, not code branches. `market_rule` has a `NOT NULL source_url`; `document_requirement` carries a `source` and a `placeholder` flag, so a requirement that is a stand-in says so. Rules with no public source are marked as placeholders. `docs/domain/SOURCES.md` lists the references and `docs/domain/TRACEABILITY.md` maps each source item to Built, Partial or Out of scope.

## Consequences
- Adding a market is a data migration plus tests, not a new code path.
- The only market rule that changes behaviour today is the Singapore motor reporting deadline (end of next working day, `LateReportRule`).
- Costs: parameters are free-form JSON, validated in code; the document checklist and SLAs for most markets are placeholders. Indicative SLAs, limits and USD rates are not company policy.
- Changed during the work: authority limits moved onto `user_market` (`V6__authority_limit_per_market.sql`), so a person can have different limits per market and a removed row means no authority.

## Alternatives rejected
- **A hard-coded rule per market.** Invented rules would look authoritative.

## Where it is in the code
- `V2__domain_schema.sql:17` (`source_url text NOT NULL`), `V3__reference_data.sql:16` (`market_rule`) and `:25` (`document_requirement`), `V6__authority_limit_per_market.sql:5`.
- `claim/domain/LateReportRule.java`, `AuthorityCheck.java`. Tests: `LateReportRuleTest`, `StaffApiClaimsTest`.
