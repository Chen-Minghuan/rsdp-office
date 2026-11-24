# WORKSPACE-005 项目阶段与工作台分类

状态：已实现并通过构建、模拟 UI 和隔离 PostgreSQL 验证；真实角色页面业务验收待确认
日期：2026-11-24

## 目标

恢复全部项目、设计中、交付中、已完成四个统计卡片，按当前账号可访问项目的真实阶段统计并筛选。项目阶段独立于空间/排版确认；新项目和历史项目默认设计中，不以预算或画布状态猜测是否交付、完成。

## 契约与权限

- `office_design_project.status` 保存 `DESIGNING / DELIVERING / COMPLETED`，数据库默认值和约束同时生效。
- 项目列表返回 `status` 和 `canManageStatus`。主号仅管理本工作区项目，项目负责人可管理本人项目；普通协作者、其他工作区账号和系统管理员不能据角色绕过校验。
- `PATCH /api/v1/office/projects/{projectId}/status` 接收并返回 `{status}`，只更新阶段和更新时间，记录审计。不自动确认方案或改写画布，允许人工纠正阶段。
- 工作台统计包含全部可访问项目，不随关键词变化；分类与关键词、排序叠加，空分类显示空状态。修改失败保留原阶段并提示，刷新后从后端读取。

## 验证记录

- Java：`mvn -f backend/pom.xml -Djava.awt.headless=true test`，363 项、0 失败、0 错误、7 跳过。覆盖新项目默认阶段、三个有效值及无效值、主号/负责人可修改、无查看权负责人/协作者/跨工作区账号/管理员拒绝，以及状态接口响应。
- 设置 `RSDP_TEST_POSTGRES_URL`、`RSDP_TEST_POSTGRES_USER`、`RSDP_TEST_POSTGRES_PASSWORD` 后，`OfficeProjectStatusPostgresTest` 在临时 PostgreSQL 独立 schema 实际执行通过：历史项目回填、新项目默认值、迁移重复执行、状态持久保存、非法状态数据库拒绝、普通改名不覆盖并发阶段变更。未提供测试连接时该专项跳过，不计为迁移验收。
- 前端 `corepack pnpm build` 通过。`node scripts/e2e-project-status.mjs`（`BASE_URL` 指向测试预览）、工作流和项目楼层 UI 回归通过，均拦截 API。阶段专项覆盖统计、分类与搜索叠加、主号及负责人修改、普通成员只读、刷新保留、失败回退和 390px 窄屏；1600px 桌面与窄屏截图已核对。
- 本地前后端已重新构建启动：3001 工作台 HTTP 200，8082 健康检查 UP；本地存量项目阶段均为 `DESIGNING`。仅核验读数，不替用户修改项目阶段。未进行真实登录角色的端到端修改验收，未部署远程服务器。
- `git diff --check` 通过。当前完整冒烟已有选择职员桌超时的基线记录，本轮未重复运行。
