-- Whether the brand-matching part of the stored response came from the screening cache.
ALTER TABLE screening ADD COLUMN cached BOOLEAN DEFAULT FALSE NOT NULL;
