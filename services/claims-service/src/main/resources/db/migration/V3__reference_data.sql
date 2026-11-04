-- Reference data only: markets, market rules and document requirements. Demo claims are created through
-- the domain service (trap 5), never here.
--
-- Round 1 (I1): the seeded users, user_market rows and policies used to be here. They moved to
-- db/demo/V1000__demo_users_and_policies.sql, which only the demo profile loads. This file was edited in
-- place, which is permitted only because nothing has shipped: no database exists with the old checksum.

INSERT INTO market (code, currency, region, zone_id) VALUES
  ('SG', 'SGD', 'Asia',      'Asia/Singapore'),
  ('HK', 'HKD', 'Asia',      'Asia/Hong_Kong'),
  ('MY', 'MYR', 'Asia',      'Asia/Kuala_Lumpur'),
  ('TH', 'THB', 'Asia',      'Asia/Bangkok'),
  ('ID', 'IDR', 'Asia',      'Asia/Jakarta'),
  ('AU', 'AUD', 'Australia', 'Australia/Sydney');

INSERT INTO market_rule (market_code, line, rule_type, parameters, source_url) VALUES
  ('SG', 'MOTOR', 'NEXT_WORKING_DAY',
   '{"weekend": ["SATURDAY", "SUNDAY"], "publicHolidays": false}',
   'https://gia.org.sg/motor-insurance/22-premium-renewal-of-policy/348-motor-claims-framewor');

-- Document requirements. Source: Chubb Malaysia, how motor insurance claims work (see TRACEABILITY.md).
-- The same list is repeated for the other markets and flagged placeholder.
-- "Completed claim form" is the submission itself, so it is not an upload requirement.
-- The theft list is a working list from the payment documents on the same page.
INSERT INTO document_requirement (market_code, line, claim_type, document_type, source, placeholder)
SELECT m.code, 'MOTOR', r.claim_type, r.document_type,
       CASE WHEN m.code = 'MY' THEN 'https://chubb.com/my-en/claims/how-motor-insurance-claims-work.html' END,
       m.code <> 'MY'
FROM market m
CROSS JOIN (VALUES
  ('OWN_DAMAGE',         'VEHICLE_REGISTRATION_CARD'),
  ('OWN_DAMAGE',         'INSURED_ID_AND_LICENCE'),
  ('OWN_DAMAGE',         'DRIVER_ID_AND_LICENCE'),
  ('OWN_DAMAGE',         'POLICE_REPORT'),
  ('OWN_DAMAGE',         'REPAIRER_ESTIMATE'),
  ('THIRD_PARTY_DAMAGE', 'VEHICLE_REGISTRATION_CARD'),
  ('THIRD_PARTY_DAMAGE', 'INSURED_ID_AND_LICENCE'),
  ('THIRD_PARTY_DAMAGE', 'DRIVER_ID_AND_LICENCE'),
  ('THIRD_PARTY_DAMAGE', 'POLICE_REPORT'),
  ('THIRD_PARTY_DAMAGE', 'REPAIRER_ESTIMATE'),
  ('THIRD_PARTY_DAMAGE', 'THIRD_PARTY_POLICE_REPORT'),
  ('THIRD_PARTY_DAMAGE', 'THIRD_PARTY_VEHICLE_DECLARATION'),
  ('TOTAL_LOSS',         'VEHICLE_REGISTRATION_CARD'),
  ('TOTAL_LOSS',         'INSURED_ID_AND_LICENCE'),
  ('TOTAL_LOSS',         'DRIVER_ID_AND_LICENCE'),
  ('TOTAL_LOSS',         'POLICE_REPORT'),
  ('TOTAL_LOSS',         'REPAIRER_ESTIMATE'),
  ('WINDSCREEN',         'VEHICLE_REGISTRATION_CARD'),
  ('WINDSCREEN',         'INSURED_ID_AND_LICENCE'),
  ('WINDSCREEN',         'DRIVER_ID_AND_LICENCE'),
  ('WINDSCREEN',         'REPAIRER_BILL'),
  ('WINDSCREEN',         'PHOTOS_BEFORE_AFTER'),
  ('THEFT',              'POLICE_REPORT'),
  ('THEFT',              'VEHICLE_REGISTRATION_CARD'),
  ('THEFT',              'IDENTITY_DOCUMENT')
) AS r(claim_type, document_type);
