# ADR 0008: Append-only reserve ledger with a provisional reserve from submission

Status: accepted (plan decision D8)

## Context
Reserve changes need an audit trail, restatement and an explanation of why exposure moved. A claim reported but not yet assessed is real exposure.

## Decision
Money lives in `financial_entry`, an append-only table: rows are inserted, never updated or deleted. A trigger blocks update, delete and truncate for every writer. The claim gets a provisional reserve at submission (`ProvisionalReserve`), replaced by the assessed amount and then the approved amount.

## Consequences
- Every exposure figure can be rebuilt from the ledger, which is how `scripts/reconcile.sh` checks the projection.
- New claims appear in exposure straight away, marked provisional.
- Costs: reading the current reserve means taking the latest entry. The trigger stops the application but not the table owner; production must run the service as a non-owner role (stated in `docs/THREAT_MODEL.md`; the local compose file uses one role).
- Changed during the work: ledger rows now carry the currency of their claim (migration `V7__integrity_constraints.sql`, checked by `MigrationTest.ledgerRowMustCarryTheCurrencyOfItsClaim`). V7 disables and re-enables the trigger to backfill it. Migrations were edited in place, so an old volume must be reset with `docker compose down -v`.

## Alternatives rejected
- **A reserve field only.** No history.
- **Reserve at assessment only.** Understates exposure for everything not yet assessed.

## Where it is in the code
- `services/claims-service/src/main/resources/db/migration/V2__domain_schema.sql:135` (`financial_entry_append_only`); `claim/FinancialEntry.java`; `claim/domain/ProvisionalReserve.java`.
- Tests: `MigrationTest.financialEntryRefusesUpdate` (`:195`) and `financialEntryRefusesDelete` (`:203`); `MigrationUpgradeTest.java:72`.
