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

INSERT INTO office_design_project (
    project_id, name, customer_requirements, requirements_revision, created_at, updated_at
) VALUES (
    'OFFICE-PROJECT-001', '默认项目', '', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
) ON CONFLICT (project_id) DO NOTHING;

INSERT INTO office_design_floor (
    floor_id, project_id, name, sort_order, created_at, updated_at
) VALUES (
    'OFFICE-PROJECT-001', 'OFFICE-PROJECT-001', '默认楼层', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
) ON CONFLICT (floor_id) DO NOTHING;
