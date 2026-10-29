# FLOW-001 空间确认与版本失效（首期部分）

2026-10-15 规则更新：用户取消逐空间确认门槛。复核只提示、按需修改后直接排版，当前行为见 [FLOW-002](FLOW-002-advisory-space-review.md)。下文的逐空间确认描述/测试保留为此前实施记录。

2026-10-29 规则更新：按《开发 A 实施设计文档 V1.1》在 `office_canvas` 工作台上新增**可选**的空间复核确认（SPACE_REVIEW）——服务端签发凭据、几何快照比对失效，见文末「2026-10-29 office_canvas 复核确认（SPACE_REVIEW）」。它不是进入排版的门槛，不改变 FLOW-002 的提示语义。

状态：进行中
更新日期：2026-10-29
负责人：Codex；2026-10-29 复核确认由开发 A（Claude）实施
基线：当前工作区源码实施
关联：[数据协议](../spatial/api-storage-contract.md)、[确认失效](../spatial/confirmation-rules.md)、[实施方案](../../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)、《RSDP-Office 开发 A 实施设计文档 V1.1》（仓库外）、[PROJECT-004](PROJECT-004-project-members.md)

## 目标与验收

实现 Spatial Scene 首期最小闭环：单房间导入/空稿 → 校准 → 多边形编辑 → 保存 → 刷新读取 → 空间确认 → 再修改使确认失效。

- [x] 后端 DesignScene 存储迁移（7 张新表）。
- [x] 5 个目标路由：创建、读取、保存草稿、应用校准、确认。
- [x] 保存/校准/确认带 baseRevision 与版本冲突 409。
- [x] 确认后修改使相关空间确认失效。
- [ ] 幂等回执完整实现（表已建，服务层仅预留）。
- [ ] 全稿删除/关系/质量处理完整规则。
- [ ] 前端写队列与 409 冲突合并 UI。
- [ ] 端到端集成测试（依赖数据库运行）。

## 实施步骤

1. 落地 `floor_plan_document/page/source_scene/design_scene/design_revision/calibration/confirmation/operation_receipt` 表。
2. 实现 `SpatialGeometryService`（多边形面积、矩形 frame、顶点编辑）与 `SpatialCalibrationService`（sourceToWorld 矩阵）。
3. 实现 `SpatialSceneService`：创建、保存、校准、确认、读取。
4. 修复旧 `FloorPlanService.confirmRooms` 保留 polygon 并按多边形计算面积。
5. 前端新增 `SpatialCanvas.vue`、`SpatialInspector.vue`、spatial store 与 API。
6. 新增 `pages/spatial-canvas.vue` 演示页。

## 决策与过程

| 日期 | 决定 | 依据 | 影响 |
| --- | --- | --- | --- |
| 2026-10-14 | 新增独立 spatial 表，不立即删除旧 floor_plan_analysis | 保留旧入口兼容，降低一次性迁移风险 | 新旧表并存，旧入口后续改造 |
| 2026-10-14 | 前端保留旧 CanvasBoard，新增 spatial-canvas 页 | 避免一次性重构整页交互 | 新旧页面并存，逐步迁移 |
| 2026-10-14 | 首期不实现 PDF 多页拆分与完整幂等回执 | 聚焦最小闭环 | 后续 FLOW-001 扩展 |

## 验证记录

| 日期 | 版本/环境 | 命令/操作 | 结果 | 未覆盖 |
| --- | --- | --- | --- | --- |
| 2026-10-14 | 工作区 | `mvn -f backend/pom.xml test` | 通过（含新增 geometry/calibration 测试） | 未运行数据库集成测试 |
| 2026-10-14 | 工作区 | `pnpm build`（frontend） | 通过 | mock E2E 未新增用例 |
| 2026-10-14 | 工作区 | `git diff --check` | 待执行 | - |

## 交付与下一步

- 后端：新增 spatial 模块、修复 confirmRooms 面积/多边形。
- 前端：新增 spatial 画布、检查器、状态管理。
- 下一步：补齐幂等回执、数据库集成测试、前端 409 冲突处理、端到端闭环用例。

## 2026-10-29 office_canvas 复核确认（SPACE_REVIEW）

按《开发 A 实施设计文档 V1.1》在既有 `office_canvas` 工作台链路上实现可选的空间复核确认，与 DesignScene 首期确认（上文）相互独立：DesignScene 本轮只读不改。

### 契约与行为

- 接口：`POST /api/v1/office/floors/{floorId}/spaces/{spaceId}/review-confirm`，请求体 `{"baseCanvasRevision": N}`；`baseCanvasRevision` 必须等于服务端当前画布修订（严格相等，否则 400「画布版本已过期（当前修订 N），请刷新后重新确认」）。
- 前置校验：楼层存在、项目成员访问（复用 PROJECT-004 的 `requireFloorAccess`）、工作区 advisory lock、目标空间存在、空间几何有效（复用 `OfficeCanvasReviewPolicy.requireZoneValid`：毫米单位、≥3 顶点、无退化边/自交、面积为正）。
- 通过后在空间对象写入服务端凭据 `reviewConfirmation`：`status=CONFIRMED`、`confirmedCanvasRevision`=当前修订、`confirmedByUserId`（认证关闭时为空）、`confirmedAt`，并追加一行新画布修订（revision+1）。
- 快照比对失效（不引入 SHA 指纹表）：服务端在确认时保存 `confirmedGeometry` 白名单字段快照（`id/zoneType/reviewUnit/mmPerSourceUnit/actualWidthMm/actualDepthMm/polygon/x/y/w/h/obstacles`；名称/备注/颜色不影响确认）。此后每次 `saveCanvas` 由服务端重建各空间的 `reviewConfirmation`——无服务端记录的一律剥离（客户端不能伪造）；已确认且白名单几何变化 → `STALE`；`STALE` 且几何改回与快照一致 → 恢复 `CONFIRMED`。数字按数值等价比较（1200 与 1200.0 相同）。
- 幂等：已确认且几何未变时重复确认直接返回当前凭据，不新增修订；不引入 clientChangeId。
- 前端：空间核对列表展示「已确认复核 / 复核失效」徽标与「确认复核 / 重新确认复核」按钮（仅毫米单位空间可点）；确认前先同步保存画布以冲刷防抖队列，再用返回的 `canvasRevision` 推进本地修订；`_persistToApi` 用 `saveCanvas` 返回值合并服务端凭据，避免旧确认状态回写。

### 决策与过程

| 日期 | 决定 | 依据 | 影响 |
| --- | --- | --- | --- |
| 2026-10-29 | 定案在 `office_canvas` 实现，DesignScene 只读 | 工作台实际权威链路是 office_canvas，避免双写 | DesignScene 确认接口保持原状 |
| 2026-10-29 | 几何快照白名单比对替代 SHA-256 指纹 | 快照可直接排查差异字段，免维护指纹算法与盐 | `OfficeSpaceReviewSupport.snapshot/jsonEquivalent` 为唯一比对入口 |
| 2026-10-29 | 普通 `saveCanvas` 仍按客户端递增修订保存，严格 base 校验只用于复核确认 | 全量保存锁会阻塞既有防抖写队列，超出本任务范围 | 后续任务评估保存队列的严格版本锁 |
| 2026-10-29 | 不耦合开发 B 未落地的布局边界数据 | 边界契约未定型 | 白名单后续可扩展 |

### 验证记录

| 日期 | 版本/环境 | 命令/操作 | 结果 | 未覆盖 |
| --- | --- | --- | --- | --- |
| 2026-10-29 | 工作区 | `mvn -f backend/pom.xml test` | 通过：221 运行 / 0 失败 / 3 跳过；含新增 `OfficeSpaceReviewServiceTest`（签发凭据+新修订、严格 base 不符文案、幂等重复确认、空间缺失/未校准/无画布拒绝、saveCanvas 剥离伪造凭据、几何变更转 STALE、仅改名保持、改回恢复） | 未跑真实数据库集成 |
| 2026-10-29 | 工作区 | `pnpm build`（frontend） | 通过 | - |
| 2026-10-29 | 工作区 | `pnpm e2e:space-review`（自建预览服务，模拟 API） | 通过 | 复核确认按钮未加专项 e2e 断言 |
| 2026-10-29 | 工作区 | `MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口预览服务） | 通过 | UI 为模拟 API |
| 2026-10-29 | 工作区 | `git diff --check` | 通过 | - |

### 交付与下一步

- 后端：`OfficeSpaceReviewService`、`OfficeSpaceReviewSupport`、`OfficeCanvasReviewPolicy.requireZoneValid` 抽取、`OfficeController` 复核确认端点、`OfficeService.saveCanvas` 服务端凭据维护。
- 前端：`SpaceReviewConfirmation` 类型、`confirmSpaceReview` API 与 store 动作、核对列表徽标与按钮。
- 下一步：复核确认的 e2e 专项断言；普通保存队列的严格版本锁评估；确认记录与方案快照/效果图失效链路的衔接（当前复核确认独立于排版确认与方案确认）。
