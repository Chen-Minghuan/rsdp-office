-- 恢复人工维护项目阶段时，存量项目沿用切换前按方案确认派生的显示阶段：
-- 项目存在楼层、且全部楼层在 office_proposal_confirmation 存在未失效(retired=false)记录
-- → DELIVERING，其余保持 DESIGNING。
-- 仅在标记不存在时执行一次（启动脚本每次重跑，避免覆盖负责人此后的人工修改）；
-- 且只改写仍为默认 DESIGNING 的行，不动已存在的人工值。
CREATE TABLE IF NOT EXISTS office_data_migration (
    script varchar(128) PRIMARY KEY,
    applied_at timestamp NOT NULL DEFAULT now()
);

UPDATE office_design_project p
SET status = 'DELIVERING'
WHERE p.status = 'DESIGNING'
    AND NOT EXISTS (SELECT 1 FROM office_data_migration m WHERE m.script = '20261126_project_status_backfill')
    AND EXISTS (
        SELECT 1 FROM office_design_floor f WHERE f.project_id = p.project_id
    )
    AND NOT EXISTS (
        SELECT 1 FROM office_design_floor f
        WHERE f.project_id = p.project_id
            AND NOT EXISTS (
                SELECT 1 FROM office_proposal_confirmation c
                WHERE c.floor_id = f.floor_id AND c.retired = false
            )
    );

INSERT INTO office_data_migration(script) VALUES ('20261126_project_status_backfill')
ON CONFLICT (script) DO NOTHING;
