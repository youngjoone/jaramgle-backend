-- Add new columns required for social login and refactoring
ALTER TABLE users ADD COLUMN name VARCHAR(255);
ALTER TABLE users ADD COLUMN provider_id VARCHAR(255);
ALTER TABLE users ADD COLUMN role VARCHAR(255);

-- Copy data from old nickname column to new name column
UPDATE users SET name = nickname WHERE nickname IS NOT NULL;

-- Make the new name column not nullable after populating it
ALTER TABLE users ALTER COLUMN name SET NOT NULL;

-- Drop the old columns that are no longer in use
ALTER TABLE users DROP COLUMN nickname;
ALTER TABLE users DROP COLUMN email_verified;
