# 架构与空间数据契约

更新日期：2026-10-26。本文区分当前实现与后续目标契约；新增目标字段和模型不代表现有 API 已支持。
业务依据见 [产品需求](product-requirements.md)，迁移差距见 [进度表](status.md)。
首期 PDF/PNG 闭环的详细设计见 [Spatial 架构](../Spatial_Canvas_Engine_空间解析编辑与无限画布系统架构设计_V1.1.md)、[数据协议](../Spatial_Scene_数据协议规范_V1.0.md)与[实施方案](../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)。
编码前固定的规则见 [几何](spatial/geometry-rules.md)、[接口/存储](spatial/api-storage-contract.md)、[确认失效](spatial/confirmation-rules.md)和[真实样本](spatial/recognition-validation.md)四份附件。

## 1. 服务职责

| 模块 | 负责内容 | 当前入口 |
| --- | --- | --- |
| Nuxt/Vue 前端 | 文件选择、画布视图、几何编辑、排版、确认交互与结果呈现 | `frontend/`，本地 3001 |
| Java 业务后端 | 商品生命周期、项目与场景保存、识别任务编排、确认快照、推荐与生成任务编排 | `backend/src/`，本地 8082 |
| .NET CAD 服务 | DWG/DXF 几何解析、单位/目标图处理、质量报告、规范预览 | `backend/cad-parser/`，宿主机 8091 |
| 外部识别/推荐/生成能力 | PDF/PNG 真实识别、商品匹配辅助、效果图生成 | 按实际接入任务定义；未接通时不得当真实能力 |
| PostgreSQL 与文件存储 | 保存项目数据、商品、版本及资产关联 | 数据库宿主机 5434；资产持久存储方案待任务落实 |

浏览器通过 Java 业务接口调用解析与后续服务，不依赖外部服务的内部端口或直接持有服务凭据。
解析算法继续复用已有 CAD 服务。外部响应通过显式适配层进入统一业务模型。

## 2. 核心数据关系（目标模型）

```text
DesignProject（项目资料与客户需求备注）
  └─ DesignFloor（floorId 作为既有 projectId 工作区隔离键）
       ├─ SourceAsset / SourcePage / RecognitionRun
       ├─ SourceScene（识别原始几何及质量，保留版本）
       ├─ DesignScene（可编辑世界坐标与版本）
       │    ├─ Space（建筑房间及功能分区、确认状态）
       │    ├─ Wall / Opening / Obstacle / Underlay
       │    └─ Layout（布局版本）→ Placement（家具需求与占地）
       └─ ConfirmedProposal（确认快照）
            ├─ ProductSelection（Placement → Product → Variant）
            └─ RenderJob / RenderArtifact

Product / Variant → AvailabilityRevision（上下架、尺寸、价格等变化）
Viewport（平移、缩放）独立于上述物理几何
```

2026-10-17 曾移除项目资料及 office_project 表，历史迁移见 [WORKSPACE-001](tasks/WORKSPACE-001-remove-project-info.md)。2026-10-26 按最新需求新增 `office_design_project` / `office_design_floor` 和项目树、创建项目/楼层、添加楼层、修改客户需求接口。项目保存名称、需求备注及需求修订；楼层保存项目归属、名称和排序。

楼层的 `floorId` 直接作为既有业务表 `project_id` 与路径 `projectId` 的隔离键，复用上传、画布、模板、选品和预览能力。默认 `OFFICE-PROJECT-001` 映射为默认项目/楼层以保留旧数据；上传与画布保存核验楼层存在。前端按楼层隔离 Office localStorage、识别 sessionStorage 和 IndexedDB 原图，切层时保存旧层并完整加载目标层，过期响应不得污染当前层。客户需求只作提醒，不自动使几何变化或替代下游复核。实现见 [WORKSPACE-003](tasks/WORKSPACE-003-project-floors.md)。

当前上传页和 office_canvas.files_json 使用零或一张当前图纸；后端保存拒绝多文件。旧草稿恢复时优先选择与底图同名的文件，否则保留首个。更换文件只更新上传文件和二进制缓存、清除上传页旧识别预览，不删除已保存几何或历史识别；见 [UPLOAD-001](tasks/UPLOAD-001-single-drawing.md)。

当前上传记录带 drawingId（同名同大小重传也更新）、analysisId、analysisPageNumber、appliedAnalysisId，原样保存在现有 files_json，无新增表。前端 floorPlan.sourceDrawingId 关联本地来源；旧草稿仅保留有限名称兼容。上传页和空间页按当前任务进入；未识别/识别中/失败显示当前图纸提示或预览，不展示旧几何。进入空间明确应用新图，导入前保留撤销快照，跨图替换移除旧坐标对象和模板；同分析恢复只补来源、不覆盖设计编辑，appliedAnalysisId 防止清空后的空间重填。详见 [UPLOAD-002](tasks/UPLOAD-002-drawing-canvas-handoff.md)。

图片/PDF 上传页通过 `/api/v1/floor-plan/analyze` 的 `background=true` 启用后台识别（直连 Qwen）。先事务保存原图和独立分析 ID，再后台转换并事务发布标准预览，之后并行请求房间/尺寸候选与独立房名框，两者完成后校正轮廓并事务保存房间。GET 在 analyzing 时即可返回 previewUrl；processing 记录实际阶段、耗时和缓存命中。前端每秒查询，刷新/导航后从 sessionStorage 续查同一任务，完成或失败停查。保存状态与 DONE 进度分次提交，客户端同时等待进度终态。结果由用户应用到画布，不自动覆盖设计稿。未指定 background、CAD、外部 RSDP_VISION_URL 服务兼容同步协议。

任务池 2 个线程/8 个等待位，独立模型池 4 个线程/8 个等待位，满载明确失败。当前后台队列限单 Java 实例；进程重启时，启动扫描将遗留后台任务标为失败并保留底图，需要重新发起。标准 PNG 缓存按源 SHA256、页码、最长边、PDF DPI 和转换版本隔离，30 分钟 TTL、最多 8 项/64 MiB LRU；同键转换合并，不缓存 AI 或人工几何。详见 [RECOG-007](tasks/RECOG-007-progressive-recognition.md)。

所有关联使用稳定 ID。文件名、数组下标、可修改的房间名称不能作为唯一关联键。
重识别生成新的识别版本；以映射/比较方式处理用户编辑，不沿用序号误配空间。
当前兼容导入会通过分析详情的 `imageSha256` / `designCorrections` 继承同项目、同标准底图、唯一同名的人工轮廓；原始 `rooms` / `sourcePolygon` 与新来源版本保留，毫米稿还原为来源像素后再应用到新设计副本。`contourGrounded` 记录独立房名定位和封闭墙线证据；不代表人工确认或比例确认。对已有墙线证据的房间，历史框需与来源边界双向接近（来源像素距离容差 10），仅重叠不足以自动套用，拒绝时非阻断提示并保留历史。当前草稿可显式恢复当前分析的 `sourcePolygon`，以已有比例换算、按原几何编辑路径使排版失效并支持撤销，不重新识别、不替换来源。见 [RECOG-005](tasks/RECOG-005-label-grounding-and-correction-reuse.md)、[RECOG-009](tasks/RECOG-009-contour-history-and-partitions.md)。

V5 增加由实心柱/长实墙支撑的细双线结构网络，按提取到的粗细线占比保留不同墙图的处理路径。同名文字与候选在数量一致时做一一最小代价匹配，不再要求错误多边形先包含房名；最终仍核验封闭区域和其他房名归属。启用校正时，未获 `contourGrounded` 的结果逐空间降为 low 并附 `ROOM_CONTOUR_UNVERIFIED`，原始模型自报置信保留。此证据不涵盖开放功能分界，不能等同于全图质量通过，见 [RECOG-008](tasks/RECOG-008-thin-partitions-and-repeated-labels.md)。
V6 在未恢复空间上补查相连弱线，并以至少三个结构房间的一致图上面积文字核对相对面积。完整隔间碎片满足四边支撑、覆盖率、面积、独占房名及不交叠条件后才合并为墙内轮廓；方法证据分别记录，弱路径保持 mid。面积文字不生成毫米比例，缺少证据仍保留低置信候选；开放功能分界没有因此解决，见 [RECOG-009](tasks/RECOG-009-contour-history-and-partitions.md)。
V7 的独立房名匹配不再被越界/损坏的初稿连带禁用；有限初稿位置只提供分配成本，不成为最终几何。无效或漏列的开放办公候选可由结构地面追踪补回：排除已获得墙线证据的房间和其他房名触及的独占区域，拒绝页面泄漏、其他房名/已恢复房间冲突；不直接截断越界矩形。`openSpaceRecovery` 独立记录种子来源与估计恢复，保持 low / contourGrounded=false，不计作封闭墙线接受数、比例确认或人工确认。已有有效开放候选不强制重写，功能分界仍需复核，见 [RECOG-010](tasks/RECOG-010-missing-open-office.md)。
RECOG-014 恢复混合识别：Qwen 主请求输出房间/功能区清单、初始 polygon 和独立的文字紧框 labelBbox；后端在同一标准底图上用墙网保守核查，可靠时替换，否则保留低置信度初始轮廓供复核。墙线掩码直接由 Java 结构线规则生成，来源为 contourRefinement.wallMaskSource=STRUCTURAL_PIXEL_MASK，阶段为 STRUCTURAL_WALL_MASK；已移除外部分割服务接入及其可用性字段。labels-only 响应仍兼容，缺少轮廓且无法墙网补救的语义进入 unresolvedSpaces；仅此协议继续保守扫描未命名墙网，混合候选不追加无名碎片替代清单。房间几何与文字框不能混用，原始输出与设计副本隔离，未知比例不自动确认。见 [RECOG-014](tasks/RECOG-014-space-completeness-recovery.md)。
RECOG-013 在主请求增加文字紧框，按位置补齐独立定位缺失；原始主/独立响应与合并定位分开保存，文字框不作为房间边界。主请求补位的墙线轮廓保持 low / contourGrounded=false，内部 wallRegionVerified 仅用于避免已获得墙网的空间被开放区重复覆盖。同名组改为带缺失位的一一部分匹配，不要求两路数量相同；新语义流程额外扫描所有保守封闭连通区域，未归属部分以 low 未命名候选返回，不为文字缺失猜名称或比例，见 [RECOG-013](tasks/RECOG-013-recognition-pipeline-recovery.md)。
服务使用 `sceneVersion` 等不同名称时，适配层注明它是契约版本还是业务修订，不能与编辑修订号混用。

## 3. 坐标和单位

### 单一世界坐标

- 设计场景的真实几何、家具位置、占地和净距统一保存为毫米；显示层可将其格式化为米/厘米。
- PDF/PNG 的原始像素轮廓保留在原始场景，未校准前标为来源坐标；确认比例后才进入可靠毫米几何。
- 建议目标世界坐标：右为 X 正方向、上为 Y 正方向，旋转角使用度；来源方向不同的转换记录在适配层。
- 本轮 Spatial 目标协议固定上述世界方向；标准底图以左上原点、Y 向下的像素坐标保存。未校准编辑稿使用显式 `SOURCE_PAGE` 框架，校准后整稿转换为 `WORLD_MM`，不能混用。
- 明确单位来源与确认状态；响应 `units=mm` 不代表文件原始单位已可靠确认。
- 房间轮廓沿墙内侧；墙厚形成的相邻房间间隔合法，只对明确登记的无墙共边分区检查裂缝。
- 净地面面积从有效多边形扣除孔洞与永久实心障碍并集计算，不重复扣除；普通禁放区不扣净面积。平方毫米除以 1,000,000 转为平方米。
- 包围盒用于选择/导航/加速计算，不能代替真实多边形或净面积。

### 世界、底图和视图变换

概念变换：`screenPoint = viewportTransform × worldPoint`；编辑指针用同一变换的逆变换回到世界坐标。
Y 轴方向、平移、缩放、旋转使用明确可逆变换处理，不能各组件独立猜测比例。
底图保留 `imagePixelToWorld` 变换或等效范围/尺寸信息；与房间共用世界坐标后再通过同一视图变换显示。
不得仅按房间集合重新计算一种尺度，再按预览图计算另一种尺度。

### 校准与修改尺寸

校准：用户选择图纸上的参考线段并输入实际长度，得到该来源图/页的像素到毫米比例；影响所有依赖该来源的空间。
自动候选：优先定位外围尺寸长线、裁图转录完整标注；不足时用标准图及四边放大图读取端点。后端从标注和像素跨度计算比例，至少两条独立一致参考及严格多数，当前相对偏差阈值为 3%（本轮策略）；无明确单位需显示假设。候选与确认分开，用户确认时重验版本、图像哈希、报告及单位，客户端不能提交任意比例。报告/确认保存在当前识别 revision 的 raw_result；读取不改 rooms 和来源单位。
`/api/v1/floor-plan/{id}/dimension-calibration` 提供读取校验，`/recognize` 和 `/confirm` 提供重读与确认；详情见 [RECOG-003](tasks/RECOG-003-dimension-calibration.md)。前端核对页自 2026-10-21 起不再展示自动校准面板，统一以两点校准应用绝对来源比例比值，保留设计编辑并使全空间/排版确认失效；复核建议不拦截排版，无需逐空间确认。已保存空间携带 sourceAnalysisId/sourceAnalysisRevision，同版本恢复时只补回来源元数据；版本不一致保留编辑并提示，不能静默替换底图。
修改尺寸：用户改变一个设计空间的世界几何；视图比例不变，相邻空间不自动整体缩放。
原始底图作为对照保留，设计副本变化不能冒充原始识别结果。

首期矩形修改宽深默认固定局部左下角并显示锚点，用户可以切换；沿矩形局部轴更新对应顶点。
异形空间首期以顶点/边界和选中边长度编辑为主；整体轴向缩放须显式预览，禁止简单替换成矩形。
稳定顶点 ID 与持久局部轴防止重排顶点后宽深互换；边长自由/正交局部模式按几何附件明确固定点和传播顶点。
已知邻接/重叠疑点提示需复核；不自动移动邻室。按用户最新要求，复核提示不作为排版门槛。
面积等派生值由几何推导，不能分别维护一套人工面积与一套未更新的几何并都声称为当前值。

## 4. 空间契约要点

| 字段含义（目标） | 用途 |
| --- | --- |
| `spaceId / parentSpaceId` | 稳定空间标识，区分建筑房间及内部功能分区 |
| `sourceAssetId / sourcePageId / recognitionRunId / sourceRoomId` | 对应原图、页、识别批次和来源空间 |
| `sourceGeometry / designGeometry` | 原始识别与可编辑几何，保留轮廓/孔洞，不覆盖来源 |
| `unitResolution / calibration` | 单位、比例依据、确认状态和校准记录 |
| `geometryRevision / requirementRevision / confirmedRevision` | 几何、用途/归属/障碍/质量等确认条件版本；confirmedRevision 指已确认的需求版本 |
| `qualityIssues / reviewDisposition` | 识别不足、人工复核和处理记录 |
| `worldBounds / underlayTransform` | 各层位置与底图对齐依据 |

导入命名空间和未命名空间。CAD 已解析出的门窗、墙体和家具候选按实际能力保留；尚未解析出的内容明确标为未知，不能伪造为空来声称完整。
既有家具候选与新设计 `Placement` 分开，用户确认复用后才成为设计排版。

## 4.1 空间模板（当前实现）

`SpaceTemplate` 独立于识别源和可编辑画布，使用现有 `SpatialSceneSnapshot` 1.2 保存毫米空间、来源分析 ID/版本、画布修订、比例、派生面积及层可用性。`office_space_template` 仅提供创建/列表/读取，无原地覆盖更新；重生成创建新 ID，旧模板保留。面积从几何与孔洞/实心障碍并集重新计算，汇总为各空间面积合计（不等于重叠房间的地面并集）。

接口：`POST/GET /api/v1/office/projects/{projectId}/space-templates` 和 `GET /.../space-templates/{templateId}`。保存以 clientChangeId 幂等、内容哈希拒绝同 ID 异内容，查询限制项目；不要求人工逐空间确认。数据库迁移须显式执行，见 [GEOM-004](tasks/GEOM-004-space-template.md)。

当前工作台可生成彩色 SVG 模板预览、保存/读取/导出 JSON，并以模板创建可撤销布局副本。编辑后的几何/名称/用途与模板比较，提示模板过期；排版单元记录 spaceTemplateId/templateSpaceId。直接进入布局自动捕获本地快照，本地与数据库保存状态区分。使用模板后移除旧识别地址，防止刷新重导入原图覆盖模板副本。

此功能不等于已迁移完整 DesignScene 写队列或建筑约束排版：模板可保留孔洞/建筑层，但兼容 Office Canvas 不能处理这些层时明确阻止使用，不静默丢弃。当前实际编辑稿只含房间轮廓，未提取层仍为 UNKNOWN。完整布局几何/推荐/方案确认快照依既定任务推进。

## 5. 排版、商品与效果图契约

2026-10-15 新增用户明确要求的单空间二维**概念效果预览**，作为排版后的辅助入口，允许未确认排版/未选商品，不替代下述正式方案效果图契约。
后端以保存画布中的空间和家具投影生成不可变 `CONCEPT_PREVIEW` 快照与 PNG 参考图，调用 Qwen-Image-3.0 原生异步接口；独立 `office_render_preview` 表和本地图片目录保存结果。
前后端比较当前空间的轮廓、家具位置/尺寸/旋转、商品与目录快照，旧图标记历史，不写回当前画布；仅视图和其他空间变化不影响此预览。
接口、配置、幂等、失败恢复与窗口轮询的时限见 [生图配置](render-configuration.md)；正式已确认方案的生成仍待后续 RENDER-001。

家具布局单元先保存 `placementId`、`spaceId`、类型、需求宽深/高度、位置、旋转和占地轮廓。
推荐前允许 `productId/variantId` 为空；推荐后另建选择映射，不能把商品 ID 当作布局单元 ID。
需求尺寸与实际商品尺寸分开记录，不能修改真实商品尺寸以迎合排版。

布局依赖场景/空间几何版本；推荐依赖已确认布局版本与商品可用性；
方案快照包含空间、排版、商品/变体、价格与选择版本；效果图依赖该快照。
回包带输入版本，收到结果时比较当前版本；过期结果进入历史，不覆盖当前编辑状态。

商品下架后禁止新推荐，但保留旧方案选择的快照和提示；确认方案时再次核验商品可用性。
当前商品导入已把确认后的 `widthMm/depthMm/heightMm` 写入 `rspu_variant.dimensions`（JSONB，`unit=mm`）；尺寸原文保留在 `size_text`。旧 `widthCm/depthCm` 数据仍可读取。画布现有接口显示厘米，读取毫米时向上取整厘米，避免缩小占地；这只是旧候选适配，当前模块宽深推荐使用下述精确毫米路径；完整匹配仍需补齐品类、结构和方案核验。
本轮模块尺寸推荐直接使用 `OfficeProductDto.widthMm/depthMm/heightMm` 的精确数值，旧厘米字段仅兼容显示。服务端读取已保存且全部家具已确认的指定空间/模块，分别比较旋转后的宽深，按两轴最小占比分档；查询及选择都核验当前修订和在售审核状态。选择以现有 `office_canvas` JSON 记录 `productSelection`（真实 SKU/商品、尺寸、旋转、价格与清单行），同规格批量选择不改预留几何；工作区事务 advisory lock 与同事务清单/画布写入；锁替换和资料表清理见 [WORKSPACE-001](tasks/WORKSPACE-001-remove-project-info.md)。接口与验证边界见 [RECOMMEND-002](tasks/RECOMMEND-002-module-size-matching.md)。
供应商的包数、CBM 体积和重量原表值分别保存在变体字段，与采购数量、商品尺寸分开；重量源表没有明确单位，不推断为千克。
渲染输入包含所选商品的真实尺寸、资源、位置与方向；缺少资源的降级及概念图标识由 RENDER-001 明确。

### 5.1 分组排版与待应用预览（当前实现，2026-10-20）

兼容画布 JSON 的 ZoneObject 增加可选 `layoutPreferences`：策略、明确目标人数、桌长调整/椅子生成、拉椅毫米预留与入口/禁放矩形。FurnitureObject 增加可选 `layoutGroupId` 与 `layoutRole`，新自动组为 `auto:{spaceId}`、角色 primary/seat。未标记手动实例保留；旧自动设置仅在同模块时兼容归组。空间身份、毫米世界坐标和目录快照保持既有契约。

`planLayoutProposal` 是预览与应用共用的纯函数；草稿预览通过页面局部状态传给画布，不写入持久化对象。应用检查空间/家具输入 sourceKey 并重新计算，同步替换自动组、保存需求、清除本空间确认及失效选品，单次撤销恢复关联状态。仅改预览不取消当前已保存方案确认。前端与 Java 保存/推荐入口独立检查已确认家具的物理轮廓、冲突、预留及座位条件。未知建筑通道仍需现场核验；详情见 [LAYOUT-014](tasks/LAYOUT-014-layout-planning-review.md)。

2026-10-22 通道（LAYOUT-021）：`layoutPreferences.channels` 保存横/纵向通道（世界毫米位置与宽度，可选 `clearanceMm` 两侧净距，禁放带按 宽度+2×净距 处理），随画布 JSON 持久化与撤销；通道（含净距）经 `clampChannelsToZone` 钳制在空间包围盒内，拖动预览/提交、面板输入与渲染矩形均不越界。课桌式网格排版按通道切段分列/分排（切段始终按未裁剪禁放带全轴切分，桌列不跨通道线），纵向通道可「仅从二排起」保持首排完整（首排深度以 `layoutSettings.firstBlockDepthMm` 随实例保存供确认豁免）；沿边/中央通道/会议/职员样式排布把通道作硬阻挡并入避障。禁放带再按房间多边形裁剪（LAYOUT-027，`clipRectToRingRects` 扫描线分段，矩形空间输出与包围盒钳制完全一致；凹形空间一条通道可输出多段矩形，渲染/压占校验/避障用裁剪后分段）；`startMm`/`endMm` 端点留断收窄走向跨度（LAYOUT-028，缺省贯通，与「仅从二排起」按交集叠加），旧数据无此字段按贯通处理；确认校验家具（旋转占地）不得压占通道——服务端核验等价于裁剪后语义（被确认家具必须先完整落在轮廓内），仅端点留断同步进服务端 band 计算；通道与入口的衔接/截断校验为提示级（LAYOUT-029 `channelEgressHints`：入口代理=人工标注的门洞净空，识别层不产出门数据，未标注只提示未核验；后端不接入，无 warnings 通道）；通道增删改与画布拖动经 `updateZoneChannels` 使本空间排版确认与已提交方案失效，不影响其他空间。通道宽度与净距数值为可调整策略，不声称符合建筑规范；弧形布局斜向通道与服务端通畅性核验待后续，见 [LAYOUT-021](tasks/LAYOUT-021-canvas-aisle-channels.md)、[LAYOUT-027](tasks/LAYOUT-027-channel-polygon-clipping.md)、[LAYOUT-028](tasks/LAYOUT-028-channel-endpoint-breaks.md)。

2026-10-26 椅背对背通行净宽（LAYOUT-038）：配对排布椅背相对间隔净宽 = 2×拉椅预留 + `CHAIR_BACK_PASSAGE_MM`（500mm，策略值）；`planOfficeFurniture` 对 faceToFace 组内间隔与 backToBack 组间排距自动加宽（`rowAisleMm`），放不下自然减排；样式参考排布（adaptive 预设）间距按素材固定不加宽、低于净宽只提示（035 黄级 `chair-back-gap`）；课桌式全同向与 centerAisle 不适用；确认校验与后端不纳入。见 [LAYOUT-038](tasks/LAYOUT-038-chair-back-passage.md)。

2026-10-26 校核预警分级（LAYOUT-035）：`layoutPanelWarnings` 统一产出分级提示——红=会被确认/应用拒绝（方案无法排出、座位不足目标），黄=低于策略阈值（通道宽度 900mm、入口衔接/截断 600mm、人均面积 2.0㎡/座、边缘补位），info=未核验/现场核验说明；黄/红附修改建议，全部数值为策略值非规范。023/029 提示迁入同一展示位并保留旧 testid（`layoutWarningTestId`），`channelEgressIssues` 提供结构化分级出口；净面积统一走 `zoneNetAreaMm2`（与 034 候选指标同源）。硬校验拒绝语义不变，后端不接入。见 [LAYOUT-035](tasks/LAYOUT-035-warning-levels-and-advice.md)。

2026-10-26 多方案候选（LAYOUT-034）：`planLayoutProposal` 返回 `candidates` Top 3（`LAYOUT_CANDIDATE_TOP_N`），课桌式网格经 `OfficeLayoutPlan.alternates` 暴露 auto 内部评估过的朝向/对齐变体，会议枚举尺寸×朝向×位置；候选按 `layoutCandidateScore` 降序——目标达成 1000 分/座封顶于目标、超出 1 分/座、未指定目标按容量，并列保持生成顺序（既有默认方案不漂移），面积利用率/人均余量只展示不参与排序；家具集合签名去重（方形家具归一化旋转角）。面板以 `layoutCandidateKey` 签名跟随选中候选，应用时 `applyLayoutProposal` 重算并按候选索引取用，预览不落库与应用/失效语义不变。评分权重与指标均为策略值，非规范，见 [LAYOUT-034](tasks/LAYOUT-034-candidate-scheme-comparison.md)。

## 6. 几何约束与版本失效

最新用户纠正（2026-10-20）：参考图只固定局部样式，不固定整张示例的总数量。实际排数/列数与横纵方向由场地轮廓、保留家具、预留区域、间距和目标人数计算；完整对坐/T/C/十字最小单元可以重复或整单元跳过，不拆坏样式。当前规则为 `2026-10-adaptive-reference-v5`，实例记录 `layoutClusterId`；下方 v4 固定示例数量描述是历史记录。详见 [LAYOUT-016](tasks/LAYOUT-016-space-adaptive-reference-layout.md)。

2026-10-20 参考图约束：四类职员桌/屏风预设使用 `2026-10-reference-v4`，完整组合的方向、镜像、件数固定，组内紧贴；目标人数不能截断组合，空间不足不能减组。会议桌椅推荐保持独立。预览/应用/方案平面使用同一实例变换及座席示意；旧布局必须显式应用更新，详见 [LAYOUT-015](tasks/LAYOUT-015-exact-reference-presets.md)。

- 多边形有效、非退化，面积为正；不存在未经处理的自交、跨房间重叠或不合理共边。
- 家具的旋转后完整占地应位于所属空间可用区域内，不能仅检查中心点或 AABB。
- 门口、通行区、柱及禁放区以世界单位参与检查；规则数值随任务定义，不能写在显示像素里。
- 保存几何变更时更新修订与派生值，并使受影响确认/布局/推荐/渲染状态失效。
- 缩放和平移属于视图，不更新几何版本。
- 原子化保存相关几何、版本与失效状态；版本冲突明确反馈，不能静默覆盖其他版本。
- 当前工作台不要求逐空间或整体空间确认：canEnterLayout / 后端 requireLayoutReady 只核验可靠单位、ID 和可计算轮廓；识别候选/边界重叠只提示复核。进入排版不填写人工确认记录，比例与排版确认保留。详情见 [FLOW-002](tasks/FLOW-002-advisory-space-review.md)；确认附件的历史逐空间 API 不再定义工作台门槛。
- 草稿可包含待解决问题，确认快照必须通过阶段校验；后端不能只信任前端按钮是否禁用。

## 7. 当前迁移重点

空间核对总览内联手动画区：`SpaceReview` 的「画新空间」工具逐点描出新空间多边形，经 `store.addDrawnZone` 建为正式 ZoneObject（`source: user_annotation`、`geometrySource: manual_geometry`、`evidenceKind: MANUAL`）。无图纸的空白画布直接以毫米建空间（默认视野 12000×8000mm）；已识别但未校准的底图上以源单位记录，随两点校准统一换算；已校准毫米场景按世界毫米存储。画区分形状模块与逐点描两种方式：矩形/L 形/U 形模块拖出包围盒一次生成轮廓（`sketchShapePolygon`），支持旋转角度输入（绕包围盒中心，屏幕顺时针为正，毫米场景按坐标系取反符号；端点先取整再旋转以保持直角），毫米场景自动取整 100mm；逐点描保留沿墙精描。画区与描边/编辑共用同一相机逆变换，支持撤销、Esc 取消与改名（SpatialInspector）。已画空间可在选择工具条输入角度旋转：`store.rotateZone` 绕面积质心旋转存储环（Y 向下坐标系正角即屏幕顺时针），建筑约束同步旋转并经 zoneLayoutProblem 校验，旋转后非轴对齐矩形停用宽/深数值编辑、保留异形轮廓。独立 `/manual-canvas` 手动布局沙盒页已删除（页面、组件、store 与专用相机移除，共享家具模板/图例类型保留）；无图纸时从上传页「手动画图」入口进入空白毫米画布（`store.startHandDrawing` 清空图纸/识别/空间并进撤销快照），再用「画新空间」逐点或形状模块绘制。

本轮 `/canvas` 的空间核对使用 `SpaceReview` / `SpatialCanvas`：来源范围和设计多边形共用相机，矩形毫米编辑与异形顶点拖动更新 polygon，面积从几何派生。旧 `CanvasBoard` 保留给单空间排版，仅渲染当前 designZoneId 的空间、家具及显式归属的局部内容，单空间不绘制整层底图。视图范围取当前多边形，内容偏移、指针逆变换与适屏共用世界原点；负坐标与远坐标不需平移设计数据。整层底图及全部空间仍在核对总览展示，旧 px 草稿不会被猜测为已校准。

设计页 `DesignSpaceSwitcher` 提供下拉与前后空间选择，通过既有 `enterDesign` 更新 designZoneId/activeZoneId 并同步 URL，画布按目标重新适屏；仅清除选择、工具、逐件放置预览与当前弹窗，保留各空间家具和确认。排版状态从现有家具/确认数据派生，无新后端接口，见 [LAYOUT-009](tasks/LAYOUT-009-design-space-switcher.md)。
空间核对的自由变换以设计顶点为输入，八点框只提供变换控制，不替换凹多边形。平移、缩放和单边推拉经 `contourTransform` 计算，同一 `updateZonePolygon` 校验及保存；拖动预览与抬起提交分开，视图缩放仅更新相机，见 [GEOM-007](tasks/GEOM-007-free-contour-transform.md)。
兼容数据仍通过 `ZoneObject` 的可选 `reviewUnit/sourceAnalysisId/sourceYAxis/sourcePolygon/mmPerSourceUnit/evidenceKind` 和 Office Canvas JSON 保存。图像坐标 Y 向下；CAD 原始范围 Y 向上，导入时把设计副本转为 Y 向下并记录 sourceYAxis，原始 FloorPlan 不改方向。渲染适配到 WORLD_MM 时统一翻转 Y。
当前单位模块共享 `manualFurniture` 可见目录与 `ManualFurnitureGlyph`；新实例使用 `furnitureTemplateId` 记录家具图例，`productId` 保留兼容字段，未绑定商品时为空串。模块规格及实例宽深采用毫米，沿边旋转的占地与物件实际宽深分别处理；旧商品绑定实例只做显示适配，不重写保存数据。
自定义模块为 `CustomOfficeModule`，独立 UMOD ID 与共享 `glyphTemplateId` 分开；目录存于当前浏览器 `customModules` 配置，界面明确本机范围，未新增团队共享服务。已摆实例携带不可变 `moduleSnapshot`，使用既有画布 JSON 保存，即使目录被编辑、移除或另一个浏览器没有本机目录，也按原快照渲染。逐件放置状态不持久化，位置使用画布世界坐标逆变换，按完整旋转外包占地校验实际轮廓、离墙和与已有家具间距；成功仅追加当前空间实例，使该空间排版确认失效，不替换其他家具。选中删除与快捷键使用同一可见对象检查和 store 删除路径；删除家具后只取消该空间剩余家具的排版确认，保留其他空间及目录，沿用画布撤销/持久化。目录删除提供独立的最近一次恢复入口，不混入画布几何撤销。
单位图例排版由 `officeAutoLayout` 按当前多边形计算；自动模式比较横纵多排，保留可调排间/离墙/同排距离，家具外包占地加离墙范围必须包含于房间轮廓，沿边跳过相互重叠候选。`layoutSettings` 随实例保存用于参数刷新/撤销恢复，修改参数只预估，主入口固定调用 auto，手动模式使用独立应用按钮。当前件数与预估件数分开，按图例、实际宽深、旋转及占地位置判断是否已经应用；应用反馈取实际实例数量，几何变动后旧成功提示失效。应用时替换当前空间家具并清除其布局确认；旧商品排版入口仍兼容。算法为启发式候选比较，不保证全局最优，也不代替建筑层避让和服务端完整方案校验。

新建排版的同排列间距初值为 0 mm，排间净距/离墙初值仍为 900/300 mm；旧实例继续恢复其已存设置。面对面/背靠背的组内中缝分别保存为 `faceGapMm`/`backGapMm`，旧实例的 `pairMm` 仍兼容读取；中间通道使用 `aisleMm` 作为连续中央净空。单排靠上侧，背靠背背侧相接，面对面朝向相对，中间通道两侧朝向通道，沿边在不重叠前提下按上/下/左/右布置。新增错位排和中间通道等方案仍使用相同多边形检查，不把可调整初值视为建筑规范。
这不是完整 DesignScene 协议迁移：旧排版间距、异形边界与碰撞规则等仍未全面重写；整体不可变确认快照、写队列与冲突处理待完成。后端旧确认路径已保留 polygon，空间核对的进入排版门禁新增后端几何/状态检查。实现与模拟证据见 [GEOM-003](tasks/GEOM-003-space-review-workbench.md)。
首期复用 `/api/v1/floor-plan/**` 与 Vue/SVG，先贯通 GEOM-001/FLOW-001 的单房间保存恢复/确认闭环，同期验证 RECOG-002 真实识别质量，再扩展 GEOM-002 复杂编辑；不以更换 PixiJS 为前提。
来源和设计修订分别存储，固定键/快照/回执/迁移模型见接口存储附件；实际 SQL 尚未实施。
家具布局及后续完整业务阶段在该闭环之后推进，不因文档中定义模型而视为已实现。
迁移旧草稿时记录旧坐标类型和比例可信度；无法可靠还原真实尺寸的草稿标为待校准，不能猜测成已确认毫米数据。
保留兼容读取或显式迁移路径，按任务逐步替换，避免一次性重写整个项目。

### 家具模块决定排布，空间用途只建议（LAYOUT-017）

家具模块目录是唯一家具选择入口；productStyleId 由模块及自定义模块 glyphTemplateId 派生，arrangementPresetId 仅允许该结构的兼容方式。房间用途不限制家具，也不用于推断目标计数。自动组主模块 layoutContext 为 meeting 时按实际椅子计座位，workstation 时按桌计工位，other 时按同模块实例计件数。现有 targetSeats 字段据该上下文解释为目标人数或件数；预览、应用、刷新、前后端确认一致。空间用途建议与选品可用性分离，见 [LAYOUT-017](tasks/LAYOUT-017-module-selection-and-space-advice.md)。

### 排版参考测量与相机旋转（LAYOUT-018）

排版标注从实际/待应用家具的旋转外包占地派生；离墙距离以实际 polygon 射线相交求得，组间距使用共截面的最近相邻家具，紧贴模块不算通道。预览携带 replaceIds，用于排除即将替换的自动组，同时保留人工家具；拖动期间测量当前拖动几何。所有数据为毫米，椅子图例仅示意、不会添加实例。视图角度留在页面相机模型，CSS 变换与指针逆变换共用中心、角度、zoom、屏幕 pan；适屏按旋转范围计算，旋转不触发设计保存或确认失效。见 [LAYOUT-018](tasks/LAYOUT-018-reference-dimensions-and-view-rotation.md)。

### 效果预览的商品外观附件（RENDER-003）

生图从已保存家具 productId 核验可用商品并读取商品库本地图片，P编号映射家具清单序号，最多两张商品板加布局图。图片缺失/商品失效在供应商调用前阻断。不可变商品附件存于预览 snapshot_json.renderProductReferences，响应拆为 productReferences 与 productImagesUsed，几何 snapshot 投影仍与客户端一致，不引入迁移列。任务保留原图摘要与已发送参考板，旧任务不能冒充使用商品照片；未选品概念预览保持可用。见 [RENDER-003](tasks/RENDER-003-selected-product-image-references.md)。

RENDER-004 更新：RenderProductReferences 按有效商品的 rspuId 从 image_assets 读取公共/当前变体图片，主图优先、内容去重、每款最多 4 个角度；每款参考块合图后仍限制为两板。版本 selected-products-multiview-v2，保存每张来源与摘要及实际使用张数。provider 第一张图片改为 RenderSceneGuide 的无文字立体代理图，原 referencePng 仅供平面核对；实际引导 URL 随任务保存并响应 layoutGuideUrl。正向与负向提示共同限定完整场景，不能将图纸或清单绘入场景。见 [RENDER-004](tasks/RENDER-004-multiview-products-and-scene-guide.md)。

RENDER-005 更新：RenderLayoutContext 从不可变当前设计计算多边形净面积、旋转外包离墙/相邻净距、主模块行列与朝向，并按项目/空间稳定 ID/来源修订/图片摘要关联原识别记录。原始轮廓不覆盖用户几何，来源不可用可降级，门窗未知明确标记。汇总提示替代逐件大坐标清单；RenderSceneGuide 支持 L 型与镜像。测量存于服务端附件、响应 layoutContext，客户端 snapshot 投影包含排版角色/分组/设置及来源比例字段。见 [RENDER-005](tasks/RENDER-005-physical-layout-context.md)。
