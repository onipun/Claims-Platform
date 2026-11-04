-- DEMO DATA. Loaded only when the demo profile adds classpath:db/demo to spring.flyway.locations
-- (application-demo.yml), and by tests that opt in. The default profile never sees these rows.
--
-- Versioning: files here use high versions (V1000 and up) so they always sort after every
-- db/migration script, whatever version that reaches. They may use the schema as it stands after all of
-- db/migration, and a new schema change goes into db/migration, never here.
--
-- Every demo account uses the password Password1! (BCrypt, cost 10). Round 1 (I1): moved here from V3
-- and V5 (see the comments there). Authority limits live on user_market (I6): a NULL limit is unlimited
-- for a MANAGER and no authority for an OFFICER. Limits are in the market's currency.

INSERT INTO app_user (email, password_hash, name, role) VALUES
  ('claimant.sg@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Alicia Tan', 'CLAIMANT'),
  ('claimant2.sg@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Benjamin Lim', 'CLAIMANT'),
  ('claimant.hk@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Chan Tai Man', 'CLAIMANT'),
  ('officer.sg@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Priya Nair', 'OFFICER'),
  ('officer2.sg@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Wei Ming Ong', 'OFFICER'),
  ('officer.hk@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Mei Ling Wong', 'OFFICER'),
  ('manager.sg@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Rachel Goh', 'MANAGER'),
  ('manager.apac@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Daniel Foo', 'MANAGER'),
  ('claimant.my@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Nurul Aisyah', 'CLAIMANT'),
  ('officer.my@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Hafiz Rahman', 'OFFICER'),
  ('claimant.id@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Budi Santoso', 'CLAIMANT'),
  ('officer.id@demo.test', '$2a$10$levwaZQSjOZzm/nKjZ2T6e0g7hJaiyrXtnfKyrzDlDt/V/bnqRAk.', 'Sari Wulandari', 'OFFICER');

INSERT INTO user_market (user_id, market_code, authority_limit)
SELECT u.id, v.market, v.lim
FROM (VALUES
  ('claimant.sg@demo.test', 'SG', NULL),
  ('claimant2.sg@demo.test', 'SG', NULL),
  ('officer.sg@demo.test', 'SG', 10000),
  ('officer2.sg@demo.test', 'SG', 50000),
  ('manager.sg@demo.test', 'SG', NULL),
  ('claimant.hk@demo.test', 'HK', NULL),
  ('officer.hk@demo.test', 'HK', 100000),
  ('claimant.my@demo.test', 'MY', NULL),
  ('officer.my@demo.test', 'MY', 20000),
  ('claimant.id@demo.test', 'ID', NULL),
  ('officer.id@demo.test', 'ID', 200000000),
  ('manager.apac@demo.test', 'SG', NULL),
  ('manager.apac@demo.test', 'HK', NULL),
  ('manager.apac@demo.test', 'MY', NULL),
  ('manager.apac@demo.test', 'TH', NULL),
  ('manager.apac@demo.test', 'ID', NULL),
  ('manager.apac@demo.test', 'AU', NULL)
) AS v(email, market, lim)
JOIN app_user u ON u.email = v.email;

-- Policies run to 2030. officer2.sg also holds one: the conflict-of-interest fixture.
INSERT INTO policy (policy_number, holder_id, market_code, line, coverage_limit, deductible, effective_from, effective_to)
SELECT v.number, u.id, v.market, v.line, v.cov, v.ded, DATE '2025-01-01', DATE '2030-12-31'
FROM (VALUES
  ('MOT-SG-100001', 'claimant.sg@demo.test', 'SG', 'MOTOR', 100000, 500),
  ('PRP-SG-100002', 'claimant.sg@demo.test', 'SG', 'PROPERTY', 500000, 1000),
  ('MOT-SG-100003', 'claimant2.sg@demo.test', 'SG', 'MOTOR', 80000, 500),
  ('PRP-SG-100004', 'claimant2.sg@demo.test', 'SG', 'PROPERTY', 400000, 1000),
  ('MOT-HK-200001', 'claimant.hk@demo.test', 'HK', 'MOTOR', 800000, 3000),
  ('PRP-HK-200002', 'claimant.hk@demo.test', 'HK', 'PROPERTY', 3000000, 5000),
  ('MOT-SG-100005', 'officer2.sg@demo.test', 'SG', 'MOTOR', 60000, 500),
  ('MOT-MY-300001', 'claimant.my@demo.test', 'MY', 'MOTOR', 150000, 500),
  ('PRP-MY-300002', 'claimant.my@demo.test', 'MY', 'PROPERTY', 800000, 2000),
  ('MOT-ID-400001', 'claimant.id@demo.test', 'ID', 'MOTOR', 500000000, 2000000),
  ('PRP-ID-400002', 'claimant.id@demo.test', 'ID', 'PROPERTY', 2000000000, 5000000)
) AS v(number, email, market, line, cov, ded)
JOIN app_user u ON u.email = v.email;
