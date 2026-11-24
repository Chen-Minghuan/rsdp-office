ALTER TABLE office_design_project
    ADD COLUMN IF NOT EXISTS status varchar(20) NOT NULL DEFAULT 'DESIGNING'
    CONSTRAINT ck_office_project_status CHECK (status IN ('DESIGNING', 'DELIVERING', 'COMPLETED'));
