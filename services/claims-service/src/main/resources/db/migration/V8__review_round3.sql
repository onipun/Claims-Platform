-- Final-review fixes. Nothing here rewrites an earlier migration.

-- ---- R5: optimistic version on a document, so two concurrent reviews cannot both win ----------------
ALTER TABLE claim_document ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- ---- R8: the staff All and Mine queues sort by (updated_at DESC, claim_number); nothing supported it --
-- All: scope on the market, then the sort. Mine: scope on the officer, then the sort. The old
-- single-column claim_officer_idx is a prefix of the new one.
CREATE INDEX claim_market_updated_idx ON claim (market_code, updated_at DESC, claim_number);
CREATE INDEX claim_officer_updated_idx ON claim (assigned_officer_id, updated_at DESC, claim_number);
DROP INDEX claim_officer_idx;
-- Unassigned sorts by (pickup_due_at, claim_number): the old index stopped before the tie-break.
DROP INDEX claim_unassigned_pickup_idx;
CREATE INDEX claim_unassigned_pickup_idx ON claim (market_code, pickup_due_at, claim_number)
  WHERE assigned_officer_id IS NULL;

-- ---- info_request is loaded per claim in asked_at order ---------------------------------------------
CREATE INDEX info_request_claim_asked_idx ON info_request (claim_id, asked_at);

-- ---- claim_activity_timeline_idx duplicates claim_activity_claim_idx for every query the service runs:
-- the timeline reads one claim ordered by id; nothing orders one claim by created_at. ----------------
DROP INDEX claim_activity_timeline_idx;

-- ---- a claim's claimant is its policy's holder -----------------------------------------------------
ALTER TABLE policy ADD CONSTRAINT policy_id_holder_uq UNIQUE (id, holder_id);
ALTER TABLE claim ADD CONSTRAINT claim_policy_holder_fk
  FOREIGN KEY (policy_id, claimant_id) REFERENCES policy (id, holder_id);

-- ---- status and amount columns cannot disagree --------------------------------------------------------
-- approved_amount is set by APPROVE and kept through SETTLE; rejection_code by REJECT; closed_at by REJECT and SETTLE.
ALTER TABLE claim ADD CONSTRAINT claim_approved_needs_amount CHECK (
  status NOT IN ('APPROVED', 'SETTLED') OR approved_amount IS NOT NULL);
ALTER TABLE claim ADD CONSTRAINT claim_rejected_needs_code CHECK (
  status <> 'REJECTED' OR rejection_code IS NOT NULL);
ALTER TABLE claim ADD CONSTRAINT claim_closed_needs_closed_at CHECK (
  status NOT IN ('SETTLED', 'REJECTED') OR closed_at IS NOT NULL);
