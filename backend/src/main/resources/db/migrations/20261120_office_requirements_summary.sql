ALTER TABLE office_design_project
    ADD COLUMN IF NOT EXISTS requirements_summary JSONB,
    ADD COLUMN IF NOT EXISTS requirements_summary_revision INTEGER,
    ADD COLUMN IF NOT EXISTS requirements_summary_model VARCHAR(100),
    ADD COLUMN IF NOT EXISTS requirements_summary_prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS requirements_summary_generated_at TIMESTAMP;
