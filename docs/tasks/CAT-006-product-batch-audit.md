# CAT-006 商品批量审核与一键全部通过

## 目标

- 商品审核页支持勾选多件待审核商品，批量通过或批量驳回。
- 支持一键将当前全部待审核商品审核通过，操作前需确认。

## 实现

- 前端 `products/audit.vue` 增加勾选列与表头全选（含半选态），选中后出现批量通过/批量驳回/清空选择操作；右上角新增“一键全部通过”按钮，弹窗确认后才发起请求。
- 批量驳回复用驳回弹窗，多件时共用同一条驳回原因；批量操作期间禁用勾选与按钮，避免重复提交。
- 前端 API 新增 `POST /api/v1/products/audit/batch-approve`、`POST /api/v1/products/audit/batch-reject`、`POST /api/v1/products/audit/approve-all`。
- Java `ProductAuditService` 新增批量通过、批量驳回与一键全部通过；统一使用带 `audit_status = pending` 条件的批量更新，只处理仍为待审核的商品，已处理或不存在的记录计入跳过数量并返回前端提示，不只依赖 UI 禁用。
- 批量请求体要求商品 ID 列表非空，后端去重去空；空选择抛出业务异常。

## 验证

- `pnpm build`（`frontend/`）：通过。
- `mvn -f backend/pom.xml test`：通过，184 项（含新增 4 项批量审核测试）全部通过，3 项数据库环境未配置跳过。
- 新增 `ProductAuditServiceTest` 4 项：批量通过只更新 pending 并统计跳过、批量驳回保留原因、一键全部通过不带商品 ID 条件、空选择抛异常且不执行更新。
- 模拟接口浏览器验证（`node scripts/e2e-batch-audit.mjs`，前端开发服务 3000）：勾选 2 件批量通过、表头全选批量驳回（带原因）、一键全部通过（弹窗确认）共 11 项断言全部通过；该脚本拦截 API，不代表真实接口验收。
- `git diff --check`：通过。

## 未覆盖

- 尚未在真实数据库与运行中的后端上执行批量审核；需在真实环境验证并发下跳过统计与列表刷新。
