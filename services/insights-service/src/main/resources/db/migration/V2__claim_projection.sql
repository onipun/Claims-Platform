-- Read model built from claims.events.v1 (D4): one row per claim, replaced only by a higher version.
-- Money is stored at scale 4 and shown at the currency's scale (IDR 0, others 2). No claimant PII (trap 12).
CREATE TABLE claim_projection (
    claim_id          uuid PRIMARY KEY,
    claim_number      text           NOT NULL,
    market            char(2)        NOT NULL,
    line              text           NOT NULL,
    claim_type        text           NOT NULL,
    status            text           NOT NULL,
    officer_id        uuid,
    officer_name      text,
    currency          char(3)        NOT NULL,
    reserve           numeric(19, 4) NOT NULL,
    reserve_status    text           NOT NULL,
    paid              numeric(19, 4) NOT NULL,
    approved_amount   numeric(19, 4),
    rejection_code    text,
    submitted_at      timestamptz    NOT NULL,
    first_picked_up_at timestamptz,
    decided_at        timestamptz,
    closed_at         timestamptz,
    pickup_due_at     timestamptz    NOT NULL,
    decision_due_at   timestamptz    NOT NULL,
    late              boolean        NOT NULL,
    version           bigint         NOT NULL,
    last_event_at     timestamptz    NOT NULL
);

-- /exposure: scope + group.
CREATE INDEX claim_projection_market_line_ccy ON claim_projection (market, line, currency);
-- /workload: open claims per officer, and ageing by submitted date.
CREATE INDEX claim_projection_open_officer ON claim_projection (officer_id, submitted_at)
    WHERE status NOT IN ('SETTLED', 'REJECTED');
CREATE INDEX claim_projection_market_submitted ON claim_projection (market, submitted_at);
-- /performance: windows on decision and pick-up time.
CREATE INDEX claim_projection_market_decided ON claim_projection (market, decided_at) WHERE decided_at IS NOT NULL;
CREATE INDEX claim_projection_market_picked ON claim_projection (market, first_picked_up_at) WHERE first_picked_up_at IS NOT NULL;
-- asOf: newest event per scope.
CREATE INDEX claim_projection_market_last_event ON claim_projection (market, last_event_at DESC);
