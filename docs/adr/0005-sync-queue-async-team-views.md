# ADR 0005: Synchronous REST for an officer's own work, read model for team views

Status: accepted (plan decision D5)

## Context
An officer who picks up a claim must see it in "mine" at once. A manager's team exposure total can be a few seconds old if it says so.

## Decision
The staff queue, claim workspace and every command are served by `claims-service` straight from its database. Team exposure, workload and performance come from `insights-service` and always return `asOf`. The exposure response names its basis: `CASE_RESERVES_EXCL_IBNR`.

## Consequences
- Pick-up, assess, approve and the like are immediately visible to the person who did them.
- Dashboard figures are eventually consistent. The UI shows `asOf` and polls (ADR 0013). `scripts/reconcile.sh` compares ledger, projection and API figures from outside both services.
- Cost: the same claim count can briefly differ between the officer queue and the manager dashboard.

## Alternatives rejected
- **Everything from the read model.** A pick-up could appear to be missing.

## Where it is in the code
- `web/src/app/features/staff/queue.page.ts:170` (queue reloads from claims) and `insights.page.ts:352` (dashboard polling insights).
- `services/insights-service/src/main/java/com/chubb/insights/api/InsightsController.java`, `InsightsDtos.java` (`asOf`).
- `web/nginx/default.conf:31` routes `/api/v1/insights/` to insights; `:40` sends the rest of `/api/` to claims.
