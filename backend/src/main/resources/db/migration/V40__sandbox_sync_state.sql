ALTER TABLE remote_workspaces
    ADD COLUMN synced_at timestamptz,
    ADD COLUMN synced_files integer,
    ADD COLUMN synced_bytes bigint;
