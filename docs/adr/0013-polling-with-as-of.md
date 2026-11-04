# ADR 0013: Polling with "as of"

Status: accepted (plan decision D13)

## Context
Staff queues and dashboards change while a person watches. The dashboard data is eventually consistent, and the screen should say how fresh it is.

## Decision
The browser polls. The insights page reloads every 15 s, the staff queue every 30 s, and the claimant's unread-updates indicator on its own timer. Polling pauses while the tab is hidden and stops when the owning page is destroyed (`poll()` helper). Insights responses carry `asOf` and the UI shows it.

## Consequences
- Simple, works through the nginx gateway without special configuration, and a stale screen is visibly stale.
- Costs: updates can lag by one interval, and each open tab makes a request every interval. Changes become visible only on the next poll.
- Next step named in the plan: Server-Sent Events or WebSocket for the manager view.

## Alternatives rejected
- **SSE or WebSocket.** More moving parts (proxy timeouts, reconnect, auth on a long-lived connection) than the freshness here needs.

## Where it is in the code
- `web/src/app/core/util/poll.ts:16`; `web/src/app/features/staff/insights.page.ts:352`, `queue.page.ts:170`, `features/claimant/unread.service.ts:109`.
- `asOf` in `services/insights-service/src/main/java/com/chubb/insights/api/InsightsDtos.java`.
