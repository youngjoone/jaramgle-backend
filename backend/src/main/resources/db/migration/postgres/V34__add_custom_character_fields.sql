ALTER TABLE characters
    ADD COLUMN owner_id BIGINT,
    ADD COLUMN description_prompt TEXT,
    ADD COLUMN art_style VARCHAR(128);

ALTER TABLE characters
    ADD CONSTRAINT fk_characters_owner
        FOREIGN KEY (owner_id) REFERENCES users (id)
        ON DELETE CASCADE;
