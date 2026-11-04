# Glossary

These terms are used the same way in code, API and UI.

| Term | Meaning | Source |
|---|---|---|
| Claim | A policyholder's request for payment under a policy after an incident | S1 |
| First notice of loss | The first report of an incident to the insurer | S3 |
| Acknowledgement | Confirmation that the insurer received the claim. Here, the claim number returned on submission | S1 |
| Line of business | `MOTOR` or `PROPERTY` | Brief |
| Claim type | The kind of loss within a line, such as `OWN_DAMAGE` or `THIRD_PARTY_DAMAGE` (KFK in Malaysia) | S1 |
| Coverage limit | The most the policy pays | Standard usage |
| Deductible | The amount the policyholder bears. Not part of the insurer's liability | Standard usage |
| Estimate | The claimant's figure for the loss, before any assessment | S1 uses "estimate" for a repairer's |
| Reserve (case reserve) | The insurer's estimate of what it'll pay on one reported claim | S4 |
| Provisional reserve | An initial reserve, derived from the estimate before any officer has assessed it. A term specific to this system | Design decision D8 |
| Paid | Amounts actually paid on a claim | S4 |
| Case outstanding | Reserve less paid | S4 |
| Incurred | Paid plus case outstanding | S4 |
| Case exposure | Case outstanding across claims. **Not** total liability | This system's label |
| IBNR | Incurred but not reported: an actuarial provision for claims not yet known. **Not in this system** | S4 |
| Liability for incurred claims | The IFRS 17 balance. Includes IBNR, discounted, with a risk adjustment. **Not in this system** | S5 |
| Late report | A report past a market's sourced deadline. A flag, not a rejection | S2 |
| Authority limit | The most an officer may approve alone | Standard usage; values are placeholders |
| Claims officer | Claims staff who handle claims. The brief's "claims staff" | Brief |
| Claims manager | Claims staff who can reassign, and approve above an officer's limit | Decision by the candidate |
| Internal note | Staff-only comment, never visible to claimants | Design decision D7 |
| Settlement | Recording payment of the approved amount. No payment is actually executed | S1 |
