-- Domain schema. Money is numeric; the application enforces the currency's scale (IDR 0, others 2).
-- Plain varchar for codes and enums so Hibernate's schema validation sees VARCHAR everywhere.

CREATE TABLE market (
  code      varchar(2)  PRIMARY KEY,
  currency  varchar(3)  NOT NULL,
  region    varchar(32) NOT NULL,
  zone_id   varchar(64) NOT NULL
);

CREATE TABLE market_rule (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  market_code varchar(2)  NOT NULL REFERENCES market (code),
  line        varchar(16) NOT NULL,
  rule_type   varchar(64) NOT NULL,
  parameters  jsonb       NOT NULL DEFAULT '{}'::jsonb,
  source_url  text        NOT NULL,
  UNIQUE (market_code, line, rule_type)
);

CREATE TABLE app_user (
  id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  email           varchar(255)  NOT NULL UNIQUE,
  password_hash   varchar(100)  NOT NULL,
  name            varchar(255)  NOT NULL,
  role            varchar(16)   NOT NULL CHECK (role IN ('CLAIMANT', 'OFFICER', 'MANAGER')),
  authority_limit numeric(19,2) CHECK (authority_limit IS NULL OR authority_limit >= 0)
);

CREATE TABLE user_market (
  user_id     uuid       NOT NULL REFERENCES app_user (id),
  market_code varchar(2) NOT NULL REFERENCES market (code),
  PRIMARY KEY (user_id, market_code)
);

CREATE TABLE policy (
  id             uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
  policy_number  varchar(32)   NOT NULL UNIQUE,
  holder_id      uuid          NOT NULL REFERENCES app_user (id),
  market_code    varchar(2)    NOT NULL REFERENCES market (code),
  line           varchar(16)   NOT NULL CHECK (line IN ('MOTOR', 'PROPERTY')),
  coverage_limit numeric(19,2) NOT NULL CHECK (coverage_limit > 0),
  deductible     numeric(19,2) NOT NULL CHECK (deductible >= 0),
  effective_from date          NOT NULL,
  effective_to   date          NOT NULL,
  CHECK (effective_to >= effective_from)
);
CREATE INDEX policy_holder_idx ON policy (holder_id);

CREATE SEQUENCE claim_number_seq START 1;

-- CLM-SG-2026-000123: the sequence is global, so numbers are unique and never reused.
CREATE FUNCTION next_claim_number(p_market varchar, p_year int) RETURNS text AS $$
  SELECT 'CLM-' || p_market || '-' || p_year || '-' || lpad(nextval('claim_number_seq')::text, 6, '0');
$$ LANGUAGE sql;

CREATE TABLE claim (
  id                   uuid          PRIMARY KEY,
  claim_number         varchar(32)   NOT NULL UNIQUE,
  policy_id            uuid          NOT NULL REFERENCES policy (id),
  claimant_id          uuid          NOT NULL REFERENCES app_user (id),
  market_code          varchar(2)    NOT NULL REFERENCES market (code),
  line                 varchar(16)   NOT NULL,
  claim_type           varchar(32)   NOT NULL,
  reporting_channel    varchar(16)   NOT NULL DEFAULT 'ONLINE',
  incident_at_local    timestamp     NOT NULL,
  market_zone          varchar(64)   NOT NULL,
  reported_at          timestamptz   NOT NULL,
  late                 boolean       NOT NULL DEFAULT false,
  late_reason          text,
  contact_phone        varchar(32),
  description          text,
  loss_details         jsonb         NOT NULL,
  estimate             numeric(19,2) NOT NULL CHECK (estimate >= 0),
  status               varchar(16)   NOT NULL,
  assigned_officer_id  uuid          REFERENCES app_user (id),
  reserve              numeric(19,2) NOT NULL DEFAULT 0 CHECK (reserve >= 0),
  reserve_status       varchar(16)   NOT NULL,
  paid                 numeric(19,2) NOT NULL DEFAULT 0 CHECK (paid >= 0),
  approved_amount      numeric(19,2),
  rejection_code       varchar(32),
  rejection_explanation text,
  coverage_limit       numeric(19,2) NOT NULL,
  deductible           numeric(19,2) NOT NULL,
  currency             varchar(3)    NOT NULL,
  pickup_due_at        timestamptz   NOT NULL,
  decision_due_at      timestamptz   NOT NULL,
  first_picked_up_at   timestamptz,
  decided_at           timestamptz,
  closed_at            timestamptz,
  idempotency_key      varchar(128),
  updated_at           timestamptz   NOT NULL,
  version              bigint        NOT NULL,
  CHECK (reserve <= coverage_limit)
);
-- Trap 7: a repeated Idempotency-Key from the same claimant is the same submission.
CREATE UNIQUE INDEX claim_idempotency_uq ON claim (claimant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX claim_market_status_idx ON claim (market_code, status);
CREATE INDEX claim_officer_idx ON claim (assigned_officer_id);
CREATE INDEX claim_claimant_idx ON claim (claimant_id);

CREATE TABLE financial_entry (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  claim_id    uuid          NOT NULL REFERENCES claim (id),
  entry_type  varchar(16)   NOT NULL CHECK (entry_type IN ('RESERVE_SET', 'PAYMENT')),
  amount      numeric(19,2) NOT NULL CHECK (amount >= 0),
  actor_id    uuid          NOT NULL REFERENCES app_user (id),
  reason      text,
  created_at  timestamptz   NOT NULL
);
CREATE INDEX financial_entry_claim_idx ON financial_entry (claim_id, id);

CREATE TABLE claim_activity (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  claim_id      uuid         NOT NULL REFERENCES claim (id),
  activity_type varchar(32)  NOT NULL,
  actor_id      uuid         NOT NULL REFERENCES app_user (id),
  visibility    varchar(16)  NOT NULL CHECK (visibility IN ('INTERNAL', 'CLAIMANT')),
  message       text,
  from_status   varchar(16),
  to_status     varchar(16),
  created_at    timestamptz  NOT NULL
);
CREATE INDEX claim_activity_claim_idx ON claim_activity (claim_id, id);

-- Trap 11: only the database enforces immutability against every writer.
CREATE FUNCTION forbid_change() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER financial_entry_append_only
  BEFORE UPDATE OR DELETE ON financial_entry
  FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER financial_entry_no_truncate
  BEFORE TRUNCATE ON financial_entry
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();
CREATE TRIGGER claim_activity_append_only
  BEFORE UPDATE OR DELETE ON claim_activity
  FOR EACH ROW EXECUTE FUNCTION forbid_change();
CREATE TRIGGER claim_activity_no_truncate
  BEFORE TRUNCATE ON claim_activity
  FOR EACH STATEMENT EXECUTE FUNCTION forbid_change();

CREATE TABLE info_request (
  id          uuid         PRIMARY KEY,
  claim_id    uuid         NOT NULL REFERENCES claim (id),
  question    text         NOT NULL,
  asked_by    uuid         NOT NULL REFERENCES app_user (id),
  asked_at    timestamptz  NOT NULL,
  answer      text,
  answered_by uuid         REFERENCES app_user (id),
  answered_at timestamptz
);
-- Invariant 5 in the database as well: at most one open request per claim.
CREATE UNIQUE INDEX info_request_one_open_uq ON info_request (claim_id) WHERE answered_at IS NULL;

CREATE TABLE document_requirement (
  id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  market_code   varchar(2)  NOT NULL REFERENCES market (code),
  line          varchar(16) NOT NULL,
  claim_type    varchar(32) NOT NULL,
  document_type varchar(64) NOT NULL,
  source        text,
  placeholder   boolean     NOT NULL DEFAULT false,
  UNIQUE (market_code, line, claim_type, document_type)
);

CREATE TABLE claim_document (
  id            uuid         PRIMARY KEY,
  claim_id      uuid         NOT NULL REFERENCES claim (id),
  document_type varchar(64)  NOT NULL,
  filename      varchar(255) NOT NULL,
  content_type  varchar(128) NOT NULL,
  size_bytes    bigint       NOT NULL CHECK (size_bytes >= 0),
  storage_key   varchar(128) NOT NULL UNIQUE,
  status        varchar(16)  NOT NULL CHECK (status IN ('UPLOADED', 'ACCEPTED', 'RE_REQUESTED')),
  uploader_id   uuid         NOT NULL REFERENCES app_user (id),
  uploaded_at   timestamptz  NOT NULL
);
CREATE INDEX claim_document_claim_idx ON claim_document (claim_id);

CREATE TABLE outbox_event (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id     uuid         NOT NULL UNIQUE,
  claim_id     uuid         NOT NULL REFERENCES claim (id),
  version      bigint       NOT NULL,
  event_type   varchar(64)  NOT NULL,
  payload      jsonb        NOT NULL,
  created_at   timestamptz  NOT NULL,
  published_at timestamptz,
  UNIQUE (claim_id, version)
);
CREATE INDEX outbox_unpublished_idx ON outbox_event (id) WHERE published_at IS NULL;
