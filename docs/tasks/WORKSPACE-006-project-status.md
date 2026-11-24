# WORKSPACE-006 项目阶段与工作台分类

状态：已实现并通过构建、模拟 UI 和隔离 PostgreSQL 验证；真实角色页面业务验收待确认
日期：2026-11-24

## 决策变更（2026-11-24）：阶段改为按方案确认派生

产品要求看板"交付中"和列表"项目阶段"列反映真实业务进度，取代人工维护。变更内容：

- 项目阶段不再人工维护：项目全部楼层在 `office_proposal_confirmation` 存在未失效（`retired=false`）记录 → `DELIVERING`，否则 → `DESIGNING`；无楼层项目为设计中。判定为读时派生，不校验确认快照的 `canvas_revision`/`fingerprint` 是否与当前画布一致。
- 移除 `COMPLETED` 阶段、`PATCH /api/v1/office/projects/{projectId}/status` 端点、`canManageStatus` 字段和前端阶段下拉；列表阶段列只读。`office_design_project.status` 列保留但不再读取，新建项目仍写入默认值 `DESIGNING`。工作台保留"已完成"看板占位（禁用、计数显示 —），业务口径待后续定义后再接入数据。
- 列表接口在 `OfficeProjectService.listProjects` 用一次 `confirmedFloorIds` 批量查询派生全部项目阶段；单项目写接口的返回同样走派生。

验证：Java 测试更新为派生规则用例（全部楼层确认 → 交付中、任一缺失或失效 → 设计中、确认失效回退），`e2e-project-status.mjs` 改为只读阶段、计数、筛选与窄屏断言。隔离 PostgreSQL 专项改为用 SQL 直接改写阶段列后验证改名不覆盖，不再走已删除的 `updateStatus`。

实际执行（2026-11-24 本机）：`mvn -f backend/pom.xml test`（JDK 21）360 项、0 失败、0 错误、9 跳过（含未配置 `RSDP_TEST_POSTGRES_URL` 而跳过的 PostgreSQL 专项，不计为数据库验收）；`pnpm build` 通过；本地 dev server（3001）上 `e2e-project-status`、`MOCK_API=1 e2e:smoke`、`e2e:workflows` 均通过（均拦截 API）。未执行真实后端联调验收。

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

## 与远程主分支整合（2026-11-24）

保留新版五入口导航、项目总览与统一紫色主题，阶段统计仅显示在项目列表，不恢复已移除的固定海报。任务编号顺延为 WORKSPACE-006，避免与既有 WORKSPACE-005 项目总览任务重号。合并后的前端构建、项目阶段、项目楼层和工作流模拟 UI 回归通过；Java 363 项、0 失败、9 跳过。本轮未配置临时 PostgreSQL，数据库专项沿用前述已实际执行的结果，不将跳过计为数据库验收。
