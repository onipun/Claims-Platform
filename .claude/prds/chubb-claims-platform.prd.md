# Chubb APAC Claims Platform

## Problem

Chubb APAC handles motor and property claims across six markets through phone, email, shared
inboxes and spreadsheets. Claimants wait with no visibility of their claim, claims staff have
no consolidated view of their workload, and managers have no real-time picture of outstanding
claims or liability exposure. The cost of leaving this unsolved is slow handling, claims that
stall unnoticed, and an exposure figure nobody can state with confidence.

## Evidence

The brief is a digest, so requirements here are traced to sources closer to the real work. Full list and gaps: plan section 2.

| Source | What it establishes |
|---|---|
| [Chubb Malaysia: how motor claims work](https://chubb.com/my-en/claims/how-motor-insurance-claims-work.html) | Real steps: report, acknowledgement, submit documents. Claim types: own damage, third-party damage (KFK), total loss, windscreen, theft. A required-document list per type |
| [GIA Singapore Motor Claims Framework](https://gia.org.sg/motor-insurance/22-premium-renewal-of-policy/348-motor-claims-framewor) | Reporting deadline: end of next working day (a Saturday-night accident is due Monday). Late reporting is excused with a valid reason, so lateness is a flag, not an automatic rejection |
| ACORD 2 and ACORD 1 loss notices ([auto](https://www.abbyy.com/marketplace/assets/host/abbyy/document-skill/acord-2-automobile-loss-notice/), [property](https://docshield.tungstenautomation.com/DocLib/en_US/help/DocumentLibrary_Help/DocumentTypes/2025.2/USAForms/ACORD/c_USAAcord1.html)) | Industry-standard first-notice sections: insured, contact, loss, policy, insured vehicle, third-party vehicle, injured, witnesses. **Section names only: the form PDFs could not be fetched** |
| [CAS reserve principles](https://www.casact.org/sites/default/files/2021-04/statement_of_principles_Loss_Loss_Adjustment%20_Expense%20_Reserves_2021.pdf), [IFRS 17 summary](https://www.cpdbox.com/ifrs-17-insurance-contracts-summary) | Incurred = paid + case reserves. Total liability also includes IBNR, and under IFRS 17 is discounted with a risk adjustment. A claims system alone can't state that total |

Claim volumes, handling times and the six market names are not given. `Assumption — needs validation via the hiring panel.` No claims professional has reviewed this PRD. `Needs validation via the panel or a claims practitioner.`

## Users

- **Claimant (primary)**: a policyholder after a motor or property incident. Needs to report it, supply the documents it requires, see where it stands, answer questions and receive the decision.
- **Claims officer (primary)**: picks up claims in their market, reviews and assesses them, and progresses them to settlement or rejection. Needs to see what to work on next and their team's workload and performance.
- **Claims manager (primary, decided)**: sees outstanding claims and exposure, rebalances work, and approves above an officer's authority. A kind of claims staff, with its own role.
- **Not for**: brokers, repairers, adjusters, finance, actuarial and reinsurance teams. Chubb's real channels include agents and repairers reporting on a claimant's behalf; these are out of scope, but each claim records its reporting channel so they can be added.

## Hypothesis

We believe **one Angular application over a claims system of record, with exposure and workload views fed by events,** will **replace phone-and-spreadsheet handling with a visible, auditable lifecycle** for **claimants, officers and managers**.

We'll know we're right when **all three roles follow a claim end to end, and the exposure figure matches an independent calculation from raw records, including while parts of the system are failing**.

## Success Metrics

Each is judged by something other than the author: an outside source, an independent tool or a separate calculation. Plan section 3 names the tool.

| Metric | Target | Judged by |
|---|---|---|
| Domain fidelity | Every field and document on the source list is mapped: built, or out of scope with a reason | Traceability table against the sources above |
| Deadline rule | GIA's own worked example passes (Saturday-night accident, due Monday) | GIA's published text |
| Exposure accuracy | Dashboard equals an independent SQL calculation from the reserve ledger, per market and currency, with no differences | SQL script sharing no code with either service |
| Lifecycle integrity | No sequence of commands breaks an invariant | Random command sequences; mutation testing of the rules |
| No lost events | Killing processes and brokers mid-flow loses nothing and double-counts nothing | Fault-injection runs, followed by reconciliation |
| API honesty | The running API conforms to its published contract | Schemathesis |
| Access control | A claimant can't reach another's claim, note or document | Authorisation test matrix, threat model |
| Accessibility | No serious or critical violations on key screens | axe-core |
| Journey | One browser journey across all three roles passes | Playwright |
| Start-up | Starts with one command from a fresh clone | Clean checkout |
| Domain realism | A claims practitioner or the panel finds nothing materially wrong | People. **Not achievable by tooling: open** |

## Scope

**MVP**

1. **Report an incident.** A claimant selects a policy and reports a motor or property incident using fields traced to loss-notice forms: when and where in the market's local time, type of loss, description, estimate, plus third party, injury and police report details for motor; location and cause for property. Validated against the policy. A claim number is issued immediately as the acknowledgement. Resubmitting doesn't create a duplicate.
2. **Late-report flag.** Where a market has a sourced rule, a late report is flagged for the officer with the claimant's stated reason. It never rejects automatically. A market with no sourced rule shows no flag rather than an invented one.
3. **Documents.** The claim shows a required-document checklist by claim type. Claimants upload; officers review and accept or ask again. Access is authorised and logged.
4. **Track a claim.** Status, timeline, outstanding documents, and whether action is needed.
5. **Provide additional information.** An officer asks; the claim waits on the claimant; the answer returns it to review.
6. **Receive decisions.** Approval with amount, or rejection with reason, plus an updates feed across the claimant's claims.
7. **Work queue.** Unassigned claims in the market ordered by urgency. Two officers can't both win the same claim. An officer can't handle a claim on their own policy.
8. **Assess and decide.** Internal notes never visible to claimants; reserve changes; approval within authority or rejection with a reason code; settlement. Managers reassign and approve above an officer's limit.
9. **Team workload and performance.** Open claims per officer, ageing, overdue, cycle time and approval rate, within the viewer's markets.
10. **Case exposure.** Paid, case outstanding and incurred, by market and line, separating provisional from assessed, in native currency with a labelled indicative USD total, with figure freshness. **Labelled as case reserves: it excludes IBNR and isn't an IFRS 17 figure.**
11. **Financial audit trail.** Every reserve and payment change is an append-only ledger entry, allowing exposure to be restated as at a past date.
12. **Audit trail.** Every state change and document access records who, when and what, and can't be edited.

**Out of scope**

- Email, SMS and push delivery — updates are in the application.
- Self-registration, password reset and enterprise single sign-on — users are seeded.
- Reporting by agents and repairers, fraud detection, automatic triage, payment execution.
- Reopening, partial payments, recoveries, loss-adjustment expenses, reinsurance, IBNR.
- Policy administration — seeded reference data.
- Malware scanning of uploads — validated by type and size, with the gap stated.
- Localisation, and a market's rules beyond those with a source. Data residency is an architecture discussion.
- Live foreign-exchange rates — fixed and labelled indicative.
- Observability stack, load testing and automated security scans — removed on the candidate's decision. There are no performance figures and no dependency or image scan results. Named in the walkthrough as next steps.

## Delivery Milestones

| # | Milestone | Outcome | Status | Plan |
|---|---|---|---|---|
| 0 | Domain grounding | Sources, domain language, threat model and quality thresholds are written down | in-progress | `.claude/plans/chubb-claims-platform.plan.md` |
| 1 | Claim lifecycle | A claim can go from report to settlement or rejection through a secured API | pending | same |
| 2 | Exposure and workload | Exposure, workload and performance, fed by events, surviving failures | pending | same |
| 3 | Claimant application | MVP items 1–6 in the browser | pending | same |
| 4 | Staff application | MVP items 7–10 in the browser | pending | same |
| 5 | Independent verification | Every Success Metric has outside evidence | pending | same |
| 6 | Review, report and walkthrough | Reviewed code, a report and a defensible walkthrough | pending | same |

## Open Questions

- [ ] Which six markets? Working assumption: SG, HK, MY, TH, ID, AU, one currency each. Markets are data, not code.
- [ ] Which service levels apply internally? None found. Pick-up and decision targets are labelled placeholder configuration.
- [ ] Can a claims practitioner check the model before the walkthrough? It's the only independent check on domain realism.
- [ ] Can full ACORD forms be obtained? Automated fetches were refused.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The candidate can't defend generated code at the walkthrough | Medium | High | Conventional design; decision records; walkthrough pack points to what to read first |
| Sources from one market, or a US form, are treated as universal | Medium | Medium | Rules are per market data; unsourced markets show nothing |
| The exposure figure is mistaken for total liability | High if unlabelled | High | Labelled as case reserves in the API and the UI |
| Kafka is present for its own sake | Low | Medium | Stated reason for each channel; fault runs show the value |

---
*Status: DRAFT, revision 3. Implementation planning is in `.claude/plans/chubb-claims-platform.plan.md`.*
