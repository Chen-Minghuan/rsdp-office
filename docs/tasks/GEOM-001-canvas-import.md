# GEOM-001 识别场景与画布准确对应

状态：部分实现；本文件保留既有实现/验证记录，未代表完整目标契约已验收。
2026-10-13 文档审阅补充：PNG 记录使用 mock，不能作为真实识别验收；本轮未重新执行下列构建或接口操作。
统一验收与缺口见 [契约任务](GEOM-001-scene-canvas-contract.md) 和 [首期实施方案](../../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)。

## 目标

使 CAD/PDF/PNG 识别结果带着真实多边形、底图、未命名空间和毫米坐标进入无限画布，替代原来只导入矩形包围盒的方式。

## 实现范围

- 后端：新增 `floor_plan_analysis`、`floor_plan_room`、`floor_plan_scene_revision`、`floor_plan_furniture` 表及对应实体/Mapper/DTO/Controller/Service。
- 接口：
  - `POST /api/v1/floor-plan/analyze`：上传 image/pdf/cad，创建分析任务。
  - `GET /api/v1/floor-plan/{analysisId}`：查询分析详情与房间列表。
  - `PUT /api/v1/floor-plan/{analysisId}/rooms`：人工校正/确认空间。
- 前端：新增 `types/floorPlan.ts`、`composables/useFloorPlanApi.ts`、`utils/canvasCamera.ts`、`utils/floorPlan.ts`。
- 画布：改造 `CanvasBoard.vue` 支持底图叠加与多边形房间渲染；改造 `pages/index.vue` 与 `pages/canvas.vue` 使用新的 FloorPlan 流程。
- 启动：`SchemaInitializer` 在启动期执行 `db/schema.sql`，确保新表存在。

## 验证

- `mvn -f backend/pom.xml test` 通过。
- `pnpm build`（frontend）通过。
- PNG 接口链路（mock）：`POST /api/v1/floor-plan/analyze` → `GET /api/v1/floor-plan/{analysisId}` 返回演示多边形房间、`drawingBounds`、`geometrySource=ai_vision`、质量提示；该来源字段不证明真实识别。
- 确认接口：`PUT /api/v1/floor-plan/{analysisId}/rooms` 后房间状态变为 `CONFIRMED`，`analysis` 状态变为 `confirmed`。

## 已知待完善

- 后端确认重建房间尚未保存多边形，面积按宽×深计算；固定 SVG 映射和矩形对象坐标尚未统一，尺寸输入没有同步轮廓。
- CAD 真实样本验证需要 `.NET cad-parser` 服务（端口 8091）启动。
- 前端尚未实现基于 `drawingBounds` 的精确底图对齐比例尺校准交互。
- `floor_plan_furniture` 表已创建但未在前端消费。
- 未实现空间确认后的版本失效与下游排版确认流程（GEOM-002 / FLOW-001）。

## 代码入口

- 后端：`backend/src/main/java/com/rsdp/officeproduct/importer/service/FloorPlanService.java`
- 前端：`frontend/app/components/OfficeCanvas/CanvasBoard.vue`、`frontend/app/pages/index.vue`、`frontend/app/pages/canvas.vue`
