# WORKSPACE-007 楼层删除

状态：已实现并通过构建、Java 全量测试与模拟 UI 验证；PostgreSQL 专项与真实业务验收待执行
日期：2026-11-28

## 目标

项目设置对话框（`ProjectSettingsDialog.vue`）楼层列表每行右侧提供删除按钮；点击弹出原生 `<dialog>` 确认弹窗（沿用 FavoriteFolderDialog 模式），明确提示操作不可恢复，确认后删除该楼层及其全部关联数据。

## 业务规则（用户确认）

1. **项目至少保留一个楼层**：删除最后一层被后端拒绝（`BusinessException("项目至少需要保留一个楼层")`），前端最后一层的删除按钮禁用并以 title 提示原因。
2. **历史报价单保留**：`office_quotation` / `office_quotation_line` 为历史快照（`floor_id`/`floor_name` 反范式存储、无 FK），不阻止删除也不随楼层删除；确认弹窗文案说明"历史报价单保留"。
3. **方案确认先软删后随楼层硬删**：`office_proposal_confirmation.floor_id` 为无级联 FK，随楼层删除前必须先清理该表；沿用 `resetProjectData` 惯例先置 `retired=true` 再在同一事务硬删。历史报价单 `proposal_confirmations_json` 中的确认 ID 仅为快照出处、无代码回读，悬空可接受。

## 数据清理范围（同一事务，子表→父表）

楼层 `floorId` 同时是画布/图纸等业务表 `project_id` 的隔离键。`OfficeProjectService.deleteFloor`（`@Transactional`，项目 EDIT 权限 + 楼层归属校验 + `workspaceLocks.lockWorkspace(floorId)`）依次删除：

- 遗留空间链路：`floor_plan_design_scene`（按 `project_id`）→ 按 `scene_id` 删除 `floor_plan_operation_receipt`、`floor_plan_confirmation`、`floor_plan_calibration`、`floor_plan_design_revision`；`floor_plan_document`（按 `project_id`）→ 按 `asset_id` 收集 `floor_plan_page` → 按 `page_id` 删除 `floor_plan_source_scene`（其 `analysis_id` 引用识别批次，须先于批次删除）
- 识别链路：`floor_plan_analysis`（按 `project_id`）→ 按 `analysis_id` 删除 `floor_plan_scene_revision`、`floor_plan_room`
- `office_render_preview`、`office_space_template`、`office_cart`、`office_canvas`（均按 `project_id`）
- `office_room_scheme`（FK 引用楼层行）
- `office_proposal_confirmation`（FK 引用楼层行，先 retired 再硬删）
- 最后删除 `office_design_floor` 行并审计 `FLOOR_DELETED`

明确不删除：`office_quotation`/`office_quotation_line`（历史快照）、`image_assets` 与 `/uploads` 文件（共享注册表，孤儿引用可接受，GC 列为后续）、`office_project_favorite_item`（按真实项目 ID）。

新增 mapper 方法：`RenderPreviewMapper.deleteByProject`、`SpaceTemplateMapper.deleteByProject`、`OfficeProposalConfirmationMapper.deleteByFloor`、`FloorPlanOperationReceiptMapper/FloorPlanCalibrationMapper/FloorPlanDesignRevisionMapper.deleteBySceneIds`（foreach IN，空列表跳过）。

## 前端

- `useWorkspaceApi.deleteFloor(projectId, floorId)` → `DELETE /api/v1/office/projects/{projectId}/floors/{floorId}`。
- `workspace` store `deleteFloor(floorId)`：调用 API 后从 `project.floors` 移除；删除的是当前激活楼层时 `useOfficeStore().$reset()` 并 `activateFloor(剩余首层)`（复用其选中记录写入与 `?floorId=` 路由替换）。浏览器侧清理（尽力而为，不阻塞）：office store 新增 `removeDraft(workspaceId)` 删除该楼层 localStorage 画布草稿；`utils/floorClientData.ts` 的 `clearFloorClientData` 额外清理 IndexedDB `rsdp-office-files/floorFiles` 中该楼层的图纸缓存。
- `ProjectSettingsDialog.vue`：楼层行容器由 `<label>` 改为 `<div>`（避免删除按钮点击触发输入聚焦），行尾 trash 按钮（`canEdit` 且非最后一层可点）；确认弹窗为嵌套原生 `<dialog>`（warning 图标红盒警告、红色确认按钮、删除期间禁用取消与关闭、inline 错误提示）。删除在确认时立即执行，不并入 `保存修改` 批处理（不可逆且与重命名独立，沿用收藏夹删除先例）。头部说明补充"删除楼层会同时删除该楼层的全部设计数据"。

## 验证记录（2026-11-28 本机）

- `mvn -f backend/pom.xml test`：523 项、0 失败、0 错误（PostgreSQL 门控测试因未配置 `RSDP_TEST_POSTGRES_URL` 跳过，不计为数据库验收）。`OfficeProjectServiceTest` 新增 4 用例：删除清理链路（verify 各 mapper 清理调用与审计）、最后一层拒绝、跨项目/未知楼层拒绝、无 EDIT 权限拒绝。
- 新增 `OfficeFloorDeletePostgresTest`（RSDP_TEST_POSTGRES_URL 门控）：真实库验证六条新增删除语句可执行、方案确认删除后楼层行 FK 不再阻止删除——本机未配置环境变量，跳过未执行。
- `pnpm build` 通过。
- `pnpm e2e:project-floors`（自起 .output 预览服务、拦截 API）：新增删除楼层用例——三个楼层均有删除按钮；删除激活的 3F 经确认弹窗后 URL 切到 OFLR-1、楼层树不再显示 3F、`rsdp-office-workspace-v1:MOCK-USER` 选中记录切换、OFLR-3 本地草稿清理；再删 2F 后最后一层的删除按钮禁用且 title 为"项目至少需要保留一个楼层"。通过。
- `pnpm e2e:workflows`、`MOCK_API=1 pnpm e2e:smoke` 通过。
- 未做真实后端联调与真实角色页面验收。

## 后续观察项

- `/uploads` 上传文件与 `image_assets` 的孤儿数据 GC。
- 项目级删除入口尚无，删除整个项目的清理链路可在本任务基础上扩展。
