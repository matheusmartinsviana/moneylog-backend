ALTER TABLE tracked_commitment_items ADD CONSTRAINT uq_tracked_commitment_items_user UNIQUE (commitment_id, position, user_id);
ALTER TABLE tracked_commitment_item_shares DROP CONSTRAINT fk_tracked_commitment_item_shares_item;
ALTER TABLE tracked_commitment_item_shares ADD CONSTRAINT fk_tracked_commitment_item_shares_item
    FOREIGN KEY (commitment_id, position, user_id) REFERENCES tracked_commitment_items (commitment_id, position, user_id) ON DELETE CASCADE;
