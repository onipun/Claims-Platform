-- Runs once, on first start of an empty data volume.
CREATE ROLE claims_app LOGIN PASSWORD 'claims_dev';
CREATE ROLE insights_app LOGIN PASSWORD 'insights_dev';
CREATE DATABASE claims OWNER claims_app;
CREATE DATABASE insights OWNER insights_app;
REVOKE CONNECT ON DATABASE claims FROM PUBLIC;
REVOKE CONNECT ON DATABASE insights FROM PUBLIC;
