CREATE TABLE IF NOT EXISTS office_design_project (
    project_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    customer_requirements TEXT NOT NULL DEFAULT '',
    requirements_revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS office_design_floor (
    floor_id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL REFERENCES office_design_project(project_id),
    name VARCHAR(200) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_office_design_floor_project
    ON office_design_floor(project_id, sort_order, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_office_design_floor_name
    ON office_design_floor(project_id, lower(name));
