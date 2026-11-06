-- TENANT-001: 工作区（经销商）隔离层与角色扩展
-- 只建表与字段；旧项目 workspace_id 为空，保持 PROJECT-004 成员判定，补录留待后续。

-- 经销商工作区：一个经销商主号（office_user.role='DEALER'）恰好拥有一个工作区。
CREATE TABLE IF NOT EXISTS office_workspace (
    workspace_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL
        REFERENCES office_user(user_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 一个主号至多一个工作区。
CREATE UNIQUE INDEX IF NOT EXISTS uk_office_workspace_owner
    ON office_workspace(owner_user_id);

-- 账号归属工作区（系统管理员与历史账号为空）与来源标记。
-- source 一期不参与鉴权：DEALER_STAFF=经销商员工，FACTORY_SALES=厂家业务员。
ALTER TABLE office_user
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64)
        REFERENCES office_workspace(workspace_id);
ALTER TABLE office_user
    ADD COLUMN IF NOT EXISTS source VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_office_user_workspace
    ON office_user(workspace_id);

-- 项目唯一归属一个工作区；旧项目为空，由后续补录指定。
ALTER TABLE office_design_project
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(64)
        REFERENCES office_workspace(workspace_id);
CREATE INDEX IF NOT EXISTS idx_office_design_project_workspace
    ON office_design_project(workspace_id);

-- 操作审计：成员替换、开户、建工作区等关键授权动作。
CREATE TABLE IF NOT EXISTS office_audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    actor_user_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_office_audit_log_target
    ON office_audit_log(target_type, target_id, created_at);
