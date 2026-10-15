-- Apply explicitly to existing databases before using the space-template endpoints.
CREATE TABLE IF NOT EXISTS office_space_template (
    template_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(128) NOT NULL,
    name VARCHAR(120) NOT NULL,
    client_change_id VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    source_canvas_revision BIGINT NOT NULL,
    source_analysis_id VARCHAR(128),
    source_analysis_revision INTEGER,
    space_count INTEGER NOT NULL,
    area_m2 NUMERIC(24,4) NOT NULL,
    evidence_kind VARCHAR(24) NOT NULL,
    scene_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_id, client_change_id)
);
CREATE INDEX IF NOT EXISTS idx_office_space_template_project
    ON office_space_template(project_id, created_at DESC);
