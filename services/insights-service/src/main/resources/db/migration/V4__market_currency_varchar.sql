-- R7: `market = ANY (?)` is bound as text[]. Against char(2) (bpchar) that is a bpchar::text cast on the column,
-- so no index could supply the condition: the planner could only read a whole index and filter every row.
-- varchar compares as text, so the same bind is now an index condition. currency follows for the same reason
-- (and so the two code columns match the claims-service schema, which uses varchar).
ALTER TABLE claim_projection
    ALTER COLUMN market   TYPE varchar(2),
    ALTER COLUMN currency TYPE varchar(3);

-- No query can use this one. /workload filters market = ANY (?) first; the index leads with officer_id and has no
-- market column, so the planner never picks it (EXPLAIN on 300,000 rows chooses claim_projection_market_line_ccy,
-- and takes this index only with enable_seqscan = off, as a full scan). It is write cost on every upsert.
DROP INDEX IF EXISTS claim_projection_open_officer;
