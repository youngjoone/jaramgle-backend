ALTER TABLE characters ADD COLUMN modeling_status VARCHAR(20);

UPDATE characters
SET modeling_status = 'COMPLETED'
WHERE modeling_status IS NULL;

ALTER TABLE characters
ALTER COLUMN modeling_status SET DEFAULT 'PENDING';

ALTER TABLE characters
ALTER COLUMN modeling_status SET NOT NULL;
