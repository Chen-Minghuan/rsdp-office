-- PROJECT-004: 项目成员关系与项目参考图片
-- 只建表与字段，不改历史项目归属；旧项目由授权管理员确认后补录负责人。

CREATE TABLE IF NOT EXISTS office_project_member (
    project_id VARCHAR(64) NOT NULL
        REFERENCES office_design_project(project_id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL
        REFERENCES office_user(user_id),
    role VARCHAR(20) NOT NULL
        CHECK (role IN ('LEADER', 'COLLABORATOR')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, user_id)
);

-- 一项目至多一位负责人；“至少一位”由创建/交接/删除成员的事务保证。
CREATE UNIQUE INDEX IF NOT EXISTS uk_office_project_one_leader
    ON office_project_member(project_id)
    WHERE role = 'LEADER';

CREATE INDEX IF NOT EXISTS idx_office_project_member_user
    ON office_project_member(user_id, project_id);

-- 项目参考图片：[{"url": "/uploads/images/xxx.png", "name": "参考"}]
ALTER TABLE office_design_project
    ADD COLUMN IF NOT EXISTS reference_images JSONB
    NOT NULL DEFAULT '[]'::jsonb;
