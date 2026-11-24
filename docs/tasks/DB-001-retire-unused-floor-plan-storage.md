# DB-001 退役闲置图纸识别表和字段

更新日期：2026-11-24。状态：实现并完成隔离验证，已在本机开发库执行。见 [本机清理记录](DB-003-local-office-cleanup.md)。

## 存量与调用核对

- 本机开发库有 70 条 `floor_plan_analysis`。没有记录填写 `confirmed_rooms` 或 `layout_json`，`layout_revision` 全为默认值 1。旧表 `floor_plan_furniture` 有 0 条记录，无引用它的外键，也未发现依赖旧字段或旧表的视图。
- 仓库内的 `FloorPlanFurnitureMapper` 没有调用。原图家具参考由 [识别接口](../../backend/src/main/java/com/rsdp/officeproduct/importer/office/controller/OfficeLayoutReferenceController.java) 直接返回识别结果。图纸版本、房间轮廓和设计稿由 `floor_plan_scene_revision`、`floor_plan_room` 和 `office_canvas` 负责。
- 本机开发库本轮用于只读核对，共有 27 张业务表，含手动安装的 `office_space_template` 和 `office_layout_plan`。结构副本执行清理后为 26 张，两张扩展表均保留。24 张为本步骤完成时的默认库表数，后续清理见 [DB-002](DB-002-retire-unused-agent-messages.md)。

## 变更

从初始化 SQL 删除闲置家具表及三个旧列，删除对应 Java 实体、Mapper 及分析实体映射。分析详情响应保留原有兼容字段。个人收藏和项目收藏分别保留各自的表。

[启动迁移](../../backend/src/main/resources/db/migrations/20261124_remove_unused_floor_plan_storage.sql) 在同一事务内锁表、检查和清理。发现任何旧家具记录、非空旧 JSON、已填写且非默认的旧版本，或 PostgreSQL 拒绝移除仍被依赖的表或列时，启动迁移整体回滚。成功后可以重复启动。

## 验证

- Java 全量回归 345 项全部通过。真实 PostgreSQL 16 执行 Spring 启动迁移，验证旧库升级、重复执行、保留识别批次及版本与房间，以及有历史数据时回滚。
- 隔离 Office 后端 API 验证了新库建表、登录和项目授权、真实 CAD 解析、模拟 PNG/PDF 识别、空间确认和保存、排版与选品、方案核验和导出、报价权限、个人及项目收藏、本地商品及模拟 RSDP 投递。升级重启后保留前述业务记录。效果图历史接口通过，生成逻辑由 Java 测试覆盖。
- 前端构建通过。使用模拟 API 验证主流程冒烟、工作流、项目楼层、账号中心、报价、收藏、渐进识别、空间核对和效果图。相关脚本按当前权限与项目数据契约更新了测试夹具。
- 外部视觉、RSDP 商品及回执在隔离验证中使用模拟服务。生产存量、外部直连调用与真实生图未验收。
