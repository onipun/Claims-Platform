# Plan: Chubb APAC Claims Platform

**Source PRD**: `.claude/prds/chubb-claims-platform.prd.md`
**Selected milestones**: 0–6, planned together.
**Complexity**: Large.
**Status**: REVISION 4, AWAITING REVIEW. No code until approved. Revision 3 removed the observability stack, k6 and the security scans, on the candidate's decision. Revision 4 adds the whole-stack data flow (section 6.1) and the complete Docker Compose (section 9.1).

---

## 1. Summary

Two Spring Boot services and one Angular application.

- `claims-service` is the system of record. It takes every decision synchronously over REST and publishes what happened to Kafka through a transactional outbox.
- `insights-service` consumes those events into a read model and serves team workload, performance and case exposure.
- The Angular application serves claimants and staff from one codebase, using lazy role-specific route trees and a design system ported from FleetROS.

The rule for what goes where: **anything a person is waiting on, or that must be consistent to make a decision, is REST. Anything that reacts to a decision and can lag is Kafka.**

Revision 2 changes the approach in three ways: the domain comes from primary sources rather than the brief, each success claim is judged by something other than the author, and the failure paths are exercised rather than only reasoned about.

---

## 2. Raw material

Task 0 turns this table into `docs/domain/SOURCES.md`.

| Raw material | Instead of | What it changed |
|---|---|---|
| [Chubb Malaysia motor claims process](https://chubb.com/my-en/claims/how-motor-insurance-claims-work.html) | "Provide additional information" in the brief | Documents are central to the real process. **Upload moves into scope**, with a checklist per claim type. Claim types come from Chubb's own list |
| [GIA Motor Claims Framework](https://gia.org.sg/motor-insurance/22-premium-renewal-of-policy/348-motor-claims-framewor) | "Report an incident" | Time of incident and market time zone matter. Late reports are flagged, never rejected, because a valid reason excuses them |
| ACORD 2 and ACORD 1 loss notices | Invented form fields | Report form sections. **Gap: section names only so far.** Fetches were refused, so a full field list needs either a copy of the form or reduced confidence stated in the traceability table |
| [CAS reserve principles](https://www.casact.org/sites/default/files/2021-04/statement_of_principles_Loss_Loss_Adjustment%20_Expense%20_Reserves_2021.pdf), [IFRS 17 summary](https://www.cpdbox.com/ifrs-17-insurance-contracts-summary) | "Liability exposure" | The figure is **case exposure**, not total liability. Paid, case outstanding and incurred become separate. IBNR is out of scope and labelled so |
| Commit-by-commit behaviour of our own system under failure | Kafka and outbox theory | Fault injection (section 3) |
| FleetROS source: `app/globals.css`, `tailwind.config.ts`, `components/ui/*`, `components/layout/dashboard-layout.tsx` | A description of its look | Section 8 |

**Weakest link:** sources are public and from individual markets. Nothing here comes from watching a claims handler work. It's the most useful thing a real team would do, and only a person can supply it.

### Corrections to revision 1 from this material

| Revision 1 | Problem | Revision 2 |
|---|---|---|
| Provisional reserve = `min(estimate, limit)` | Ignored the deductible, so **exposure was overstated** | `min(max(estimate − deductible, 0), limit)` |
| "Liability exposure" | Implied total liability | "Case exposure", with the exclusions stated |
| Reserve as a field | Couldn't restate past figures or explain changes | Append-only ledger; the field is derived |
| Text-only information | Contradicted the real process | Documents in scope |
| Incident date | Can't compute a next-working-day deadline | Local date and time plus market time zone |

---

## 3. What a top professional would do, and what proves it

| Practice | Method and tool | Evidence, independent of author | Threshold |
|---|---|---|---|
| Learn the domain language first | Event storming, written up as a glossary and event timeline in `docs/domain/` | Traceability table against sources | Every source item mapped |
| Prove the lifecycle rather than sample it | Seeded random command sequences in plain **JUnit**, with the seed printed on failure | Invariants hold over generated sequences | 1,000+ sequences, none failing |
| Check the tests themselves | Mutation testing, **PIT**, on transition, money and authority rules | Mutants killed | ≥ 90 % on those packages |
| Prove exposure from another direction | Standalone `scripts/reconcile.sh` and its SQL, reading the ledger directly and comparing with the projection and the API | Dashboard and SQL agree | Zero difference |
| Exercise failure | **Toxiproxy**, `docker kill`, Testcontainers. Kill the poller after send but before marking; stop Kafka; stop insights; duplicate and reorder deliveries | Reconciliation after each run | Zero lost, zero double-counted |
| Keep the contract honest | **Schemathesis** against the running API; JSON Schema validation for events | Tool report | No server errors, no schema violations |
| Keep boundaries honest | **ArchUnit** | Fails the build on a boundary breach | Feature packages only through their API; web layer can't reach repositories |
| Threat-model sensitive data | STRIDE on paper | Each threat maps to a test or a stated gap | No unmapped threat |
| Separate duties | Officer can't handle a claim on their own policy; above-authority approval by a manager | Authorisation test matrix | All role × endpoint × ownership cases |
| Check accessibility | **axe-core** in Playwright | Tool report | No serious or critical |
| Check the journey | **Playwright**, deliberately simple: one spec across three roles | Pass in a real browser | Passes |
| Check it starts elsewhere | Fresh clone, separate compose project | Script output | Passes following only the README |
| Ask someone who knows | A claims practitioner or the panel | Their comments | **Open.** Reviewer agents are also AI, so they don't count as independent of "you or me" |

---

## 4. Decisions and the alternatives rejected

These become `docs/adr/` records.

| # | Decision | Why | Rejected, and why |
|---|---|---|---|
| D1 | Java 21 + Spring Boot | Java 21 and Maven are installed; `dotnet` isn't | .NET: equally acceptable, adds setup |
| D2 | Two services: claims and insights (**candidate confirmed**) | One side decides and must be consistent; the other reacts and may lag. A dashboard failure must not stop a claim being reported | **One service**: Kafka becomes decorative. **One per concept**: nothing here needs those parts to scale or fail separately |
| D3 | Transactional outbox with a poller | Saving and publishing can't be one atomic step; a crash between them silently loses an event | **Publish after commit**: loses events. **Debezium**: correct, but adds a Connect cluster |
| D4 | Events carry a snapshot plus version | Upsert only when `event.version > stored.version` handles duplicates *and* reordering with no dedupe table | **Thin events with call-back**: couples insights to claims availability. **Event sourcing**: not required; the ledger covers the finance need |
| D5 | Sync REST for staff's own queue; read model for team views | An officer must see their pick-up immediately. A team total seconds old is fine if it says so | **Everything from the read model**: pick-ups could appear missing |
| D6 | Optimistic locking with `If-Match` | Rarely contended; settles the pick-up race and stops decisions on stale screens | **Pessimistic locks**: can't span a person's open screen |
| D7 | Separate claimant and staff controllers and DTOs | A note can't leak through a type lacking that field | **Role-filtered JSON**: one missed condition leaks |
| D8 | Append-only reserve ledger; provisional reserve from submission | Audit, restatement and explanation of changes; new claims aren't invisible to exposure | **Field only**: no history. **Reserve at assessment**: understates exposure |
| D9 | One currency per market; USD labelled indicative | Summing SGD and IDR is meaningless; authority limits need no FX | **Normalise to USD**: FX in the decision path |
| D10 | Code-first OpenAPI, committed; TS types generated; JSON Schema for events | Backend comes first and owns the contract; a test catches drift; no shared Java library | **Spec-first**: friction. **Avro + registry**: right at scale, one more moving part |
| D11 | Locally issued RS256 JWT; insights verifies through JWKS | Insights can verify but can't mint | **Shared HMAC**: insights could mint. **Keycloak**: the production answer, named as the first swap |
| D12 | PostgreSQL, one database per service, one local container | Each owns its data | **Shared database**: one system with extra steps |
| D13 | Polling with "as of" | Simple, honest about eventual consistency | **SSE/WebSocket**: next step for the manager view |
| D14 | Transition table | Exhaustively testable and readable | **Library**: dependency for seven states. **Scattered `if`s**: can't be audited |
| D15 | Documents on a volume behind a `DocumentStore` interface; streamed through the API after an access check | Authorisation and audit stay in one place; size and magic-byte checks | **S3 + presigned URLs**: production answer, adds MinIO and bypasses the audit path. **Database BLOBs**: bloats backups |
| D16 | Market rules as data, with a source reference | A rule without a source doesn't exist | **Hard-coded rule per market**: invented rules look authoritative |
| D17 | FleetROS tokens and component shapes, in Angular with Tailwind + Angular CDK (**candidate instruction**) | One consistent, already-tested design language | **Angular Material**: a different visual language |
| D18 | Compacted event topic | Events are full snapshots, so the latest per claim is enough to rebuild the read model at any time, without unbounded growth. This only holds if the last record per key is the newest, so the poller publishes under a single-publisher advisory lock; two concurrent pollers could write `[v1, v0]` and compaction would keep the stale v0 | **Time-based retention**: a claim open longer than retention couldn't be rebuilt. **Infinite retention**: stores history the ledger already owns |

---

## 5. Domain

### Entities

| Entity | Key fields | Notes |
|---|---|---|
| `Market` | code, currency, time zone | Seeded: SG, HK, MY, TH, ID, AU (assumption) |
| `MarketRule` | market, rule type, parameters, source URL | SG next-working-day only. Public holidays not modelled; stated shortcut |
| `AppUser` | email, hash, name, role, markets, authority limit | `CLAIMANT`, `OFFICER`, `MANAGER`. Seeded |
| `Policy` | number, holder, market, line, limit, deductible, dates | Seeded, read-only |
| `Claim` | number, policy, claimant, market, line, claim type, reporting channel, local incident time, reported at, late flag and reason, loss details, estimate, status, officer, derived reserve and paid, approved amount, rejection reason, due dates, `version` | The aggregate. Limit, deductible and currency copied at submission |
| `MotorLoss` / `PropertyLoss` | Motor: vehicle reg, driver, third party, injury, police report. Property: address, cause, occupancy | Typed record per line, stored as JSONB and validated in Java. Queried only with the claim |
| `FinancialEntry` | claim, type (`RESERVE_SET`, `PAYMENT`), amount, actor, reason, time | Append-only; a database trigger blocks update and delete |
| `DocumentRequirement` | market, claim type, document type, source | Seeded from Chubb's published list, for MY. Others use it as labelled placeholder |
| `ClaimDocument` | claim, type, filename, content type, size, storage key, status, uploader | Uploaded → accepted or re-requested |
| `ClaimActivity` | claim, type, actor, visibility, message, time | Append-only timeline, also records document access |
| `InfoRequest` | claim, question, asker, answer, times | At most one open per claim |
| `OutboxEvent` | event id, claim, version, type, payload, published at | Same transaction as the claim |
| `ClaimProjection` (insights) | snapshot + version | Read model |

### Lifecycle

```
SUBMITTED ──pick up / assign──▶ UNDER_REVIEW ◀──claimant answers── AWAITING_INFO
                                 │      │  ▲                          ▲
                                 │      │  └──────request info────────┤
                                 │    assess                          │
                                 │      ▼                             │
                                 │   ASSESSED ───request info─────────┘
                                 │      │   │
                              reject  reject approve (within authority)
                                 ▼      ▼   ▼
                               REJECTED    APPROVED ──record settlement──▶ SETTLED
```

- Only the assigned officer, or a manager of that market, acts on a claim. Nobody acts on a claim on their own policy.
- Reassignment doesn't change status. `REJECTED` and `SETTLED` are terminal.
- Above authority returns `AUTHORITY_EXCEEDED`; a manager must approve.

### Case exposure

`incurred = paid + case outstanding`, and `case outstanding = latest reserve − paid` on non-terminal claims.

| Event | Ledger effect |
|---|---|
| Submitted | `RESERVE_SET min(max(estimate − deductible, 0), limit)`, `PROVISIONAL` |
| Assessed | `RESERVE_SET` officer's figure, `ASSESSED`, within limit |
| Approved | `RESERVE_SET` approved amount |
| Settled | `PAYMENT`; outstanding becomes 0 |
| Rejected | `RESERVE_SET 0` |

**Invariants tested by the random-sequence test:** terminal states never change; outstanding is never negative; reserve never exceeds limit; ledger sum equals the claim's derived figures; at most one open info request; every status change has an activity.

---

## 6. Interfaces

### claims-service (`/api/v1`)

Errors are RFC 9457 problem+json with a stable `code`. Stale `If-Match` returns 409 `STALE_VERSION`; a missing one returns 428.

| Audience | Endpoints |
|---|---|
| Public | `POST /auth/login`, `GET /.well-known/jwks.json` |
| Claimant | `GET /claimant/policies`; `POST /claimant/claims` (`Idempotency-Key`); `GET /claimant/claims`, `GET /claimant/claims/{id}`; `POST …/{id}/info-responses`; `GET`/`POST …/{id}/documents`, `GET …/documents/{docId}/content`; `GET /claimant/updates` |
| Staff | `GET /staff/claims?queue=unassigned\|mine\|all&status=&page=`; `GET /staff/claims/{id}`; `POST /staff/claims/{id}/` + `pick-up`, `assign`, `notes`, `info-requests`, `assessment`, `approval`, `rejection`, `settlement`; `GET …/documents/{docId}/content`; `POST …/documents/{docId}/review`; `GET …/{id}/financials` |

### insights-service (`/api/v1/insights`, staff, scoped to the token's markets)

`GET /exposure`, `GET /workload`, `GET /performance`, each with `asOf`. The exposure response names its basis: `"basis": "CASE_RESERVES_EXCL_IBNR"`.

### Kafka

- `claims.events.v1`, 3 partitions, keyed by claim id; `acks=all`, idempotent producer.
- Envelope: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `claimId`, `claimVersion`, `snapshot`.
- The snapshot has **no claimant PII, loss details or free text**.
- Consumer retries with backoff, then `claims.events.v1.DLT`.
- `claims.events.v1` is compacted, keeping the latest snapshot per claim. The DLT keeps messages for 7 days.

### Ports

Web `14200`, claims `18080`, insights `18081`, Postgres `15432`, Kafka `19092`, avoiding the car-rental stack. Every host port is bound to `127.0.0.1` (`"127.0.0.1:15432:5432"`), so nothing is reachable from another machine on the network; containers still reach each other by service name. Kafka's advertised EXTERNAL listener is `localhost:19092`, which fits that.

### 6.1 Data flow: the whole stack

Solid lines are HTTP, double lines are Kafka, and dotted lines are database access.

```
                            ┌───────────────────────────────┐
                            │  Browser: Angular app         │
                            │  /claimant/**   /staff/**     │
                            └───────────────┬───────────────┘
                                            │ HTTP + JWT, same origin
                            ┌───────────────▼───────────────┐
                            │  web: nginx (:14200)          │
                            │  serves the app, routes /api  │
                            └───────┬───────────────┬───────┘
                 /api/**  (all else)│               │/api/v1/insights/**
                                    │               │
             ┌──────────────────────▼───┐   ┌───────▼──────────────────┐
             │  claims-service          │   │  insights-service        │
             │  DECIDES                 │   │  REACTS                  │
             │  rules, auth, documents  │◄──┤  read model, dashboards  │
             │  outbox poller           │   └──▲───────────────────┬───┘
             └───┬───────────────────┬──┘ JWKS ║                   ┊
                 ┊ SQL               ║   (keys ║ only)             ┊ SQL
      ┌──────────▼─────────┐         ║         ║        ┌──────────▼─────────┐
      │ Postgres: claims   │         ║         ║        │ Postgres: insights │
      │ claim, ledger,     │         ║         ║        │ claim_projection   │
      │ activity, outbox   │         ║         ║        └────────────────────┘
      └────────────────────┘   ┌─────▼─────────╨─────┐
                               │ Kafka               │
      ┌────────────────────┐   │ claims.events.v1    │
      │ Volume: documents, │   │ claims.events.v1.DLT│
      │ signing keys       │   └─────────────────────┘
      └────────────────────┘
```

**Three things to note about this picture:**

- The services share no database and no library.
- Kafka only flows one way, from claims to insights.
- The only HTTP call between services is insights fetching public signing keys. Insights never asks claims for data.

### 6.2 When HTTP is in effect

HTTP carries everything a person is waiting on. The caller gets a definite answer, and on success the data is already committed.

**Example: a claimant submits a claim**

```
Browser          nginx        claims-service                        Postgres (claims)
   │ POST /api/v1/claimant/claims │                                        │
   │ Bearer JWT, Idempotency-Key  │                                        │
   ├───────────────►├────────────►│ 1 verify JWT, role = CLAIMANT          │
   │                │             │ 2 validate against policy              │
   │                │             │ 3 BEGIN ───────────────────────────────►
   │                │             │   claim                                │
   │                │             │   financial_entry (provisional reserve)│
   │                │             │   claim_activity                       │
   │                │             │   outbox_event  (published_at = null)  │
   │                │             │   COMMIT ──────────────────────────────►
   │◄───────────────┤◄────────────┤ 4 201 + claim number + ETag            │
```

The claimant has their reference number once this returns. **No Kafka is involved in that answer**, so a Kafka outage can't stop it.

| Operation | Path | Who answers | Consistency |
|---|---|---|---|
| Log in | browser → nginx → claims | claims-service | Immediate |
| Report claim, answer request, upload document | browser → nginx → claims | claims-service | Immediate |
| Claimant's claims, timeline, updates | browser → nginx → claims | claims-service | Immediate |
| Staff queue and claim workspace | browser → nginx → claims | claims-service | Immediate (D5) |
| Pick up, assess, approve, reject, settle | browser → nginx → claims with `If-Match` | claims-service | Immediate; a stale screen gets 409 |
| Download document | browser → nginx → claims (streamed after access check, logged) | claims-service | Immediate |
| Team exposure, workload, performance | browser → nginx → insights | insights-service | **Eventual**, with `asOf`: HTTP to read, but fed by Kafka |
| Signing keys | insights → claims, on first use, then cached | claims-service | Keys only |

### 6.3 When Kafka is in effect

Kafka carries the consequences of a decision. It starts **after** the HTTP response may already have gone back, and no user request waits on it.

**Example: the same claim reaching the manager's dashboard**

```
claims-service poller        Kafka             insights-service          Postgres (insights)
   │ every 500 ms               │                     │                         │
   │ 1 pg_try_advisory_xact_lock│                     │                         │
   │   (false: return), then    │                     │                         │
   │   SELECT unpublished       │                     │                         │
   │   ORDER BY id              │                     │                         │
   │ 2 send, key = claim id ───►│                     │                         │
   │ 3 wait for broker ack ◄────┤                     │                         │
   │ 4 set published_at, COMMIT │                     │                         │
   │                            │ 5 deliver ─────────►│ 6 UPSERT ... WHERE      │
   │                            │                     │   event.version >       │
   │                            │                     │   stored.version ───────►
   │                            │                     │ 7 commit offset         │
   │                                                                            │
Browser polls every 15 s:  GET /api/v1/insights/exposure  ──► reads the projection, returns asOf
```

| Crash point | What happens | Why it's safe |
|---|---|---|
| Before step 2 | Row stays unpublished; sent next poll | Nothing was lost |
| Between steps 3 and 4 | Row is sent again | Duplicate; step 6 ignores it because the version isn't newer |
| Between steps 6 and 7 | Event is redelivered | Same rule |
| Bad message at step 6 | Retried with backoff, then sent to the DLT | Its partition keeps moving |

| Event | Trigger | Effect on read model |
|---|---|---|
| `ClaimSubmitted` | Claimant reports | Counted, provisional reserve in exposure |
| `ClaimAssigned` | Pick-up, assign | Moves to officer's workload |
| `ClaimStatusChanged` | Info request, answer | Ageing; shows waiting on claimant |
| `ClaimAssessed` | Assessment | Moves provisional to assessed |
| `ClaimApproved` | Approval | Reserve becomes the approved amount |
| `ClaimRejected` | Rejection | Exposure out; approval rate updated |
| `ClaimSettled` | Settlement | Paid recorded; cycle time updated |

**Topic retention:** the topic is compacted (D18), so Kafka always holds the latest snapshot of every claim, and insights can rebuild its read model by replaying from the start.

### 6.4 One claim, start to finish

| Step | Actor | HTTP (immediate) | Kafka (follows) |
|---|---|---|---|
| 1 | Claimant reports | `POST /claimant/claims` → claim number | `ClaimSubmitted` → exposure rises |
| 2 | Claimant uploads | `POST …/documents` | None: documents don't affect these figures |
| 3 | Officer picks up | `POST …/pick-up` with `If-Match` | `ClaimAssigned` → workload |
| 4 | Officer asks | `POST …/info-requests` | `ClaimStatusChanged` |
| 5 | Claimant sees it | Polls `GET /claimant/claims/{id}` | None |
| 6 | Claimant answers | `POST …/info-responses` | `ClaimStatusChanged` |
| 7 | Officer assesses | `POST …/assessment` | `ClaimAssessed` → provisional becomes assessed |
| 8 | Manager approves | `POST …/approval` | `ClaimApproved` |
| 9 | Officer settles | `POST …/settlement` | `ClaimSettled` → exposure falls, paid rises |
| 10 | Manager checks | `GET /insights/exposure` reads the result | Nothing new |

### 6.5 When something is down

This is what the design predicts. Task 19's fault runs have to prove it.

| Down | Still works | Stops or degrades | On recovery |
|---|---|---|---|
| **insights-service** | All claimant and staff claim work; events keep flowing to Kafka | Dashboards return 502, or 504 for the first seconds while nginx holds the old address (found by fault run; `proxy_connect_timeout 2s`), and the UI says so | Resumes from its committed offset; reconciliation passes |
| **Kafka** | All claimant and staff claim work; outbox rows build up | Dashboards are readable but stale, and `asOf` shows it | Poller drains the outbox in order |
| **claims-service** | Dashboards, for tokens already issued, with keys already cached | Login and all claim work | Unpublished outbox rows are sent. Tokens survive, because the signing key is on a volume |
| **claims-service and insights restarted** | Consumption continues | Insights can't verify tokens until claims is back, because its key cache is empty: dashboards return 401 | Automatic |
| **Postgres** | Nothing that needs data | Both services. One local instance is a local-only shortcut; D12 means production can separate them | Automatic reconnect |
| **web (nginx)** | APIs on their direct ports | The application | Immediate |

---

## 7. Traps this design must survive

1. **`@Version` changes only on flush.** Build the outbox payload after `saveAndFlush`, or events carry the old version and get dropped as stale.
2. **Timeline-only changes don't bump the version.** Every staff command must touch the `Claim` row.
3. **Outbox order across transactions.** Sequence ids can commit out of order. Per-claim order holds through version serialisation, and D4 tolerates the rest. Covered by a test.
4. **Kafka sends are asynchronous.** Wait for the broker's acknowledgement before marking published.
5. **Seed data must use the domain service.** SQL-inserted claims produce no events and no ledger entries, and reconciliation would fail. Reference data via Flyway; demo claims via a `demo` profile.
6. **Object-level access.** Another claimant's claim, and other-market staff, get 404. Includes document content by id.
7. **Double submission.** Unique constraint on `(claimant, Idempotency-Key)`. The losing race returns the original.
8. **Money.** `BigDecimal` and `numeric`, sent as JSON strings. Currency scale matters: IDR and SGD differ.
9. **Time.** Incident time is local to the market. "Next working day" is worked out in that zone. A server in UTC gets the Saturday-night case wrong; the test asserts it.
10. **Upload.** Check magic bytes, not the declared type. Generate storage keys and never use client filenames in a path. Serve with `Content-Disposition: attachment` and `nosniff`.
11. **Ledger immutability.** Only a database trigger enforces it against every writer, not just the application.
12. **Snapshot drift.** A new sensitive field must not reach Kafka by accident: an explicit snapshot type, and a schema test with `additionalProperties: false`.
13. **Version compatibility.** Boot 4.1.2, springdoc 3.2.0, Angular 22, Kafka 4.4 are current. Task 1 proves they work together, with a fallback to the newest Boot line springdoc supports.
14. **FleetROS porting.** `--destructive` isn't defined in its `app/globals.css`, though buttons and badges reference it; define it here. Its orange accent with white text and its `hover:bg-accent` outline buttons need checking against contrast rules; axe decides.
15. **Liveness can't depend on Kafka.** If a Kafka outage marks `claims-service` unhealthy, Docker or an orchestrator restarts a service that was working fine, undoing the failure isolation. The health check must exclude it, and the producer needs a short blocking timeout so the poller isn't stuck.
16. **Signing key lifetime.** A key generated in memory on each start would invalidate every token when `claims-service` restarts. It's stored on the `claims-data` volume.

---

## 8. Design system

**Decision:** match FleetROS's look and UX patterns, implemented in Angular. FleetROS is React and shadcn, so tokens and class recipes port and its components don't.

| Area | Taken from FleetROS | Angular form |
|---|---|---|
| Colour, radius | `app/globals.css` HSL variables, light and dark; `tailwind.config.ts` | Copied into `web/src/styles/tokens.css` and Tailwind config |
| Type, icons | Inter; Lucide | `@fontsource/inter`, `lucide-angular` |
| Button, Badge, Card, Input, Table, Skeleton | Exact class recipes from `components/ui/*` | Directives and small standalone components in `web/src/app/shared/ui/` |
| Dialog, menu, select | Radix behaviour | Angular CDK |
| Shell | `dashboard-layout.tsx`: `w-64` sidebar with `bg-card` and `border-r`, sticky `h-16` top bar, mobile drawer, `py-10` content padding | `shell` component, nav per role |
| Status pills | `merchant-status-badge.tsx` pattern: `rounded-full`, `bg-*-100 text-*-800` | `claim-status-badge` |
| Standards | Repo's stated rules: loading skeletons, clear error states, robust validation, responsive layout, accessible contrast | Applied to every async screen |

**Not carried over:** car animations (the error page's smoking car), the car-rental name and logo, and fleet copy. Colours and layout are FleetROS's own; no Chubb logo or brand is used.

---

## 9. Repository layout

Car-rental supplies the workflow, and FleetROS the design tokens and class recipes. No backend code is reused.

```
services/claims-service/      domain, api, security, outbox, Flyway, tests
services/insights-service/    consumer, projection, api, Flyway, tests
contracts/                    openapi/*.json, events/claim-event.v1.schema.json
web/                          core/, features/claimant/, features/staff/, shared/ui/
scripts/                      smoke.sh, reconcile.sh, fault-*.sh, fresh-clone.sh, Dockerfile.test
docs/domain/                  SOURCES, glossary, event timeline, traceability
docs/                         AI_JOURNAL, THREAT_MODEL, adr/, reviews/, evidence/, REPORT, WALKTHROUGH
infra/postgres/init-databases.sql
docker-compose.yml, README.md, pom.xml (aggregator only)
```

### 9.1 Docker Compose

Task 1 commits these files as written.

**How much has been tested.** I ran the infrastructure part on this machine (2026-11-04) under a separate project name, then removed it:

| Checked | Result |
|---|---|
| `docker compose config` | Valid, on Compose v2.18.1 |
| Postgres | Each role can create tables in its own database; `insights_app` connecting to `claims` is refused |
| Kafka topics | Both created by `kafka-init`; 3 partitions, compaction on, exit code 0 |
| Kafka listeners | Inside the network on `kafka:9092`, and from the host on `localhost:19092` |
| Kafka persistence | Messages survive a container recreate |
| nginx | Starts with no upstreams: pages return 200 and APIs return 502. Routes `/api/v1/insights/**` and `/api/**` correctly |

**Not yet tested**, because the code doesn't exist: `claims-service`, `insights-service`, `web` and the `test` profile. That includes the two Spring environment settings for JWKS and consumer group, and the tool image tags. Task 1 and task 12 run them, and any correction is recorded here.

**Start and test:**

```bash
docker compose up --build -d                        # the whole application: http://localhost:14200
docker compose --profile test run --rm smoke        # full journey and reject path
docker compose --profile test run --rm reconcile    # dashboard against raw ledger
docker compose --profile test run --rm playwright   # one browser journey
docs/evidence/schemathesis/run.sh              # API against its contract, authenticated, both services (needs the stack up, host python3 and docker)
docker compose down -v                              # remove everything, including data
```

**`docker-compose.yml`**

```yaml
name: chubb-claims

x-java-service: &java-service
  restart: unless-stopped
  mem_limit: 768m
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 18
    start_period: 30s

services:
  postgres:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres_dev}
    ports:
      - "127.0.0.1:15432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./infra/postgres/init-databases.sql:/docker-entrypoint-initdb.d/init-databases.sql:ro
    healthcheck:
      # TCP plus a real query: during init the server listens on the socket only,
      # and pg_isready alone reports ready before the databases exist.
      test: ["CMD-SHELL", "psql -h 127.0.0.1 -U postgres -d insights -tAc 'select 1' && psql -h 127.0.0.1 -U postgres -d claims -tAc 'select 1'"]
      interval: 5s
      timeout: 3s
      retries: 20

  kafka:
    image: apache/kafka:4.4.0
    restart: unless-stopped
    mem_limit: 1g
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: INTERNAL://:9092,EXTERNAL://:19092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,EXTERNAL://localhost:19092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LOG_DIRS: /var/lib/kafka/data
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_HEAP_OPTS: -Xms256m -Xmx512m
    ports:
      - "127.0.0.1:19092:19092"
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null"]
      interval: 10s
      timeout: 10s
      retries: 18
      start_period: 15s

  # One-shot. Topics are created here because broker auto-creation is off.
  kafka-init:
    image: apache/kafka:4.4.0
    depends_on:
      kafka:
        condition: service_healthy
    restart: "no"
    entrypoint: ["/bin/sh", "-c"]
    command:
      - |
        set -e
        T=/opt/kafka/bin/kafka-topics.sh
        B=kafka:9092
        $$T --bootstrap-server $$B --create --if-not-exists --topic claims.events.v1 \
            --partitions 3 --replication-factor 1 \
            --config cleanup.policy=compact --config min.insync.replicas=1
        $$T --bootstrap-server $$B --create --if-not-exists --topic claims.events.v1.DLT \
            --partitions 3 --replication-factor 1 \
            --config retention.ms=604800000
        $$T --bootstrap-server $$B --describe --topic claims.events.v1

  claims-service:
    <<: *java-service
    build: ./services/claims-service
    depends_on:
      postgres:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/claims
      SPRING_DATASOURCE_USERNAME: claims_app
      SPRING_DATASOURCE_PASSWORD: claims_dev
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      APP_DOCUMENTS_DIR: /data/documents
      APP_JWT_KEY_DIR: /data/keys
      JAVA_TOOL_OPTIONS: -Xmx512m
    ports:
      - "127.0.0.1:18080:8080"
    volumes:
      - claims-data:/data

  # Deliberately no dependency on claims-service. Each must start and run
  # without the other; JWKS keys are fetched lazily on the first request.
  insights-service:
    <<: *java-service
    build: ./services/insights-service
    depends_on:
      postgres:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
    environment:
      SPRING_PROFILES_ACTIVE: demo
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/insights
      SPRING_DATASOURCE_USERNAME: insights_app
      SPRING_DATASOURCE_PASSWORD: insights_dev
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SPRING_KAFKA_CONSUMER_GROUP_ID: insights-projection
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://claims-service:8080/api/v1/.well-known/jwks.json
      JAVA_TOOL_OPTIONS: -Xmx384m
    ports:
      - "127.0.0.1:18081:8080"

  web:
    build: ./web
    restart: unless-stopped
    ports:
      - "127.0.0.1:14200:80"
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/"]
      interval: 10s
      timeout: 3s
      retries: 6

  # ---- Test tooling. Only runs with: docker compose --profile test run --rm <name> ----
  smoke:
    profiles: [test]
    build:
      context: ./scripts
      dockerfile: Dockerfile.test
    environment:
      WEB_URL: http://web
      CLAIMS_DB_URL: postgresql://claims_app:claims_dev@postgres:5432/claims
      INSIGHTS_DB_URL: postgresql://insights_app:insights_dev@postgres:5432/insights
    volumes:
      - ./scripts:/scripts:ro
    entrypoint: ["bash", "/scripts/smoke.sh"]

  reconcile:
    profiles: [test]
    build:
      context: ./scripts
      dockerfile: Dockerfile.test
    environment:
      CLAIMS_DB_URL: postgresql://claims_app:claims_dev@postgres:5432/claims
      INSIGHTS_DB_URL: postgresql://insights_app:insights_dev@postgres:5432/insights
      WEB_URL: http://web
    volumes:
      - ./scripts:/scripts:ro
    entrypoint: ["bash", "/scripts/reconcile.sh"]

  playwright:
    profiles: [test]
    image: mcr.microsoft.com/playwright:v1.64.1-noble
    working_dir: /e2e
    environment:
      BASE_URL: http://web
    volumes:
      - ./web/e2e:/e2e
    entrypoint: ["sh", "-c", "npm ci && npx playwright test"]

volumes:
  postgres-data:
  kafka-data:
  claims-data:
```

**`infra/postgres/init-databases.sql`**

```sql
-- Runs once, on first start of an empty data volume.
CREATE ROLE claims_app LOGIN PASSWORD 'claims_dev';
CREATE ROLE insights_app LOGIN PASSWORD 'insights_dev';
CREATE DATABASE claims OWNER claims_app;
CREATE DATABASE insights OWNER insights_app;
REVOKE CONNECT ON DATABASE claims FROM PUBLIC;
REVOKE CONNECT ON DATABASE insights FROM PUBLIC;
```

**`web/nginx/default.conf`**

```nginx
server {
  listen 80;
  root /usr/share/nginx/html;
  # 11m, not 10m: the API enforces the 10 MB file limit and answers with its own problem+json 413.
  # A smaller nginx limit would trip first on multipart overhead and return nginx's HTML page.
  client_max_body_size 11m;

  # Docker DNS, resolved per request. nginx must still start, and claimant
  # pages must still work, while an upstream is stopped.
  resolver 127.0.0.11 valid=5s ipv6=off;
  proxy_connect_timeout 2s;
  set $claims   http://claims-service:8080;
  set $insights http://insights-service:8080;

  location /api/v1/insights/ {
    proxy_pass $insights;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  }
  location /api/ {
    proxy_pass $claims;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  }
  location / {
    try_files $uri $uri/ /index.html;
  }
}
```

**Dockerfiles (written in Task 1 and task 12)**

| File | Shape |
|---|---|
| `services/*/Dockerfile` | Multi-stage: `maven:3.9-eclipse-temurin-21` builds, `eclipse-temurin:21-jre-alpine` runs as a non-root user. Alpine provides `wget` for the health check |
| `web/Dockerfile` | Multi-stage: `node:24-alpine` builds, `nginx:1.29-alpine` serves, copying `web/nginx/default.conf` |
| `scripts/Dockerfile.test` | `postgres:17-alpine` plus `bash curl jq`, so test scripts need nothing on the host |

**These credentials are for local development only**, and are in the repo for that reason. The README says so, and production secrets are listed as a next step.

---

## 10. Tasks

Models follow your routing table. Each agent gets one task, about 60 turns at most, and returns the command and output as evidence. The main thread keeps the decisions and reads every result.

### Milestone 0 — Domain grounding (`feature/domain-grounding`)

| # | Task | Model | Validate |
|---|---|---|---|
| 0a | `docs/domain/`: sources, glossary, event timeline, draft traceability table | main | Each glossary term and rule cites a source |
| 0b | `docs/THREAT_MODEL.md` (STRIDE), and quality thresholds | main | Each threat maps to a test or a stated gap |

### Milestone 1 — Claim lifecycle (`feature/claim-lifecycle`)

| # | Task | Model | Validate |
|---|---|---|---|
| 1 | Scaffold: aggregator, services, Dockerfiles, the compose files from section 9.1; prove version set | sonnet | `mvn verify`; `docker compose up --build -d`; every service healthy; insights starts with claims stopped |
| 2 | Flyway schema, entities, ledger trigger, seeded reference data | sonnet | Migration test; update and delete on the ledger fail |
| 3 | Transition table, domain rules, **test-first**, random-sequence invariants | main writes table and invariants; sonnet implements | Table test and property tests |
| 4 | Identity: login, RS256, JWKS, role and market rules | sonnet | No-token, wrong-role, wrong-market tests |
| 5 | Claimant API: submit with per-line validation, idempotency, late flag; list, detail, respond, updates | sonnet | Integration tests; GIA Saturday example in market time zone |
| 6 | Staff API: queue, pick-up, assign, notes, requests, assess, approve, reject, settle; authority, conflict, `If-Match` | sonnet | Concurrent pick-up has one winner; stale → 409; own-policy blocked |
| 7 | Documents: checklist, upload, review, audited download | sonnet | Wrong magic bytes rejected; other claimant's document 404; access logged |
| 8 | Outbox, poller, publish; demo seeder through the service | sonnet | Commit → event; broker down → retained then delivered |
| 9 | OpenAPI export and drift test; event schema and contract test; ArchUnit | sonnet | Each fails on a deliberate breach, then passes |

### Milestone 2 — Exposure and workload (`feature/insights`)

| # | Task | Model | Validate |
|---|---|---|---|
| 10 | Consumer, projection upsert, DLT | sonnet | Duplicate, reorder and poison-message tests |
| 11 | Exposure, workload and performance APIs, market-scoped | sonnet | Exact figures from a fixture, by currency and provisional/assessed |
| 12 | `smoke.sh`, `reconcile.sh`, `Dockerfile.test`, as compose `test` profile services | sonnet | Both pass from `docker compose down -v` and a clean start |

**Review round 1 (backend) runs here.** The frontend doesn't start until no Critical or Important remains.

### Milestone 3 — Claimant application (`feature/web-claimant`)

| # | Task | Model | Validate |
|---|---|---|---|
| 13 | Angular 22 scaffold, tokens, `shared/ui`, shell, generated types, interceptor, lazy role routes and guards, same-origin proxy | sonnet | `ng build`; guard and interceptor tests; side-by-side screenshot against FleetROS |
| 14 | Login, report form per line, my claims, detail and timeline, documents, answer request, updates | sonnet | Unit tests; browser check with screenshots |

### Milestone 4 — Staff application (`feature/web-staff`)

| # | Task | Model | Validate |
|---|---|---|---|
| 15 | Queue with urgency and overdue; workspace with actions by state and role; late flag; document review; financial history; 409 "claim changed, reload" | sonnet | Unit tests; browser check |
| 16 | Manager views: exposure with its basis and "as of", workload, performance; reassign | sonnet | Figures match `reconcile.sh` |
| 17 | Playwright: **one simple spec**, claimant → officer → manager, with axe on key screens | sonnet | `npx playwright test` |

### Milestone 5 — Independent verification (`feature/verification`)

Results go in `docs/evidence/`. A failure is a finding for the review loop, not a threshold to be relaxed. If a threshold proves wrong, I bring it to you with the numbers.

| # | Task | Model | Validate |
|---|---|---|---|
| 18 | PIT on the rules packages; extra tests where mutants survive | sonnet | ≥ 90 % |
| 19 | Fault runs with Toxiproxy and `docker kill`: poller after send before mark, Kafka down, insights down, consumer crash mid-batch. Reconcile after each | sonnet | Zero lost, zero double-counted |
| 20 | Schemathesis against the running API | sonnet | Report clean |
| 21 | Complete traceability table | main | Every source item mapped |

### Milestone 6 — Review, report, walkthrough

| # | Task | Model | Validate |
|---|---|---|---|
| 22 | Final full review and fixes (section 11), then full suite | sonnet reviewers; main triages | Clean round recorded |
| 23 | README, ADRs | sonnet drafts; main checks against code | `scripts/fresh-clone.sh` passes |
| 24 | `docs/REPORT.md`: built, cut, evidence by metric, known shortcuts. `docs/WALKTHROUGH.md`: running order, demo, "why not X?", what to read first | main | Every claim traces to a file or a run |

---

## 11. Review and fix loop

The car-rental gate, adapted. Without an issue board, the record lives in the repository: **one `docs/reviews/round-N.md` per round**, with reviewed SHA, findings by severity and what happened to each. Branches merge to `main` with `--no-ff`.

| Round | Diff | Reviewers |
|---|---|---|
| 1 — backend, full | `services/`, `contracts/` | Java, silent failures, test quality, database, security (auth and tenant isolation) |
| Fix rounds — delta | Fixes only | Code correctness, silent failures; test quality when tests change |
| Frontend, full | `web/` | TypeScript, silent failures, accessibility |
| Final — full | Whole repository | All of the above |

- Critical and Important block; Advisory is fixed or a reason is recorded.
- The main thread confirms each fix at `file:line`.
- Three consecutive fix rounds with new blocking findings: I stop and bring you the trend.
- A false positive needs your agreement.
- Full suite once, after the final clean round.

---

## 12. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| No performance figures, and no dependency or image scan results | Certain | Your decision. Stated in the report and raised in the walkthrough as next steps. Security review by agents and the authorisation tests remain |
| More generated code to defend | High | Conventional shapes; evidence files; the walkthrough pack names what to read first |
| Busy machine (23 of 30 GB in use) | Medium | Heap limits. I'll ask before stopping any of your stacks |
| Testcontainers slows the loop | Medium | Unit and MockMvc during tasks; Kafka and fault runs in `verify` or scripts |
| One market's rules presented as universal | Medium | Rules carry a source; unsourced markets show nothing |
| Porting FleetROS styles carries over its contrast issues | Medium | axe decides; adjust here, and report back to you |
| Confidential brief in a public repository | Low | PDF stays out of the repo; I check with you before any push |

---

## 13. Validation

```bash
mvn verify                                  # unit, property, integration, contract, ArchUnit
mvn -pl services/claims-service pitest:mutationCoverage
(cd web && npm test && npm run build)
docker compose up --build -d
docker compose --profile test run --rm smoke
docker compose --profile test run --rm reconcile
./scripts/fault-all.sh
docker compose --profile test run --rm playwright
docs/evidence/schemathesis/run.sh
./scripts/fresh-clone.sh
```

## 14. Acceptance

- [ ] Every PRD Success Metric has evidence in `docs/evidence/`, or is stated as unmet
- [ ] Traceability table complete against sources
- [ ] Clean final review round recorded
- [ ] Fresh clone starts and passes the smoke test
- [ ] The commit history shows milestones, with the journal updated alongside them
- [ ] Every section 4 decision has an ADR, and everything cut is listed with a reason

## 15. Questions for your review

1. **Can the repo be a private GitHub repo with Actions?** I recommend yes: it gives a clean machine that isn't this one, which is exactly the kind of outside check you asked for. Without it, the code stays local and `fresh-clone.sh` is the weaker substitute.
2. **Do you know a claims practitioner?** Thirty minutes with the glossary and lifecycle would be the only real test of domain realism. If not, it stays open and is declared in the report.
