# AI Working Journal

A running log of what was asked of the AI, and what was accepted, challenged and overridden.
Not polished. Tool: Claude Code, main thread on Opus 5.5, delegated work on Sonnet.

Entries marked **[candidate]** are the candidate's own decisions. Entries marked **[AI]** are what the AI did or proposed, and stay as proposals until the candidate accepts them.

---

## 2026-11-04 — Kick-off

**[candidate] Asked:** Run the assessment as a PRD → plan → implementation → review and fix loop → report workflow, following the process used on a previous project (car-rental). Stop at the plan for review.

**[AI] Did:**
- Read that project's workflow rules and sample PRD and plan documents, and adopted their structure and review gate. No code from it is reused.
- Skipped the PRD template's question-and-answer steps, because the brief already answers them. Unknowns are marked as assumptions in the PRD instead.
- Checked the environment: Java 21 and Maven present, `dotnet` absent, Docker available. Chose ports that avoid services already running on this machine.

**[AI] Proposed, awaiting candidate review (plan section 13):**
- Java/Spring Boot, because of the installed tools.
- Two services split on "decides" versus "reacts".
- Manager as a third role.

**[AI] Adapted from the previous workflow:**
- No issue board. Review rounds are recorded in `docs/reviews/`.
- Two full review rounds rather than one per milestone.

## 2026-11-04 — Plan review, revision 2

**[candidate] Accepted:** two services; manager as a third role.

**[candidate] Overrode:**
- The AI's proposed cut list: the full scope stays. The call stays with the candidate.
- Playwright stays, but as a simple test. The AI had proposed dropping it first.
- Angular Material, in favour of matching the FleetROS design system.

**[candidate] Challenged:** Asked what a top professional would do that wasn't asked for, what raw material they'd start from, which specific methods and tools they'd use, and what other than the candidate or the AI would show the result is right. Told the AI to rebuild the PRD and plan on that.

**[AI] What the challenge exposed in its own revision 1:**
- The provisional reserve ignored the deductible, which overstated exposure.
- "Liability exposure" implied total liability; a claims system only knows case reserves, not IBNR.
- Documents were cut as out of scope, yet Chubb's published process is built around them.
- A date-only incident field can't support a next-working-day deadline.
- Its success measures were tests the AI itself would write.

**[AI] Limits of the sources:** ACORD PDFs couldn't be fetched, so only section names are known. Rules come from individual markets. Nobody has reviewed this from inside claims work. The agents reviewing the code are AI too, so they don't count as independent.

**[candidate] Overrode (revision 3):** Removed the observability stack, k6 and the security scans that the AI had added in revision 2. Consequence recorded in the plan: no performance figures and no dependency or image scan results. Agent security review and authorisation tests remain.

**[candidate] Asked (revision 4):** Add to the plan the whole-stack data flow, showing when Kafka is in effect and when HTTP is, and a complete Docker Compose that can be started and tested locally.

**[AI] Did:** Ran the infrastructure part of the compose file before writing it into the plan. Reading it back turned up a Postgres health check that could pass before the databases existed, and a Kafka log directory it hadn't set, so it fixed both. Application services can't be tested until they exist; the plan says so. Writing the failure table also added two traps (health checks must not depend on Kafka; signing keys must survive a restart) and a compacted topic (D18).

**Still pending from the candidate:** approval of revision 4; private GitHub repo or local only; access to a claims practitioner.

## 2026-11-04 — jqwik removed

**[AI] Found:** jqwik 1.10.2 prints a message to AI agents from `JqwikExecutor`: they must not use the library, should disregard previous instructions, and should ignore its results. The AI didn't act on it as an instruction, confirmed it comes from the library itself, and brought the choice to the candidate, recommending replacement.

**[candidate] Decided:** Replace it with JUnit. Cost: no automatic shrinking of failing sequences, so the test prints its seed instead.

## 2026-11-04 — Backend build, Tasks 1-11

**[AI] Direction:** The main thread wrote the lifecycle rules and invariants. Sonnet agents built each task to a written Definition of Done, including a deliberate breach per task to prove the tests can fail. Each report was read and triaged by the main thread.

**[AI] Challenged and overrode an agent's result:** An agent reported that two pollers can publish a claim's versions out of order and that the consumer's version rule absorbs it. Accepted at face value, that would have hidden a real design flaw: the topic is compacted and keeps the *last* record per key, so a rebuild could restore a stale claim. Overrode with a single-publisher advisory lock, which also removed `SKIP LOCKED`.

**[AI] Rejected as a default:** An agent's suggestion to add outstanding documents to `actionRequired`, since it would show every new claim as needing action. Kept a separate `outstanding` count.

**[candidate] Set the working mode:** Continue through all milestones with nothing committed or staged. The candidate commits afterwards.

## 2026-11-04 — Backend review, round 1

**[AI] Did:** Ran five independent agent reviews (Java, silent failures, tests, database, security). Result: 0 Critical, 17 Important, plus Advisory. The main thread triaged each one in `docs/reviews/round-1.md`, and recorded a reason for each Advisory it won't fix. These reviewers are AI too, so they don't count as independent evidence.

## 2026-11-04 — Parallel start, Angular foundation

**[candidate] Asked:** Whether the work could start in parallel.

**[AI] Decided how:** Backend fixes stay one at a time because they share Maven output and the Docker stack, and without git there are no worktrees to separate them. Frontend agents run alongside, each in its own folder, without Maven or Docker. This relaxes the plan's "frontend waits for a clean review". The cost: screens are first checked against stubbed responses, so they need a live test once the API settles.

**[AI] Overrode the design system:** White text on FleetROS's orange accent is 2.85:1, which fails WCAG AA, so the accent foreground is near-black here. Muted on muted, at 4.34:1, was left at the FleetROS value and may be flagged by axe.

**[AI] Limit of the evidence:** The Angular foundation was compared with FleetROS by class recipes, not screenshots.

## 2026-11-04 — Screens built, frontend review

**[AI] Challenged an agent's report:** An agent said it had fixed a clipped column on the exposure table. Reading the screenshot showed Incurred still cut off at 1280px, so it went on the fix list. The same read turned up mixed date formats.

**[AI] Confirmed a finding the reviewer doubted:** The accessibility reviewer wasn't sure the confirm dialog was misplaced. The screenshot shows it is: full viewport width, with its × over the account icon.

**[AI] Rejected a backend change:** Lists show times in the browser's zone with no label. The first thought was to add the market zone to list APIs. Labelling the zone on screen is correct and needs no API change.

**[AI] Departed from FleetROS for accessibility:** Input borders and muted text are darkened to meet WCAG AA. Decided by the AI under the candidate's instruction to follow FleetROS, so it's flagged for the candidate in the report.

**[AI] Limit of the evidence:** The three frontend reviewers read code and screenshots only. Race findings aren't reproduced at runtime; the fix agent's tests resolve requests out of order to exercise them.

## 2026-11-04 — Verification: PIT and fault runs

**[AI] Raised its own bar on PIT:** Default mutators scored 96.5% before any new test. The agent switched to `STRONGER`, which dropped the baseline to 90.9% and exposed 20 weak spots. Final score 218 of 219; the main thread recounted it from the raw report.

**[AI] What fault runs found that reviews didn't:** The nginx config was in scope for backend review round 1, and no reviewer flagged this. Right after a service stops, nginx can still hold its old address and hang on connecting, for up to the 60s default. Only stopping a real container showed it. It was intermittent: one of three runs passed, and the agent refused to count that as a pass.

**[AI] Changed a check, and why:** With a 2s connect timeout nginx answers 504 in that window, so the check now accepts 502, 503 or 504. It also gained a limit it didn't have before: answer within 4s. The plan's failure table was wrong on this point and now says so.

**[AI] Its own decision was wrong:** Triage recorded that the server enforces who may accept or re-request a document. The live test showed the server allowed it on closed claims and messaged the claimant. No code review caught this. Corrected in the record, and fixed.

**[AI] What Schemathesis found:** Four server defects no test had caught: a page number overflow, a malformed query string and a bad multipart header each returned 500, the first on three endpoints. It also found 11 response fields typed non-null that could be null. Weak spot: creating a claim never succeeded under fuzzing, so that path is proven by the journey and API tests instead.

**[AI] Challenged an agent's proof:** To show the invariant test didn't depend on the clock, an agent moved the clock forward to 2031. That can't produce the failure in question, a clock earlier than the incident. The reset stays, recorded as unproven.

## Outbox test failure, final review

- **[AI]** An implementing agent saw `twoConcurrentPollersPublishEveryRowInOrder` fail once in 4 full runs and called it "probably an existing flake". The orchestrating AI didn't accept that, because the test guards the single-publisher design, and sent a separate investigation.
- **[AI]** Root cause: the test's poll count, not the poller. Reproduced in 14 of 100 repeats; fixed with a time limit.
- **[AI]** The investigating agent didn't re-run the test against a broken lock. The orchestrating AI did, in a scratch copy: 3 of 3 failed, with 14 to 29 version regressions.

## 2026-11-04 — Full suite, report

- **[AI] Corrected its own count:** it had reported 498 backend tests. Splitting the reports by age showed one was a leftover from a deleted test, so the real figure was 497.
- **[AI] Found its evidence had gone stale:** the suite didn't include PIT, and five domain classes had changed since PIT ran. The re-run dropped to 221 of 224, with two new survivors in the authority check. A unit test took it to 223 of 224.
- **[AI] Limit of the last review:** the final fix group was read only by the orchestrating AI, to limit cost, and had no separate reviewer.
- **[AI] Awaiting the candidate:** three disputed test findings; the per-claimant limit left as a stated gap; FleetROS colour departures; creating the Git history.
