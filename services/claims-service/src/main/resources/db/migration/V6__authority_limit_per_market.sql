-- I6: an approval limit is in a market's currency, so it belongs to the (user, market) pair, not the user.
-- A user with a limit in SG (SGD) and another in ID (IDR) cannot be described by one number.
-- NULL stays "unlimited" for a MANAGER and "no authority" for an OFFICER.
ALTER TABLE user_market
  ADD COLUMN authority_limit numeric(19,2) CHECK (authority_limit IS NULL OR authority_limit >= 0);

-- Carry the old single limit across to every market the user belongs to.
UPDATE user_market um
   SET authority_limit = u.authority_limit
  FROM app_user u
 WHERE u.id = um.user_id;

ALTER TABLE app_user DROP COLUMN authority_limit;
