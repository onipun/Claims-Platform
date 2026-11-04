# ADR 0009: One currency per market, USD only as an indicative label

Status: accepted (plan decision D9)

## Context
Summing SGD and IDR into one number is meaningless, and an officer's authority limit is stated in the market's own currency.

## Decision
Every claim, ledger row and policy has one currency, set by its market (SGD, HKD, MYR, THB, IDR, AUD). Authority limits and every decision use that currency, so no exchange rate is ever in the decision path. IDR amounts must be whole numbers; others have two decimals. `insights-service` can show a combined USD figure using fixed rates from its `application.yml`; the response labels it indicative.

## Consequences
- Rules, limits and reconciliation are exact in the claim's own currency.
- Costs: dashboards that span markets show per-currency totals, and the USD column is only as good as the placeholder rates. The rates in `services/insights-service/src/main/resources/application.yml` are placeholders, not market data.
- Changed during the work: `financial_entry` gained a `currency` column that must match its claim (`V7__integrity_constraints.sql:5`), and the insights `market` column became `varchar` so the market index can serve filters (`V4__market_currency_varchar.sql`).

## Alternatives rejected
- **Normalise to USD.** Puts FX into the decision path and makes limits depend on a rate.

## Where it is in the code
- `V2__domain_schema.sql:6` and `:85` (`currency varchar(3) NOT NULL`); `claim/domain/Money.java`.
- `services/insights-service/src/main/java/com/chubb/insights/config/FxProperties.java:7` ("Never used for a decision") and `application.yml:62`.
- Tests: `MigrationTest.idrAmountsMustBeWholeOnClaimAndLedger` (`:308`), `ledgerRowMustCarryTheCurrencyOfItsClaim` (`:360`), `AmountRulesTest`.
