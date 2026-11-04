# Threat Model (STRIDE)

Scope: the local application in this repository. Each threat maps to a test or a stated gap. "Test" names what must exist once the code does.

Sensitive data: claimant identity documents, loss descriptions, injury details, financial figures.

| # | Category | Threat | Mitigation | Evidence |
|---|---|---|---|---|
| T1 | Spoofing | Forged token | RS256; only claims-service holds the private key | Test: token signed by another key → 401 |
| T2 | Spoofing | Password guessing | BCrypt | **Gap:** no rate limit or lockout. Named next step |
| T3 | Spoofing | Insights mints tokens | It only has the public key | Design (D11) |
| T4 | Tampering | Stale screen overwrites a newer decision | `If-Match` | Test: stale version → 409 |
| T5 | Tampering | Altering financial history | Trigger blocks update and delete, for every writer | Test: direct SQL update fails |
| T6 | Tampering | Forged Kafka events alter dashboards | None locally | **Gap:** plaintext broker with no ACLs. Production needs TLS and ACLs |
| T7 | Repudiation | Denying a decision | Append-only activity with actor and time | Test: each command records an activity |
| T8 | Information disclosure | Claimant reads another's claim or document | Ownership check; 404 | Test matrix |
| T9 | Information disclosure | Claimant sees internal notes or reserves | Separate DTOs (D7) | Test: response has no such fields |
| T10 | Information disclosure | Staff read other markets | Market scope on all staff and insights endpoints | Test matrix |
| T11 | Information disclosure | Claimant data in Kafka | Explicit snapshot type; schema forbids extras | Contract test |
| T12 | Information disclosure | Document read without trace | Download logged as an activity | Test |
| T13 | Information disclosure | Personal data in logs | Log ids, not bodies; the JDBC driver is set to `logServerErrorDetail=false` so a failed statement's exception carries no row values; control characters in free text are refused with 422 before they can reach a statement | `ClaimantApiTest` (control characters, driver detail) |
| T14 | Denial of service | Oversized upload | 10 MB limit in nginx and the API | Test |
| T15 | Denial of service | Poison event stops the projection | Retry, then DLT | Test |
| T16 | Denial of service | Dashboard failure stops claims | Separate services | Fault run |
| T17 | Elevation of privilege | Claimant calls staff endpoint | Role rule on each path | Test matrix |
| T18 | Elevation of privilege | Officer approves above limit | Domain rule | Test |
| T19 | Elevation of privilege | Staff handle their own claim | Conflict check on commands and on staff reads (detail, financials, documents list) | Test. **Gap:** see "Staff member with a separate claimant login" below |
| T20 | Elevation of privilege | Malicious upload runs in a browser | Magic-byte check, generated storage key, `attachment`, `nosniff` | Test. **Gap:** no malware scanning |
| T21 | Information disclosure | Token theft from browser storage | Short expiry; no third-party scripts, enforced by the gateway's `Content-Security-Policy` (`script-src 'self'`, `frame-ancestors 'none'`; `style-src` allows inline because Angular injects component styles) and checked by `web/e2e/csp.spec.ts` | **Gap:** token is held in browser memory or storage, not an HttpOnly cookie. Stated shortcut |

## Other stated gaps

- **No per-claimant limit.** One token can create claims without bound and fill the document volume at 500 MB per claim (50 documents of 10 MB), which also inflates exposure figures. This is the same class as T2 (no rate limit), and a named next step. An nginx rate limit would also interfere with the fault and fuzz workloads, so it isn't added. The 50-document cap itself is now exact: uploads lock the claim row, so parallel uploads can't pass it (`DocumentConcurrencyTest`).
- **Tokens have no audience.** The claims service signs tokens and both services accept any valid one from the shared key set. A token for one service is valid at the other. There is no escalation today, because both honour the same user's role and markets and no service holds a different authority. A third service with different rules would need an `aud` claim checked on each side.
- **The outbox is never purged.** `outbox_event` keeps every row after publishing it, so it grows without bound. Stated. A retention job (delete published rows older than the topic's retention) is needed before production volume.
- **The outbox poller holds a database connection while it waits for the broker.** During a broker outage a publish can block for up to the producer's `delivery.timeout.ms` (about 5 s here, 10 s worst case with the poller's own timeouts). The Hikari pool (default 10) absorbs one poller; request threads are not starved. Stated, not changed.
- A staff member whose `user_market` row for a market is removed has no approval authority there at once: authority is read from the database on each command, and a missing row is no authority, while a row with a null limit stays unlimited for a manager (`AuthorityCheck`, `StaffApiClaimsTest`). Reads and non-financial commands still follow the token's markets until it expires (the stale-token row below).

- Local credentials are in the repo, for development only.
- Automated scans (dependency, image, dynamic) were removed by the candidate's decision.
- No encryption at rest or in transit locally. Data residency isn't addressed.
- Role and markets are trusted from the token for up to one hour (`app.jwt.ttl`). A user whose role or markets change, or who is removed, keeps the old access until the token expires. There is no revocation list. Authority limits are not in the token: they are read from `user_market` on each command, so a lowered limit takes effect at once.
- Append-only ledger and timeline depend on the application's database role not owning the tables in production. The triggers on `financial_entry` and `claim_activity` (update, delete, truncate) stop the application, but the table owner can drop or disable a trigger. Production must run the service as a non-owner role, with migrations run by a separate owner role. The local compose file uses one role for both. Stated shortcut.
- Deployment rule: upgrade consumers before producers. A consumer that meets an event with a newer schema version than it supports sends it to the dead-letter topic (`claims.events.v1.DLT`), and nothing replays it automatically. Deploying a producer first therefore parks events in the DLT until someone replays them by hand after the consumer is upgraded. Stated trade-off: automatic replay could loop on a poison message.
- The claimant's ETag (the claim version) reveals only that a change happened, not what changed or who changed it. Staff-only changes (notes, reassignment) bump the version too, so a claimant can see that something happened without seeing what.
- The default profile has no user bootstrap: the seeded accounts live in `db/demo` and are loaded only by the demo profile (`application-demo.yml` adds that Flyway location). A deployment on the default profile starts with no users and needs its own provisioning.
- The demo script (`db/demo/V1000__demo_users_and_policies.sql`, known password for every account) ships in the jar, so it is on the classpath of every build. Only the Flyway location decides whether it runs. Production must never set the demo profile or a `spring.flyway.locations` override that names `db/demo`; `DemoLocationsConfigTest` fails if the base configuration does.

- Two narrow failure windows are accepted. If a commit reports failure after the database did commit, upload cleanup could delete a file its record still points to, and later downloads would return 5xx. If a commit fails after a download opens its stream, that file handle leaks until the stream is collected.

## Staff member with a separate claimant login

The conflict-of-interest check compares the caller's user id with the claim's policyholder id. A staff member who also holds a claimant account under another login (another email, so another user id) is two unrelated users to the system. The check cannot see that these are the same person, so that staff member can handle a claim they hold under the claimant login. The two logins aren't linked in the data model. Closing this needs an identity link (a shared person id, or a staff register matched on a verified identifier), which is out of scope. The demo has one staff-held policy (`officer2.sg`, policy `MOT-SG-100005`) that exercises the same-account case only.
