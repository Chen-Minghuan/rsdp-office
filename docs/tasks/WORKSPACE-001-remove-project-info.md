# WORKSPACE-001 删除项目信息表单与专用存储

日期：2026-10-17。状态：已编码并通过专项验证；当前使用库待用户启动新版后端迁移。

## 用户要求与范围

移除上传页项目信息（名称、建筑面积、预计人数、预算、整体偏好、说明），并删除后端对应接口、模型和 office_project 表。同步清理依赖这些字段的摘要与预算展示；单次效果预览中主动选择的风格参数保留。

现有 projectId 仅作为画布、识别、模板、选品和效果预览的隔离键保留，不改写已保存几何或重新生成 ID。移除指向 office_project 的外键，不级联删除业务数据；原项目行锁改为按隔离键获取 PostgreSQL 事务级 advisory lock，保持写入串行化。

## 实现

- 上传页删除整个资料面板并扩展图纸区域；侧栏、步骤条和模板名称使用图纸文件名。提交弹窗、总览与方案删除人数/预算/风格资料摘要，保留空间、商品金额与几何计算面积。
- 前端删除 Project 类型、状态、样例资料和 loadProject/saveProject；初始化以画布读取判断服务可用性，无需资料记录。新工作区不再自动填入示例，示例由用户显式加载。
- 浏览器恢复草稿时清除旧 project 和 submitted.projectContext，保留空间/家具/来源/稳定 ID；后续提交不再携带这些资料。
- 后端删除 OfficeProject、Mapper、资料 DTO 和 GET `/api/v1/office/projects/{projectId}`、POST `/api/v1/office/projects`、PUT `/api/v1/office/projects/{projectId}`。画布/识别/选品/效果预览不再创建或查询资料记录；识别仍校验隔离键有效性。
- 画布保存、模块选品和预览创建共用 OfficeWorkspaceLockMapper，以 PostgreSQL `pg_advisory_xact_lock(hashtextextended(projectId, 0))` 在事务中串行化同工作区写入，无需新增替代表。
- 新库 schema 和 Spatial 初始迁移不再创建/引用 office_project。新增 `20261017_remove_office_project.sql`，从系统目录查找指向该表的外键后逐一移除，再删资料表；不用 CASCADE，意外依赖使迁移失败，避免连带删除其他对象。
- SchemaInitializer 同一事务执行结构脚本和清理迁移，成功后提交，失败终止启动；DO 脚本整体执行，避免按内部分号拆分。重复启动幂等。

## 验证与结果

本次基于 main 的 `6579995` 后工作区验证，记录随本次功能提交保存。测试只在独立浏览器或唯一测试 schema 中运行。

| 检查 | 实际结果与边界 |
| --- | --- |
| `cd frontend && pnpm build` | 通过；日志 `/tmp/rsdp-remove-project-build.log` |
| `pnpm e2e:space-review` | 通过；验证资料面板/旧 API 消失、空稿不注入示例、旧草稿资料清除、空间保留及编辑/删除/刷新。mock API，日志 `/tmp/rsdp-remove-project-ui.log` |
| `pnpm e2e:templates` | 通过；模板保存/读取/副本/撤销/刷新/导出等 mock 回归，日志 `/tmp/rsdp-remove-project-templates.log` |
| `pnpm e2e:render-preview` | 通过；预览快照、等待/恢复、历史结果和失败重试等 mock 回归，日志 `/tmp/rsdp-remove-project-render.log` |
| `pnpm e2e:vision` | 通过；图片/PDF 上传预览、校准提示、选页和失败反馈 mock 回归，未调用百炼，日志 `/tmp/rsdp-remove-project-vision.log` |
| `BASE_URL=http://localhost:3000 MOCK_API=1 pnpm e2e:smoke` | 通过；上传→显式示例→排版→方案，mock 无商品候选，不能证明真实选品集成；截图 `/tmp/rsdp-remove-project-smoke/` |
| `BASE_URL=http://localhost:3000 pnpm e2e:workflows` | 未通过；既有商品详情按钮选择器“查看简约办公桌 2详情”超时（第 81 行），尚未运行后续设计断言；日志 `/tmp/rsdp-remove-project-workflows.log` |
| `RSDP_TEST_DATABASE_URL=jdbc:postgresql://localhost:5434/office mvn -f backend/pom.xml test` | 114 项，0 失败/错误/跳过；含资料路由 404、无资料保存画布和 3 项真实 PostgreSQL 测试，日志 `/tmp/rsdp-remove-project-java-db.log` |
| PostgreSQL 隔离测试 | 唯一 test_profile_removal_* schema 中执行实际迁移，确认删表、六类子表数据/ID/轮廓保留、新隔离键插入、重复迁移、新库不建资料表；真实 MyBatis mapper 验证同键阻塞/异键通过/事务释放。测试 schema 用后删除，不迁移当前业务 schema |
| 界面核对 | 已查看上传页 1440×900 截图：图纸区域完整展开，没有原资料栏和空白右栏 |
| 文档与差异 | 当前架构/需求/存储/启动说明同步；本地相对链接检查与 `git diff --check` 通过 |

## 部署状态与下一步

本轮不重启或停止用户的 8082 服务，当前业务库的 office_project 尚未删除。用户停止旧版 Java 并启动新版后，启动迁移自动删除旧资料表及外键；无需重新初始化 PostgreSQL、重建表或删除数据卷。不能在旧 Java 仍提供服务时单独删表，否则旧代码访问资料接口会出错。

单独验证过真实迁移脚本与新 schema；未以新版 Spring 进程对当前业务库运行启动迁移，正式更新后的上传/保存完整集成与用户业务验收仍待进行。CAD 服务未修改，本轮未运行 CAD 测试。
