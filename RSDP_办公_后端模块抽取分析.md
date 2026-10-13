# RSDP → 办公空间项目后端模块抽取分析

> 目标：把 RSDP（`/Users/rooom01/work/rsdp`）中可复用的后端能力抽取出来，支撑独立办公空间项目的后端。前端自行实现，本分析只讨论后端。
> 分析日期：2026-10-09

---

## 1. 目标与范围确认

### 1.1 原型的 4 个页面与后端诉求

基于 `/Users/rooom01/work/rsdp-office/` 下的原型图、HTML 原型与设计文档 V0.1，办公项目需要后端支撑的能力可归纳为：

| 页面 | 核心后端诉求 |
|---|---|
| **Page 1 上传办公图纸** | 文件上传与元数据、PDF/PNG 视觉识别、CAD(DWG/DXF) 几何解析、识别结果人工校正、项目/空间元数据持久化 |
| **Page 2 无限画布·空间总览** | 画布对象（zone/note/arrow/reference/drawing）CRUD、比例尺校准、空间分区持久化、版本快照（Revision） |
| **Page 3 无限画布·单空间设计** | 按区域推荐商品、布局/容量校验、方案行（购物车）CRUD、效果图片资源、Agent 会话（可选） |
| **Page 4 整体方案与清单** | 区域-商品聚合、数量×单价实时合计、品类汇总、报价单/BOM 导出、方案版本管理 |

### 1.2 关键缺口（原型尚未真实实现）

- **AI 办公空间识别**：原型底图为示意 SVG，真实识别需要新增办公空间语义模型。
- **真实 CAD/PDF 多页解析**：现有 `FloorPlanService` 主要针对单页户型图。
- **办公 Agent Skill**：RSDP 现有 Skill 偏家装客厅，缺少办公工位/会议室/动线 Skill。
- **完整商业报价**：原型用假价格；正式需要库存、MOQ、交期、运费、税费。
- **空间布局算法**：现有 `RoomDimensionRules` 只做单件尺寸校验，不能完整计算工位数量/会议室容量/动线。

---

## 2. RSDP 现有能力扫描

### 2.1 候选模块总览

| 能力域 | 主要 Java 类 / 服务 | C# / 其他 | 可抽取性 |
|---|---|---|---|
| **CAD 几何解析** | `CadFloorPlanParser`、`FloorPlanParserRegistry` | `rsdp-cad-parser`（.NET 7） | **高** |
| **户型图视觉识别** | `VisionService`、`VisionFloorPlanParser` | — | 中 |
| **户型/Scene 生命周期** | `FloorPlanService`、`FloorPlanSceneRevisionService` | — | 中 |
| **空间/房间模型** | `FloorPlanRoom`、`FloorPlanFurniture`、`FloorPlanContextService` | — | 中 |
| **产品主数据** | `ProductService`、`RspuMaster`、`RskuSupply`、`RspuVariant` | — | 低 |
| **检索/推荐/Agent** | `RetrievalService`、`AiMatchingService`、`AgentStreamService`、`MarketingAgentGraphConfig` | — | 低 |
| **尺寸规则引擎** | `RoomDimensionRules`、`SizeSpecParser` | — | 中高 |
| **定价/报价/方案** | `PricingService`、`QuoteService`、`SchemeService` | — | 低 |
| **用户/项目/权限** | `ProjectService`、`SysUser`、RBAC、`DataScopeHelper` | — | 中 |
| **空间布局校验** | `SpatialService` / `engine/spatial/`、`SchemeService.saveCanvasLayout` | — | 中 |

### 2.2 可抽取性判定原则

- **高**：与 RSDP 业务模型无强耦合，可独立部署或作为库引入。
- **中高**：纯算法/工具类，但输入输出依赖 RSDP 数据字典，需要抽象接口。
- **中**：业务模型清晰，但依赖项目/用户/图片资产等少量伴生表。
- **低**：深度嵌入 RSPU/RSKU、价格、权限、向量检索，抽取代价接近整体迁移。

---

## 3. 能力映射表：原型功能 → RSDP 模块 → 抽取策略

| 原型功能 | 复用 RSDP 哪个模块 | 抽取策略 | 依赖与风险 |
|---|---|---|---|
| DWG/DXF 解析 → 房间/墙体/家具 footprint | `rsdp-cad-parser` + `CadFloorPlanParser` | **独立部署**，HTTP JSON 调用 | 几乎无业务耦合；注意 PDF/PNG 不能走此通道 |
| PDF/PNG 办公空间识别 | `VisionService` + 多模态模型 | **改造后复用**或**新增 Office 识别服务** | Prompt/输出字典偏家装；需新增办公空间语义 |
| 文件上传与资源管理 | `ImageAssetsService` / MinIO 配置 | **参考实现**或**API 调用** | 依赖 MinIO/用户权限；原型可先用本地/通用 OSS |
| 项目/办公客户档案 | `ProjectService` | **简化版新建**或**复用 schema** | 现有 project 偏家装，需扩展办公字段 |
| 空间分区持久化 | `FloorPlanRoom` + `FloorPlanSceneRevision` | **带伴生表抽取** | 需拖入 `floor_plan_analysis`、`image_assets`、异步任务 |
| 画布对象（zone/note/arrow） | 无现成模型 | **新建** | RSDP 的 canvas_layout 只存相对坐标摆位，不存标注 |
| 比例尺/尺寸校准 | `FloorPlanScaleSuggestion` + 自定义算法 | **部分复用** | 现有算法针对 OCR 尺寸反推；办公场景需交互式校准 |
| 按区域推荐办公家具 | `AiMatchingService` / `MarketingProductQueryService` | **新增 Office Skill + 调用检索接口** | 强依赖 RSPU/RSKU、向量表、价格权限 |
| 商品向量/图片搜索 | `RetrievalService` + `PgvectorProductVectorStore` | **直接调用 API** | 需真实商品数据与向量；原型可用 mock |
| 布局容量校验 | `RoomDimensionRules` + 自定义布局算法 | **抽象接口后复用** | 现有规则偏沙发/床等家装；办公工位/会议室需扩展 |
| 方案/购物车 CRUD | `SchemeService` + `SchemeItem` | **带伴生表抽取** | 强依赖 RSPU/RSKU/项目；建议简化模型 |
| 实时报价/总价 | `PricingService` / `AgentQuoteService` | **直接调用 API**或**简化版新建** | 依赖 AES 加密出厂价、品类倍率、全局倍率 |
| BOM/报价单导出 | `QuoteExportService` / `AgentSchemeExportService` | **改造后复用** | 输出字段需适配办公品类 |
| Agent 多轮对话 | `AgentStreamService` + `MarketingAgentGraphConfig` | **新增 Office 图配置** | 现有图偏营销客服；办公需精简为选品 Skill |

---

## 4. 推荐抽取模块清单（按优先级）

### Tier 1：建议优先抽取（低风险、高价值）

#### 4.1 `rsdp-cad-parser`（C# .NET 7）

- **职责边界**：只接收 CAD 文件，输出房间/未命名空间多边形、墙体、家具候选、尺寸、预览 PNG、质量问题报告。
- **输入**：`multipart/form-data` DWG/DXF + options（图层映射、 tolerance 等）。
- **输出**：`CadParseResult` JSON（rooms、unnamedRegions、walls、dimensions、furniture、preview、qualityIssues）。
- **抽取方式**：**独立容器/服务部署**，Java 原型后端通过 HTTP `/parse` 调用。
- **耦合点**：无 RSPU/RSKU/用户模型依赖，仅输出几何事实。
- **风险**：
  - 办公建筑图比家装户型图更复杂（大开间、柱网、幕墙），需调参/新增墙体策略。
  - PDF/PNG 不能走 CAD Parser，需视觉通道补充。

#### 4.2 `FloorPlanParserRegistry` + `CadFloorPlanParser`（Java 适配层）

- **职责边界**：按文件类型路由到 CAD 或 Vision 解析器，归一化为 `FloorPlanParseResult`。
- **输入**：文件 URL/bytes、sourceType（CAD/VISION）。
- **输出**：统一解析结果对象。
- **抽取方式**：作为 **Java 库/模块** 复制到 原型后端，替换 RSDP 特定的异步任务与落库逻辑。
- **耦合点**：仅依赖 `rsdp-cad-parser` HTTP 契约和 `VisionService`。
- **风险**：Vision 分支耦合 RSDP 字典与 Prompt；原型可暂时只保留 CAD 分支。

#### 4.3 `RoomDimensionRules` + `SizeSpecParser`（尺寸规则工具）

- **职责边界**：根据房间类型、面积、开间/进深等硬规则，筛选适合的产品候选或给出容量上限。
- **输入**：房间事实（类型、尺寸、面积）、产品候选（尺寸文本/包围盒）。
- **输出**：通过/不通过 + 原因。
- **抽取方式**：**纯工具类库**，抽象 `CandidateProduct` 接口后即可复用。
- **耦合点**：原输入依赖 RSPU 品类与 RSKU 报价标记，需去掉这些字段。
- **风险**：规则偏家装（客厅/卧室/餐厅），办公工位/会议室/茶水间需新增规则。

---

### Tier 2：需要适配后抽取（中等风险）

#### 4.4 空间/户型数据模型（`floor_plan_analysis` 族表）

涉及表：
- `floor_plan_analysis`
- `floor_plan_scene_revision`
- `floor_plan_room`
- `floor_plan_furniture`
- `cad_analysis_feedback`

- **职责边界**：管理一次图纸分析批次、多版本 Revision、房间/家具对象、人工校正 Diff。
- **输入**：文件资源 ID、解析结果 JSON、用户校正指令。
- **输出**：已确认的房间/家具列表、激活的 Revision。
- **抽取方式**：**带少量伴生表迁移**，剥离 `ProjectService`/`ImageAssets`/权限/异步任务后使用。
- **耦合点**：
  - 头表依赖 `project_id`（可空）。
  - 依赖 `image_assets` 存储源文件与预览图。
  - `FloorPlanService` 内嵌异步任务、用户权限校验。
- **风险**：
  - 若只复用 schema，需要自行实现文件上传与任务调度。
  - Scene Revision 的“激活指针”语义对办公项目的“返回总览再进入单空间”很有价值，建议保留。

#### 4.5 画布布局持久化（`scheme.canvas_layout`）

- **职责边界**：保存前端画布上家具/对象的相对坐标、缩放、旋转、层级。
- **输入**：`SaveCanvasLayoutRequest`（JSON 数组）。
- **输出**：`scheme` 表中的 `canvas_layout` JSONB。
- **抽取方式**：**参考实现**，在 原型自己的“方案/空间”表中增加 JSONB 字段。
- **耦合点**：`SchemeService` 强依赖 RSPU/RSKU/项目；不建议整体抽取。
- **风险**：原型的 canvas 对象（zone/note/arrow/reference）远超 RSDP 的 furniture 摆位，需要扩展 schema。

---

### Tier 3：不建议直接抽取，建议 API 调用或 mock（高风险/强耦合）

#### 4.6 产品主数据（RSPU/RSKU）

- **涉及表**：`rspu_master`、`rspu_variant`、`rsku_supply`、`rspu_style`、`rspu_scene`、`image_assets`、`category_dict`、`six_dim_schema`、`factory_master` 等。
- **不建议抽取原因**：
  - `rspu_master` 被 15+ 张表 FK 引用。
  - `rsku_supply.factory_price` 为 AES-256-GCM 密文，需带走加密 TypeHandler 与密钥。
  - 六维标签、品类字典、风格场景强绑定 RSDP 运营体系。
- **推荐做法**：
  - **原型阶段**：用 mock 办公商品数据（JSON/CSV），不抽产品模块。
  - **正式阶段**：通过 RSDP 公开商品 API（`/api/v1/public/products`）或内部 API 读取；若要独立部署，需整体迁移“产品核心包”。

#### 4.7 检索/推荐/Agent

- **涉及服务**：`RetrievalService`、`AiMatchingService`、`AgentStreamService`、`MarketingAgentGraphConfig`、`MarketingProductQueryService`、各 `AgentNode`。
- **不建议抽取原因**：
  - 深度依赖 `rspu_master`、向量表 `product_image_embedding`、pgvector、价格权限。
  - 现有 Skill/Node 围绕家装营销对话设计，不能直接回答办公选品。
- **推荐做法**：
  - **原型阶段**：规则推荐 + mock 数据。
  - **正式阶段**：新增 `OfficeSkill` 接入 `MarketingProductQueryService` 的查询接口；流式对话复用 `AgentStreamService` 的 SSE 框架。

#### 4.8 定价/报价/方案

- **涉及服务**：`PricingService`、`QuoteService`、`SchemeService`、`AgentQuoteService`、`AgentSchemeExportService`。
- **不建议抽取原因**：
  - 售价解析链 `retail_price → pricing_rule → sys_config` 与 RSDP 价格体系强耦合。
  - 报价涉及 AES 出厂价、工厂交期、权限掩码。
- **推荐做法**：
  - **原型阶段**：前端根据 mock 单价 × 数量计算总价。
  - **正式阶段**：调用 RSDP `/api/v1/quotes/generate` 或 `/api/v1/schemes/{id}/quote`。

---

## 5. 抽取策略建议

### 5.1 最小可运行 原型后端（MVP）

如果目标只是“快速验证”4 个页面交互，后端可极轻量：

```
office-backend/
├── cad-parser-bridge/          # 调用 rsdp-cad-parser（Tier 1）
├── office-space-service/       # 简化版 project + floor_plan_analysis + floor_plan_room
├── mock-product-service/       # mock 办公商品 + 规则推荐
├── mock-quote-service/         # mock 单价 × 数量
└── canvas-state-service/       # 画布对象 CRUD + 版本快照
```

### 5.2 与 RSDP 长期共存的抽取形态

如果目标是“最终达到交付程度”并与 RSDP 主站共享商品/价格/权限，建议：

| 模块 | 形态 |
|---|---|
| `rsdp-cad-parser` | 独立微服务，原型与 RSDP 共用 |
| 办公空间识别 | 新增 `office-vision-service`，复用 `VisionService` 的模型调用框架 |
| 项目/户型/Revision | 复用 RSDP schema，通过 API 或只读副本访问 |
| 商品/价格/报价 | **不抽取**，通过 RSDP 内部/公开 API 调用 |
| Agent | 新增 `OfficeSkill`，复用 SSE 会话框架 |
| 用户权限 | 复用 JWT + RBAC；若独立部署，需同步 `sys_user`/`company` 基础信息 |

### 5.3 数据库层面建议

- **独立原型 库**：早期使用独立 PostgreSQL，只导入 Tier 1/2 的表结构。
- **产品数据**：先用 CSV/JSON mock，避免一次性迁移 RSPU/RSKU 全 schema。
- **向量检索**：原型阶段不需要 pgvector；正式接入时再考虑同步商品向量。

---

## 6. 风险清单与实施顺序

| 优先级 | 风险项 | 缓解措施 |
|---|---|---|
| P0 | 办公建筑 CAD 解析效果不达预期（柱网、大开间、幕墙） | 先用 2~3 张真实办公 DWG 跑 `rsdp-cad-parser` 做 POV 验证 |
| P0 | 原型期望“AI 自动识别空间分区”，但 RSDP 识别偏家装户型 | 明确 原型是否允许人工圈选兜底；必要时新增办公视觉模型 |
| P1 | 产品/报价模块耦合过深，抽取代价大 | 原型用 mock；正式通过 API 调用 RSDP |
| P1 | 画布对象远超 RSDP 现有 schema（zone/note/arrow/reference） | 新建 原型专用 canvas 模型，不与 RSDP `scheme.canvas_layout` 强绑 |
| P2 | 用户权限/企业数据范围 | 原型可先做单用户/单企业；后续复用 JWT + RBAC |
| P2 | 多页 PDF 支持 | 扩展 `VisionFloorPlanParser` 或新增 PDF 分页服务 |
| P3 | 效果图/渲染图生成 | 不在 RSDP 现有能力内，需额外评估 3D 渲染或 AI 生成 |

### 推荐实施顺序

1. **Week 1**：部署 `rsdp-cad-parser`，用真实办公 DWG 验证房间/墙体/家具 footprint 输出。
2. **Week 2**：搭建 原型后端骨架（project、floor_plan_analysis、floor_plan_room 简化版）。
3. **Week 3**：实现 Page 1（上传 CAD → 解析 → 人工校正）+ Page 2（画布总览 + 分区持久化）。
4. **Week 4**：mock 办公商品数据 + 规则推荐 + 方案购物车，实现 Page 3。
5. **Week 5**：mock 报价合计 + BOM 导出，实现 Page 4。
6. **Week 6+**：逐步替换 mock：接入 RSDP 商品 API、价格 API、Agent SSE 框架、新增 Office Skill。

---

## 7. 需要用户决策的未决问题

在动手抽取代码前，请确认以下几点：

1. **抽取粒度**：
   - A) 仅把 `rsdp-cad-parser` 作为独立服务调用，其余 原型后端全新开发。
   - B)  additionally 抽取 Java 适配层 + 空间数据模型，形成一个小型独立后端。
   - C) 期望与 RSDP 主服务共用商品/价格/权限，通过 API 调用而非代码抽取。

2. **数据源**：
   - 办公商品数据是否复用 RSDP 现有 RSPU/RSKU？还是先 mock 50~100 条办公 SKU？

3. **CAD 图纸来源**：
   - 是否有真实办公 DWG/PDF 样本用于验证解析效果？原型是否接受人工圈选作为 fallback？

4. **AI 识别范围**：
   - 需要自动识别办公功能区（开放工位、会议室、茶水间等），还是允许用户在画布上手动划分？

5. **报价真实性**：
   - 原型阶段是否只需要“参考金额”，还是必须连接真实 RSKU 报价？

6. **部署边界**：
   - 原型后端是独立数据库独立部署，还是与 RSDP 共用数据库/服务？

---

## 8. 结论

**最优先抽取的模块**：
- `rsdp-cad-parser`（C# .NET）：独立部署，HTTP 调用。
- `FloorPlanParserRegistry` + `CadFloorPlanParser`（Java）：作为解析路由与适配层。
- `RoomDimensionRules` / `SizeSpecParser`：抽象接口后作为尺寸规则库复用。

**建议通过 API 调用而非代码抽取**：
- 产品主数据（RSPU/RSKU）
- 检索/推荐/Agent
- 定价/报价/方案

**需要新建的部分**：
- 办公项目/客户模型
- 画布标注对象（zone/note/arrow/reference）持久化
- 办公空间分区与版本快照
- mock 办公商品与报价服务

**下一步建议**：先用真实办公 DWG 跑通 `rsdp-cad-parser`，验证几何解析质量；同时确认上述 6 个未决问题的答案，再决定 Tier 2/3 模块的取舍。
