# 开发进度与下一步

更新日期：2026-10-15。代码基线：`2413a93`，另有本地商品导入图片功能改动。
既有功能状态主要依据源码审阅；CAT-002 已运行 Java 测试、真实工作簿解析测试及前端构建。用户正式导入与补入后，当前数据库有 211 个商品、220 个规格；`16FMT001` 的图片说明不符问题已编码处理，用户补图及完整业务验收仍待验证。
已有代码统一记为部分实现或已编码待验证；文档验收单独记录。

## 1. 状态含义

| 状态 | 含义 |
| --- | --- |
| 待开始 | 需求已记录，尚未启动该任务 |
| 进行中 | 正在实现或检查，有明确下一步 |
| 部分实现 | 已有相关代码，但目标范围仍有缺口 |
| 已编码待验证 | 任务范围已编码，实际验证尚不完整 |
| 已验收 | 任务标准实际通过，并附环境、版本与证据 |
| 阻塞 | 明确缺少必要条件，说明解除条件 |

演示是数据/实现来源，不是完成状态。项目总体不填无统计依据的完成百分比。

## 2. 功能与工作项

| ID | 工作项 | 当前状态与证据 | 剩余工作/验收 | 优先级 |
| --- | --- | --- | --- | --- |
| DOC-001 | 需求、规则、规范与进度基线 | 已验收（文档）；本轮新增文档及 README 同步 | 文档检查通过；后续业务任务按对应标准另行验收 | P0 |
| CAT-001 | 商品入库与可恢复上下架 | 部分实现；已有导入、预览、手工录入、审核、编辑和删除代码；商品库已支持页码跳转、每页数量选择和批量永久删除（见 [CAT-004](tasks/CAT-004-product-pagination-hard-delete.md)）；供应商工作簿可转换为预览行，见 [CAT-002](tasks/CAT-002-workbook-image-ingest.md) | 真实数据库导入、独立上下架流程、刷新持久化、推荐过滤及旧方案处理；补充批量删除真实服务验收 | P0 |
| CAT-002 | 系统导入时自动提取内嵌图片 | 已编码待补图验收；普通图、组合图、WPS 图片和合并图片区解析；`16FMT001` 锚定图的说明编码错误已纳入候选，默认不选、需人工勾选；预览可逐行排除图片并选择主图，详见 [CAT-002](tasks/CAT-002-workbook-image-ingest.md) | 用户在原端口复核并补入 `16FMT001` 两张图，核对商品详情、其他冲突图片、重复/失败路径及部署持久存储 | P0 |
| CAT-003 | 供应商商品尺寸与预览适配 | 已编码待完整验收；F 列合并说明、浮点尾数归一、包装字段、图片预览和品类码清理已完成；双宽度拆分形成 220 条规格；用户补入单行失败商品后数据库已有 211 商品、220 规格，详见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 核对缺图商品补图、价格、规格和正式导入结果；完整尺寸推荐另按 RECOMMEND-001 实施 | P0 |
| RECOG-001 | CAD 真实识别链路 | 已编码待验证；上传页 → Java `/api/v1/cad/parse` → CAD 服务 | 真实办公 DWG/DXF、目标选择、单位、未命名空间和失败路径验收 | P0 |
| RECOG-002 | PDF/PNG 真实识别 | 部分实现；已有 vision 调用，未配置服务时返回演示结果 | 真实服务接入、PDF 页选择、图像校准、来源标识及样本验收 | P0 |
| GEOM-001 | 识别场景与画布准确对应 | 已编码待验证；新增 `floor_plan_analysis/room/scene_revision` 表、`/api/v1/floor-plan/**` 接口、前端 `useFloorPlanApi` 与多边形/底图渲染；PNG 真实样本验证通过（mock 数据），CAD 样本待 cad-parser 启动后验证 | CAD 真实 DWG/DXF 样本验证；未命名空间在前端列表中展示；底图叠加比例校准 | P0 |
| GEOM-002 | 无限画布与空间尺寸双向编辑 | 部分实现；已有平移/缩放、区域拖动、校准与草稿保存 | 无界世界坐标、边界/顶点编辑、宽深输入改变几何、面积联动、撤销/重做与恢复 | P0 |
| FLOW-001 | 空间/排版/方案确认及版本失效 | 待开始；现有模式切换不构成完整确认模型 | 全部设计空间确认、阶段校验、返回修改、受影响下游失效及后端检查 | P0 |
| LAYOUT-001 | 独立家具排版 | 部分实现；已有排版算法，布局直接绑定商品且基于矩形 px | 未绑定商品的布局需求、世界单位、异形边界/碰撞/通道校验、人工调整及布局确认 | P1 |
| RECOMMEND-001 | 已确认排版后的真实商品推荐 | 部分实现；商品候选按用途过滤，侧栏与排版并列 | 尺寸/类型/数量匹配、上架过滤、变体映射、选择复核和按版本返回结果 | P1 |
| RENDER-001 | 已确认方案效果图 | 待开始；未发现完整生成任务接口/流程 | 服务选型、方案快照、资源与视角、失败重试、历史/过期结果和图像对应验收 | P2 |
| QA-001 | 分层验证与持续检查 | 部分实现；已有构建、UI E2E、Java/CAD 测试入口 | 增加几何/流程/商品测试和真实集成样本；统一脚本与 CI；处理金样本未执行判定 | 随各项实施 |

P0 是后续设计流程的数据与业务基础；P1 依赖空间与确认基础；P2 依赖确认方案。
优先级是实施顺序，不表示 P2 不在目标范围。

## 3. 关键源码证据

- 商品：[接口](../backend/src/main/java/com/rsdp/officeproduct/importer/controller/ProductImportController.java)、[审核服务](../backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductAuditService.java)、[商品页](../frontend/app/pages/products/index.vue)。
- 识别与导入：[上传页](../frontend/app/pages/index.vue)、[API 客户端](../frontend/app/composables/useOfficeApi.ts)、[vision 客户端](../backend/src/main/java/com/rsdp/officeproduct/importer/service/VisionRecognitionClient.java)。
- 当前画布模型：[类型](../frontend/app/types/office.ts)、[画布](../frontend/app/components/OfficeCanvas/CanvasBoard.vue)、[尺寸校准](../frontend/app/components/OfficeCanvas/OverviewSidebar.vue)。
- 排版：[算法](../frontend/app/utils/layout.ts)、[排版侧栏](../frontend/app/components/OfficeCanvas/DesignLayoutSidebar.vue)、[商品候选侧栏](../frontend/app/components/OfficeCanvas/DesignSidebar.vue)。
- 测试：[UI 工作流](../frontend/scripts/e2e-workflows.mjs)、[CAD 金样本](../backend/cad-parser/tests/RsdpCadParser.Tests/GoldenFileTests.cs)。

## 4. 当前最需要解决的差距

1. 识别原始多边形与当前矩形 zone 之间存在信息损失，且未命名空间尚未一同导入。
2. 上传页 `scale` 是 px/mm，但 `pixelToMeter` 写为 `1000 / scale`；排版模块将其作为 m/px 使用，量纲不一致。需在 GEOM-001 中统一换算并验证真实家具尺寸。
3. 现有“校准尺寸”修改比例属性，不能满足输入宽深后空间几何同步变化。
4. 当前家具排版直接选商品；完整的“空间确认 → 排版确认 → 推荐”顺序和版本约束尚未实现。
5. 商品 status 与 auditStatus 分开存在，但审核不是上下架；不能凭已有 approve/reject 接口认为上下架完成。
6. 图片/PDF 演示返回与真实能力必须区分；原型说明和服务抽取历史不代替当前功能验收。

## 5. 建议执行顺序

先做 [GEOM-001](tasks/GEOM-001-scene-canvas-contract.md)：统一识别结果、毫米场景与画布的契约，解决对齐、轮廓与比例。
CAT-001 可独立开发；RECOG-001/002 的实际验收依赖对应服务和样本。
随后完成 GEOM-002 和 FLOW-001，再推进 LAYOUT-001、RECOMMEND-001，最后 RENDER-001。
每项业务任务同步补 QA-001 对应验证，不把所有验证留到最后。

未排定日期和工期；按任务实际完成与依赖更新，而非提前承诺进度。

## 6. 验收记录

| 任务 | 代码/文档版本 | 环境与验证 | 结论 |
| --- | --- | --- | --- |
| DOC-001 | 基于 `3f8f1eb` 工作区新增规则与开发文档 | 10 个规则/文档文件、60 个本地链接、代码块/换行/空白、业务工作项与 `git diff --check` 检查通过，见任务记录 | 已验收（文档） |
| CAT-002 | 基于 `2413a93` 的本地工作区，见 [任务记录](tasks/CAT-002-workbook-image-ingest.md) | Java 测试、指定真实工作簿解析测试、前端构建与 `git diff --check` 通过；真实服务预览 211 行有效、205 张图，全部跳过确认 0 写入 | 已编码待集成验收；图片目录已由用户清空，旧映射 JSON 仍在 |
| CAT-003 | 本地工作区，见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 2026-10-15 双宽度拆分后 Java 18 项测试（含真实工作簿只读解析）、前端构建和 `git diff --check` 通过；此前 8083 预览和旧 UI 工作流记录见任务文件；本轮未启动服务或运行数据库迁移 | 已编码待正式导入验收；未替用户写入商品 |
| CAT-003 单行失败修复 | 本地工作区，见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 2026-10-15 只读核对数据库日志与商品计数；Java 19 项测试通过，含无图回归测试；未启动服务或代替用户导入 | 已修复空图片更新；待用户重启原服务后补入 `16FMT001` 验证 |
| CAT-002 图片说明不符修复 | 本地工作区，见 [任务记录](tasks/CAT-002-workbook-image-ingest.md) | 2026-10-15 只读核对真实工作簿 A15 图片锚点与说明、商品图片资产；Java 20 项测试通过，含真实工作簿解析；前端构建通过；未启动服务或代替用户补图 | 待用户在原端口勾选两张候选图并仅更新该商品，核对详情 |

业务验收记录尚未建立，本表没有将源码存在标为已验收。
