ALTER TABLE items ADD COLUMN parent_item_id uuid REFERENCES items (id) ON DELETE CASCADE;

CREATE INDEX items_parent_idx ON items (parent_item_id, position) WHERE parent_item_id IS NOT NULL;
