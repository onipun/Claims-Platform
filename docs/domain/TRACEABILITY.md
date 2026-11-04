# Traceability

Every source item is mapped: built, partly built, not built, or out of scope with a reason. Each status was checked against the code on 2026-11-04. `Built` means the code exists and the named evidence is real; where no test covers a row, the row says so. Paths: API under `services/claims-service/src/main/java/com/chubb/claims/` (`claim/api/ClaimantController.java` etc.), seeds in `services/claims-service/src/main/resources/db/migration/`, forms in `web/src/app/features/claimant/`.

## Process (S1, S2)

| Source item | Status | Where / why not |
|---|---|---|
| S1 Report the claim | Built | `POST /api/v1/claimant/claims` (`ClaimantController`, `SubmitClaimRequest`); form `report-claim.page.ts` + `claim-form.ts`. Test: `ClaimantApiTest.submitReturns201WithClaimEtagLocationAndMoneyAsStrings` |
| S1 Acknowledgement | Built | Claim number `CLM-SG-2026-nnnnnn` in the 201 response; shown by `report-claim.page.ts`. Tests: `submitReturns201WithClaimEtagLocationAndMoneyAsStrings`; web `report-claim.page.spec.ts` "shows the claim number as the acknowledgement with documents as the next step" |
| S1 Submit documents, complete documents expedite settlement | Partial | Checklist `GET /api/v1/claimant/claims/{id}/documents`, upload `POST` on the same path (multipart), `documents-panel.ts`. Test: `DocumentApiTest.checklistMergesRequirementsWithUploadsAndSaysWhetherPlaceholder`. Nothing links completeness to settlement speed, and there is no per-type check beyond OWN_DAMAGE (see the document table). Requirements exist for motor only: property claims have no `DocumentRequirement` rows |
| S1 Report through agent | Out of scope | Claimant reports directly. `claim.reporting_channel` exists (`V2__domain_schema.sql:65`, default `'ONLINE'`) but no code sets or returns it: it is always `ONLINE`, absent from the API and the UI. See Reporting channel mismatch below |
| S1 Report through appointed repairer | Out of scope | As above |
| S1 Online portal | Built | `web/` Angular app (claimant routes `claimant.routes.ts`) |
| S2 Every accident must be reported, however small | Built | Estimate of 0 accepted: `AmountRules.submitEstimate` (`>= 0`), DB `CHECK (estimate >= 0)`. Test: `AmountRulesTest.submitEstimateMustBeNonNegativeAndFitTheCurrency` (asserts 0 passes). No API-level test submits an estimate of 0 |
| S2 Deadline: end of next working day | Built (SG motor only) | `LateReportRule`; `claim.late` column; shown in `claim-detail.page.ts` and the report page. Tests: `LateReportRuleTest.giaExample_*`, `ClaimantApiTest.sgMotorSaturdayNightIncidentIsDueMondayNotLateOnMondayLateOnTuesday`, `hkClaimUsesHkdAndIsNeverFlaggedLate`. Other markets and property produce no flag (no sourced rule). Public holidays not modelled |
| S2 Late with valid reason isn't penalised | Built | `lateReason` field (`SubmitClaimRequest`, form field `lateReason`, `late_reason` column); stored only when the claim is late (`ClaimSubmissionTx:105-108`); no rejection path. Tests: `ClaimantApiTest.lateReasonIsKeptOnlyWhenLateAndNeverRejects`; GIA Saturday-night example: `LateReportRuleTest.giaExample_saturdayNightAccident_reportedMonday_isNotLate` and `..._reportedLastSecondOfMonday_isNotLate` |
| S2 Bring vehicle to approved centre within 24 hours | Out of scope | Physical process |
| S2 Particulars of other party: name, phone, insurer, vehicle number | Built | `motor.thirdParty.{name,phone,insurer,vehicleNumber}` (`MotorDetails.ThirdPartyDetails`); form fields `tpName`, `tpPhone`, `tpInsurer`, `tpVehicle`. All four are optional, including for THIRD_PARTY_DAMAGE. NRIC is not collected: no such field in any DTO, form or column. Tests: web `claim-form.spec.ts` ("sends only motor details..." and "omits third party when not involved..."); no server-side test asserts the round trip |
| S2 Police report for a Malaysian-registered vehicle | Built (not enforced) | `motor.policeReport` free text, max 64 (`MotorDetails`); form field `policeReport`. `SubmissionValidator` does not require it for any market or claim type; no test asserts a claim submits without it (test fixtures always send `PR-1`). The POLICE_REPORT document is requested by the checklist instead |

## Motor claim types and documents (S1)

Seeded as `DocumentRequirement` rows in `V3__reference_data.sql:25-56`: one row per market and document, 25 per market, 150 in all. MY rows carry the S1 source; the other five markets are placeholder copies. Exposed by `GET /api/v1/claimant/claims/{id}/documents` and shown in `documents-panel.ts`. Tests: `MigrationTest.documentRequirementsAreSourcedForMalaysiaAndPlaceholderElsewhere` (counts only, 6 markets x MY rows), `DocumentApiTest.checklistMergesRequirementsWithUploadsAndSaysWhetherPlaceholder` (OWN_DAMAGE, 5 items). No test asserts the exact list for the other four types. Physical originals for payment are out of scope.

| Claim type | Documents (document_type seeded) | Status |
|---|---|---|
| All | Completed claim form (the submission itself) | Built, implicitly: no `DocumentRequirement` row; the submitted claim is the form |
| Own damage | `VEHICLE_REGISTRATION_CARD`, `INSURED_ID_AND_LICENCE`, `DRIVER_ID_AND_LICENCE`, `POLICE_REPORT`, `REPAIRER_ESTIMATE` (5) | Built |
| Third-party damage | The five above plus `THIRD_PARTY_POLICE_REPORT`, `THIRD_PARTY_VEHICLE_DECLARATION` (7) | Built |
| Total loss | Same five as own damage (5) | Built |
| Windscreen | `VEHICLE_REGISTRATION_CARD`, `INSURED_ID_AND_LICENCE`, `DRIVER_ID_AND_LICENCE`, `REPAIRER_BILL`, `PHOTOS_BEFORE_AFTER` (5); no police report, no estimate | Built |
| Theft (working list) | `POLICE_REPORT`, `VEHICLE_REGISTRATION_CARD`, `IDENTITY_DOCUMENT` (3). Working list from S1's payment documents, not an S1 claim-document list | Built (working list) |
| Total loss, theft: originals, K3 forms, keys, discharge voucher | Out of scope | Physical items and payment stage |
| Company claimants: business registration, board resolution | Out of scope | Individual claimants only |

## First-notice sections (S3, section names only; low confidence, US-origin ACORD forms, not read in full)

| Section | Status | Field |
|---|---|---|
| Insured | Built | From the logged-in account and selected policy (`GET /api/v1/claimant/policies`, `policies.page.ts`) |
| Contact | Built | `contactPhone` (`SubmitClaimRequest`; form `contactPhone`; max 32) |
| Loss | Built | `incidentAtLocal`, `claimType`, `description`, `estimate`; form fields `incidentDate`, `incidentTime`, `claimType`, `description`, `estimate`. Where it happened is `motor.location` (next row) |
| Accident location (motor) | Built | `motor.location`, required, at most 200 characters, stored in the claim's loss details, returned in the claimant and staff detail views, not in the Kafka event. Spec: LIFECYCLE.md "Submission rules". Tests: `ClaimantApiTest.motorWithoutLocationIsRejectedAndLocationRoundTripsToBothViews`, `AccessRulesTest.motorNeedsRegistrationAndDriver`. Form field: label "Accident location" (`web/e2e/claim-journey.spec.ts`) |
| Policy | Built | `policyId` (policy chosen in `report-claim.page.ts`) |
| Insured vehicle | Built | `motor.vehicleRegistration`, `motor.driverName`; both required (`SubmissionValidator`); form `vehicleRegistration`, `driverName` |
| Third-party vehicle | Built | As the third-party row above |
| Injured | Built | `motor.injury` (boolean, absent means false) and `motor.injuryDescription` (free text, stored, not tied to the flag); form `injury`, `injuryDescription`. Test: `ClaimantApiTest.motorWithoutInjuryFlagMeansNoInjury`; web `claim-form.spec.ts` asserts `injury: true` in the request. No test of the description |
| Witnesses or passengers | Out of scope for structured data | No field anywhere; free text in `description` only |
| Property: probable amount, description of loss | Built | `estimate`, `description`, plus `property.{address,cause,occupancy}`. Test: `ClaimantApiTest.propertyClaimIsAccepted` |
| Property: mortgagee | Out of scope | Absent from every DTO, form and column. Not relevant to the claims workflow here |

## Finance (S4, S5)

| Item | Status | Where / why not |
|---|---|---|
| Case reserve, paid, incurred | Built | Ledger table `financial_entry`; `GET /api/v1/staff/claims/{id}/financials` (`StaffFinancials`: reserve, paid, outstanding, incurred); `GET /api/v1/insights/exposure`. Tests: `ClaimantApiTest.reserveInTheLedgerEqualsTheFormula`, `InsightsApiTest.exposureEveryNumber` |
| IBNR | Out of scope | Actuarial. Excluded and labelled: exposure response carries `basis: "CASE_RESERVES_EXCL_IBNR"` (`InsightsService.BASIS`); UI text in `insights.page.ts`. Test: `InsightsApiTest.exposureEveryNumber` (asserts `basis`) |
| IFRS 17 discounting and risk adjustment | Out of scope | Accounting layer |
| Loss adjustment expenses | Out of scope | Named in the PRD |
