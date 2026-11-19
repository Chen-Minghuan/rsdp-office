ALTER TABLE office_workspace
    ADD COLUMN IF NOT EXISTS workspace_type VARCHAR(16) NOT NULL DEFAULT 'DEALER'
        CHECK (workspace_type IN ('DEALER', 'FACTORY'));
