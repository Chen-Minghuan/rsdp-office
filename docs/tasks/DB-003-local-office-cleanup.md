# DB-003 本机 Office 库清理

2026-11-24 在本机 `office-postgres` 的 `office` 库完成。

## 数据和备份

- 清理前 `public` 共 27 张表，五个遗留 `test_layout_plan_*` schema 各有一张测试表。两张旧表 `floor_plan_furniture` 和 `office_agent_message` 当时均为空。
- `office_layout_plan` 共有 5 条历史记录，其中 1 条已确认、4 条草稿。未发现入向外键或依赖视图，存量画布、效果图和空间模板 JSON 也未发现对旧方案 ID 的引用。
- 清理前用 `pg_dump -Fc` 备份整库，在隔离 PostgreSQL 将备份完整恢复并执行清理演练。备份保存在本机 Application Support 下的 `rsdp-office/db-backups` 私有目录。

## 执行和验证

- 对照归档校验旧方案和五张测试表的记录摘要，在同一个事务复用 [旧家具识别清理迁移](../../backend/src/main/resources/db/migrations/20261124_remove_unused_floor_plan_storage.sql) 和 [旧助手清理迁移](../../backend/src/main/resources/db/migrations/20261124_remove_unused_agent_messages.sql)，同时用 `RESTRICT` 移除已归档的旧排版方案表及五个残留测试 schema。
- 本机 8082 原运行包依赖已退役字段和助手表。切换到已在隔离副本启动并验证的后端版本，保留本机现有 RSDP 的读取契约。隔离登录、项目、画布、购物车、图纸识别详情和模板列表均通过实际 API 读取。
- 本机 8082 健康检查通过。`public` 实测 24 张表，包括标准的 23 张表和 `office_space_template`。图纸分析及版本各 70 条、画布 333 条、购物车 43 条、空间模板 1 条，清理前后行数一致。项目、楼层和账号行数也一致。
