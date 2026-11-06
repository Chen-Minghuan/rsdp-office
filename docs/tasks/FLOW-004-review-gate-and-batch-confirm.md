# FLOW-004 空间复核确认门禁与一键复核

状态：已实现并通过专项验证
更新日期：2026-11-06
依据：用户最新要求——空间识别后必须确认复核才可以进入家具排版；提供一键复核按钮；单个复核已实现（FLOW-001）；复核确认后的状态显示与「可用」统一。

本任务取代 FLOW-002 的「复核仅提示、不拦截排版」口径；FLOW-002 的其余结论（未知比例/无效轮廓拦截、不伪造人工确认）继续有效。

## 范围

- **进入排版门禁**：前端 `layoutEntryBlocker`（= 几何可排版 `layoutBlocker` + 全部空间持有 `reviewConfirmation.status === 'CONFIRMED'`），作用于核对页页脚按钮、URL 直达（`canvas.vue` 同步/参数剥离）、`store.enterDesign` / `setCanvasMode` / `advance` 与初始化恢复。后端在 `saveCanvas` 的「总览 → 排版」进入转换上由 `OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed` 强制核验（凭据经 `maintainSpaceReviewConfirmations` 维护后判定，伪造无效）；已在排版模式内的继续保存不受此门禁约束，读取已存排版稿时凭据失效（STALE）降级回总览。
- **一键复核**：新增 `POST /api/v1/office/floors/{floorId}/spaces/review-confirm-all`，服务端基于当前权威画布对全部空间逐签确认凭据（与单个确认同一事务、同一快照/幂等规则）；已确认且几何未变的空间幂等跳过，不满足确认条件（未校准/轮廓无效）的空间逐个列入 `failed` 并附原因，不阻塞其他空间；有新增确认才追加一个新画布修订。「一键复核（N）」与「生成/查看空间模板」按钮放在右栏「识别空间」列表下方（左模板、右复核），确认后 toast 汇总成功与失败原因；画布页脚保留空间/约束/待复核统计与「进入家具排版」。
- **状态统一**：空间卡片徽标统一为一个复核状态——几何/比例问题显示「需修正」，服务端凭据 CONFIRMED 显示「可用」，STALE 显示「复核失效」，其余「待复核」；重叠/继承修正/识别来源等提示性信息降级为卡片内提示行，不再与复核状态并列成第二个徽标。页脚统计由「N 需复核」改为「N 待复核」。
- 边界重叠等疑点空间允许人工确认（用户已对照原图判断），确认后重叠提示保留为提示行，不拦截排版。
- 门禁只挡家具排版（含直达与刷新恢复）；生成空间模板等其他功能不受影响。
- 空间模板对话框支持「点击冒出定位」：点击空间列表行或平面图色块，在点击处冒出「定位到画布编辑」气泡，点气泡即关对话框、画布选中该空间并把视图居中放大到它；整层其他空间保持可见（不进入单空间聚焦模式）。点其他空间直接换气泡目标，点空白处收起（不阻塞二次点选）。仅当空间 ID 仍在当前设计稿中时冒出气泡，历史模板或副本空间不提供定位；尺寸/轮廓修改仍走画布既有编辑链路。
- 排版模式内编辑建筑约束等使凭据 STALE 后，当前会话可继续工作；重新进入排版（含刷新恢复）需重新复核确认。

## 验证

| 检查 | 实际结果与范围 |
| --- | --- |
| `mvn -f backend/pom.xml test` | 全量 261 运行 / 0 失败 / 3 跳过（3 项为既有数据库集成跳过）。`OfficeSpaceReviewServiceTest` 新增 6 项：批量确认一次修订逐签、跳过已确认+无法确认逐个列原因、幂等不增修订、严格 base/无空间拒绝、进入排版未确认拒绝与确认后放行、排版内继续保存不受阻但读取降级总览。 |
| `pnpm build`（frontend） | 通过；未运行独立 lint/typecheck。 |
| `pnpm e2e:space-review` | 通过；模拟 API。新增：未确认拦截直达地址与页脚按钮（灰化可点击弹引导）、页脚待复核计数、一键复核后徽标统一「可用」并可进入排版、重叠确认后保留提示不拦截、确认后刷新恢复排版、手画空间同样受门禁。顺带修复两处既有失败：排版不再默认选中功能模块后的品类显式点选、排版侧栏改名入口移除（随 LAYOUT-013 三段式改版下线，改名保留总览列表/画布标签两处）的过时断言。 |
| 其余进入排版的 UI 回归（全部模拟 API） | 全部通过：space-obstacles、adaptive-layout、layout-planning、preview-drag、layout-regions、auto-layout、aisle-channels（直接 node 运行）、furniture-modules、layout-references、reference-layouts、module-selection、meeting-layout、single-space、selected-space-entry、annotations、drawing-entry、custom-modules、multi-furniture、delete-modules、module-recommendations、render-preview、white-model、proposal-3d、proposal-layout、templates（含模板空间定位回画布断言）。 |
| `MOCK_API=1 pnpm e2e:smoke` | 通过（自建 3001 生产预览）；冒烟流程改为先一键复核再进入排版。 |
| `pnpm e2e:workflows` | 通过（自建 3001 生产预览，接口全拦截）。 |
| 差异及文档 | `git diff --check` 通过。 |

适配过程中一并修复的既有失败（均为排版面板/属性面板改版遗留，非本任务引入）：layout-references/reference-layouts/module-selection/multi-furniture/custom-modules/delete-modules 缺 `/api/auth/me` 模拟；reference-layouts 刷新后未重选模块；annotations 底图对照层与「便签」按钮改名；multi-furniture 旋转座椅净空断言停留在 2026-10-26 降级前语义；custom-modules 新建目录模块未显式点「自动」、freshContext 缺 auth 模拟；space-template 三处尺寸编辑仍用已移除的右栏属性面板选择器、「模板需更新」提示条断言（现由对话框「此模板与当前草稿不同」表达）。

e2e 模拟复核确认使用共享助手 `frontend/scripts/helpers/review-confirm-mock.mjs`（跟踪最近一次画布保存的空间、为单个/一键确认请求签发 CONFIRMED 凭据）；种子数据类脚本直接携带 `reviewConfirmation: { status: 'CONFIRMED' }`。模拟接口不维护几何快照比对，STALE 失效链路只由 Java 测试覆盖。

真实数据库链路、多用户并发与真实识别图纸的业务复核验收未执行。
