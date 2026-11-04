-- I10, I11 and the database advisories of round 1. Nothing here rewrites an earlier migration.

-- ---- financial_entry.currency (I10) -------------------------------------------------------------
-- The ledger is append-only, so the backfill switches the row trigger off for this one statement.
ALTER TABLE financial_entry ADD COLUMN currency varchar(3);
ALTER TABLE financial_entry DISABLE TRIGGER financial_entry_append_only;
UPDATE financial_entry e SET currency = c.currency FROM claim c WHERE c.id = e.claim_id;
ALTER TABLE financial_entry ENABLE TRIGGER financial_entry_append_only;
ALTER TABLE financial_entry ALTER COLUMN currency SET NOT NULL;

-- ---- the market owns the currency (I11) ---------------------------------------------------------
ALTER TABLE market ADD CONSTRAINT market_code_currency_uq UNIQUE (code, currency);
ALTER TABLE claim ADD CONSTRAINT claim_market_currency_fk
  FOREIGN KEY (market_code, currency) REFERENCES market (code, currency);
ALTER TABLE financial_entry ADD CONSTRAINT financial_entry_currency_len CHECK (char_length(currency) = 3);
-- a ledger row carries its claim's currency
ALTER TABLE claim ADD CONSTRAINT claim_id_currency_uq UNIQUE (id, currency);
ALTER TABLE financial_entry ADD CONSTRAINT financial_entry_claim_currency_fk
  FOREIGN KEY (claim_id, currency) REFERENCES claim (id, currency);

-- ---- IDR has no minor unit: no fractional part, in every money column ---------------------------
ALTER TABLE claim ADD CONSTRAINT claim_idr_whole_amounts CHECK (currency <> 'IDR' OR (
      estimate = trunc(estimate)
  AND reserve = trunc(reserve)
  AND paid = trunc(paid)
  AND (approved_amount IS NULL OR approved_amount = trunc(approved_amount))
  AND coverage_limit = trunc(coverage_limit)
  AND deductible = trunc(deductible)));
ALTER TABLE financial_entry ADD CONSTRAINT financial_entry_idr_whole_amount
  CHECK (currency <> 'IDR' OR amount = trunc(amount));

-- ---- a claim's policy agrees with the claim's market and line -----------------------------------
ALTER TABLE policy ADD CONSTRAINT policy_id_market_line_uq UNIQUE (id, market_code, line);
ALTER TABLE claim DROP CONSTRAINT claim_policy_id_fkey;
ALTER TABLE claim ADD CONSTRAINT claim_policy_market_line_fk
  FOREIGN KEY (policy_id, market_code, line) REFERENCES policy (id, market_code, line);

-- ---- value checks -------------------------------------------------------------------------------
ALTER TABLE claim ADD CONSTRAINT claim_line_ck CHECK (line IN ('MOTOR', 'PROPERTY'));
ALTER TABLE claim ADD CONSTRAINT claim_status_ck CHECK (status IN
  ('SUBMITTED', 'UNDER_REVIEW', 'AWAITING_INFO', 'ASSESSED', 'APPROVED', 'SETTLED', 'REJECTED'));
ALTER TABLE claim ADD CONSTRAINT claim_reserve_status_ck CHECK (reserve_status IN ('PROVISIONAL', 'ASSESSED'));
ALTER TABLE claim ADD CONSTRAINT claim_coverage_limit_positive CHECK (coverage_limit > 0);
ALTER TABLE claim ADD CONSTRAINT claim_deductible_non_negative CHECK (deductible >= 0);
-- nothing is paid before an amount is approved, and never more than the approved amount
ALTER TABLE claim ADD CONSTRAINT claim_paid_within_approved CHECK (
  (approved_amount IS NULL AND paid = 0) OR (approved_amount IS NOT NULL AND paid <= approved_amount));

-- ---- idempotency: the hash of the canonicalised submit request ----------------------------------
ALTER TABLE claim ADD COLUMN request_hash varchar(64);

-- ---- claim numbers must not lose digits after 999999 (lpad truncates) ---------------------------
CREATE OR REPLACE FUNCTION next_claim_number(p_market varchar, p_year int) RETURNS text AS $$
  SELECT 'CLM-' || p_market || '-' || p_year || '-'
         || repeat('0', greatest(6 - length(n), 0)) || n
  FROM (SELECT nextval('claim_number_seq')::text AS n) s;
$$ LANGUAGE sql;

-- ---- indexes ------------------------------------------------------------------------------------
CREATE INDEX claim_policy_idx ON claim (policy_id);
CREATE INDEX claim_activity_timeline_idx ON claim_activity (claim_id, created_at DESC, id DESC);
CREATE INDEX claim_unassigned_pickup_idx ON claim (market_code, pickup_due_at) WHERE assigned_officer_id IS NULL;
