-- I12: three indexes that no query can use. InsightsQueries filters on market only in WHERE (market = ANY(?));
-- decided_at, first_picked_up_at and submitted_at appear only inside FILTER (WHERE ...) and aggregate
-- expressions, which a partial or btree index on those columns cannot serve, and the ageing buckets read
-- every open row of the market anyway. Each is pure write cost on a projection that is upserted on every event.
-- (The market-only scan is served by claim_projection_market_line_ccy and claim_projection_market_last_event.)
DROP INDEX IF EXISTS claim_projection_market_decided;
DROP INDEX IF EXISTS claim_projection_market_picked;
DROP INDEX IF EXISTS claim_projection_market_submitted;
