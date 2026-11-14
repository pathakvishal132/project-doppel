-- A VPA accepted by the API regex can be up to 256 (handle) + 1 ('@') + 64 (PSP) = 321 characters.
-- VARCHAR(255) made valid max-length VPAs fail with HTTP 500 on PostgreSQL.
ALTER TABLE screening ALTER COLUMN vpa TYPE VARCHAR(321);
ALTER TABLE alert     ALTER COLUMN vpa TYPE VARCHAR(321);
ALTER TABLE brand_vpa ALTER COLUMN vpa TYPE VARCHAR(321);
