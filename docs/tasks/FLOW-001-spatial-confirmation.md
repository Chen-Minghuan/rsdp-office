# FLOW-001 空间确认与版本失效（首期部分）

2026-10-15 规则更新：用户取消逐空间确认门槛。复核只提示、按需修改后直接排版，当前行为见 [FLOW-002](FLOW-002-advisory-space-review.md)。下文的逐空间确认描述/测试保留为此前实施记录。

状态：进行中
更新日期：2026-10-14
负责人：Codex
基线：当前工作区源码实施
关联：[数据协议](../spatial/api-storage-contract.md)、[确认失效](../spatial/confirmation-rules.md)、[实施方案](../../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)

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
