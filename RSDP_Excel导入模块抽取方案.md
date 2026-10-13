# RSDP Excel 产品导入模块抽取方案

> 目标：只抽取供应链端的 Excel 产品导入能力，支撑办公项目的单工厂产品录入，必须支持 6 维标签。
> 约束：AI 自研、前端自研、单工厂、数据库重设计。
> 日期：2026-10-09

---

## 1. 核心结论

**只抽取「传统 RSPU Excel 导入」链路，不抽取「Excel AI 辅助导入」。**

理由：
- 传统导入已完整支持 `sixDimTags` 字段。
- 传统导入同步执行，依赖最少。
- Excel AI 导入（`ExcelAiImportService`，约 8241 行）依赖 Snapshot/Revision/Attempt、异步任务、AI 服务、供应配置等大量 RSDP 专用底座，抽取代价过高。

---

## 2. 建议抽取的文件清单

### 2.1 核心导入链路（必选）

| 文件路径 | 说明 |
|---|---|
| `server/src/main/java/com/rsdp/controller/ProductImportController.java` | `/api/v1/products/import`、`/api/v1/products/import-template` |
| `server/src/main/java/com/rsdp/service/ProductImportService.java` | 传统 RSPU 导入主服务 |
| `server/src/main/java/com/rsdp/dto/excel/ProductImportRow.java` | Excel 行 DTO，含 `sixDimTags` 字段 |
| `server/src/main/java/com/rsdp/dto/response/ProductImportResult.java` | 导入结果响应 |
| `server/src/main/java/com/rsdp/dto/response/ProductImportFailure.java` | 失败行明细 |
| `server/src/main/java/com/rsdp/util/ProductImportTemplateBuilder.java` | 模板下载生成器 |

### 2.2 通用工具（必选）

| 文件路径 | 说明 |
|---|---|
| `server/src/main/java/com/rsdp/util/ExcelFileValidator.java` | Excel/CSV 魔数校验 |
| `server/src/main/java/com/rsdp/util/DictNormalizes.java` | 字典码归一 |
| `server/src/main/java/com/rsdp/util/CategoryPaths.java` | 品类路径解析 |
| `server/src/main/java/com/rsdp/util/SizeSpecParser.java` | 尺寸文字解析 |
| `server/src/main/java/com/rsdp/util/ContentHashes.java` | 内容哈希 |
| `server/src/main/java/com/rsdp/util/ConstraintViolations.java` | 约束异常转文案 |

### 2.3 字典与编码服务（必选）

| 文件路径 | 说明 |
|---|---|
| `server/src/main/java/com/rsdp/service/DictService.java` | 字典查询 |
| `server/src/main/java/com/rsdp/service/DictResolverService.java` | 按名解析字典码 |
| `server/src/main/java/com/rsdp/service/DictAliasService.java` | 别名库 |
| `server/src/main/java/com/rsdp/service/RspuCodeService.java` | RSPU 业务编码发号 |

### 2.4 实体与 Mapper（必选，可简化）

| 文件路径 | 说明 |
|---|---|
| `server/src/main/java/com/rsdp/entity/RspuMaster.java` | 产品主档 |
| `server/src/main/java/com/rsdp/mapper/RspuMasterMapper.java` | |
| `server/src/main/java/com/rsdp/entity/RspuVariant.java` | 默认变体（传统导入会创建默认 variant） |
| `server/src/main/java/com/rsdp/mapper/RspuVariantMapper.java` | |
| `server/src/main/java/com/rsdp/entity/RspuStyle.java` / `RspuScene.java` | 风格/场景关联 |
| `server/src/main/java/com/rsdp/mapper/RspuStyleMapper.java` / `RspuSceneMapper.java` | |
| `server/src/main/java/com/rsdp/entity/ImageAssets.java` | 图片资产 |
| `server/src/main/java/com/rsdp/mapper/ImageAssetsMapper.java` | |
| `server/src/main/java/com/rsdp/entity/CategoryDict.java` | 品类字典 |
| `server/src/main/java/com/rsdp/mapper/CategoryDictMapper.java` | |
| `server/src/main/java/com/rsdp/entity/SixDimSchema.java` | 六维定义（可选，仅前端展示用） |
| `server/src/main/java/com/rsdp/mapper/SixDimSchemaMapper.java` | |

### 2.5 支撑服务（按 原型要求简化）

| 文件路径 | 说明 | 简化建议 |
|---|---|---|
| `server/src/main/java/com/rsdp/service/RspuVariantService.java` | 变体创建 | 保留默认变体创建逻辑 |
| `server/src/main/java/com/rsdp/service/RspuAssociationHelper.java` | 风格/场景关联重建 | 保留 |
| `server/src/main/java/com/rsdp/service/StorageService.java` | 文件存储 | 可替换为本地/MinIO 简单实现 |
| `server/src/main/java/com/rsdp/service/AuditLogService.java` | 审计日志 | 原型可降级为操作日志 |
| `server/src/main/java/com/rsdp/security/SecurityOperatorContext.java` | 当前操作人 | 原型可硬编码或从 JWT 取 |
| `server/src/main/java/com/rsdp/security/datascope/DataScopeHelper.java` | 数据权限 | 原型可删除 |

---

## 3. 明确不抽取的模块

| 模块 | 不抽理由 |
|---|---|
| `RskuImportService` / `RskuImportController` | 单工厂不需要独立报价导入 |
| `ExcelAiImportService` 全链路 | 太重，依赖 AI/异步/Snapshot/Attempt |
| `ExcelImportPreviewRevision` / `ProductGroup` / `Attempt` 族表 | Excel AI 导入底座 |
| `AsyncTaskProcessor` / `AsyncTask` | 传统导入同步执行 |
| `VisionService` / AI 识别 | AI 自研 |
| `FactoryLeadTimeRule` / 动态交期 | 报价层，单工厂可硬编码 |
| `PriceHistory` | RSKU 更新历史 |
| `RetrievalService` / `PgvectorProductVectorStore` | 不需要向量检索 |
| `SupplierProductRef` / `SupplierIdentityResolutionService` | 供应商身份解析，可降级 |

---

## 4. 简化后的数据库表设计

只保留导入和查询必需的最小集合：

```sql
-- 品类字典
create table category_dict (
  id bigserial primary key,
  dict_type varchar(50) not null,   -- 'category' / 'style' / 'scene' / 'material' / 'size' / 'color'
  dict_code varchar(50) not null,
  dict_name varchar(100) not null,
  parent_code varchar(50),
  path varchar(255),
  unique(dict_type, dict_code)
);

-- 六维维度定义（仅前端展示/校验用，不强依赖）
create table six_dim_schema (
  id bigserial primary key,
  category_code varchar(50) not null,
  dim_code char(1) not null,        -- A/B/C/D/E/F
  dim_name varchar(50) not null,
  allowed_values jsonb,
  unique(category_code, dim_code)
);

-- 产品主档
create table rspu_master (
  id bigserial primary key,
  rspu_code varchar(50) unique,     -- 业务编码，可自动生成
  external_code varchar(100),       -- 工厂/供应商编码
  category_code varchar(50),
  product_name varchar(255) not null,
  positioning_label varchar(100),
  product_level varchar(20),
  warranty_years int,
  reference_price_band varchar(20),
  retail_price decimal(12,2),
  six_dim_tags jsonb,               -- 六维标签，如 {"A":["A1"],"B":["B2"]}
  key_specs jsonb,
  material_tags jsonb,
  scene_tags jsonb,
  description text,
  primary_image_url varchar(500),
  detail_image_urls jsonb,
  status varchar(20) default 'active',
  created_at timestamp default now(),
  updated_at timestamp default now()
);

-- 默认变体（传统导入会创建至少一个 variant）
create table rspu_variant (
  id bigserial primary key,
  rspu_id bigint not null references rspu_master(id),
  variant_code varchar(50),
  display_name varchar(255),
  size_text varchar(100),
  dimensions jsonb,                 -- {"w":1200,"d":600,"h":750,"unit":"mm"}
  color_text varchar(100),
  material_text varchar(100),
  reference_price_band varchar(20),
  status varchar(20) default 'active'
);

-- 风格/场景关联（多对多）
create table rspu_style (
  id bigserial primary key,
  rspu_id bigint not null references rspu_master(id),
  style_code varchar(50) not null
);

create table rspu_scene (
  id bigserial primary key,
  rspu_id bigint not null references rspu_master(id),
  scene_code varchar(50) not null
);

-- 图片资产
create table image_assets (
  id bigserial primary key,
  asset_type varchar(50),           -- 'product'
  ref_id bigint,                    -- rspu_id or variant_id
  url varchar(500) not null,
  sort_order int default 0
);

-- 单工厂信息（可只有一条记录）
create table factory_master (
  id bigserial primary key,
  factory_code varchar(50) unique not null,
  factory_name varchar(100),
  contact_info jsonb
);

-- 工厂产品报价（单工厂简化版，替代 rsku_supply 复杂模型）
create table factory_product_price (
  id bigserial primary key,
  rspu_id bigint not null references rspu_master(id),
  variant_id bigint references rspu_variant(id),
  factory_id bigint not null references factory_master(id),
  factory_price decimal(12,2),
  moq int default 1,
  lead_time_days int,
  effective_date date,
  unique(rspu_id, variant_id, factory_id)
);
```

---

## 5. Excel 模板字段

基于 `ProductImportRow.java`，原型模板至少包含：

| 中文表头 | 字段 | 必填 | 说明 |
|---|---|---|---|
| 品类编码 | categoryCode | 是 | 对应 `category_dict.dict_code` |
| 产品名称 | productName | 是 | |
| 工厂编码 | externalCode | 否 | 单工厂可省略或固定 |
| 定位标签 | positioningLabel | 否 | |
| 产品等级 | productLevel | 否 | |
| 质保年限 | warrantyYears | 否 | |
| 价格带 | referencePriceBand | 否 | |
| 建议零售价 | retailPrice | 否 | |
| **六维标签** | **sixDimTags** | **否** | **JSON 字符串，如 `{"A":["A1"],"B":["B2"]}`, 本次重点** |
| 关键规格 | keySpecs | 否 | JSON 字符串 |
| 材质标签 | materialTags | 否 | JSON 字符串 |
| 场景标签 | sceneTags | 否 | JSON 字符串 |
| 主图URL | primaryImageUrl | 否 | |
| 详情图URLs | detailImageUrls | 否 | 逗号或分号分隔 |
| 产品描述 | description | 否 | |
| 变体显示名 | variantDisplayName | 否 | 不填则生成默认变体 |
| 尺寸描述 | sizeText | 否 | 如 `1200*600*750` |
| 颜色 | colorText | 否 | |
| 材质 | materialText | 否 | |

> 模板生成器 `ProductImportTemplateBuilder` 已包含 `六维标签` 列和示例值 `{"style":["MC"]}`，可直接复用。

---

## 6. 六维标签处理流程

```
Excel 单元格
  ↓
ProductImportRow.getSixDimTags()
  ↓
ProductImportService.validateJson()   -- 校验是否为合法 JSON
  ↓
ProductImportService.buildRspuFromRow()
  ↓
rspu_master.six_dim_tags (jsonb)
```

- 导入时只做 **JSON 格式校验**，不做 `six_dim_schema` 键值强校验（降低耦合）。
- 查询时使用 PostgreSQL `jsonb` 操作符或 `pg_trgm` 模糊匹配。

---

## 7. 单工厂简化点

| 原 RSDP 逻辑 | 简化后 |
|---|---|
| 多工厂 RSKU 报价导入 | 单工厂 `factory_product_price` 表，价格可随产品导入一起写入 |
| RSPU/变体/RSKU 三层分离 | 保留 RSPU + Variant，但 Variant 只作为尺寸/颜色规格；价格直接挂在 `factory_product_price` |
| 工厂数据范围 `DataScopeHelper` | 删除，只读/写默认工厂 |
| `RspuCodeService` 发号规则 | 可保留原规则，或简化为 `OFFICE-001` 自增 |
| 审计日志 `AuditLogService` | 降级为普通操作日志 |
| 异步任务 | 同步导入，前端轮询或等待 |

---

## 8. 实施步骤

1. **在 `rsdp-office/backend` 目录下维护导入模块**（建议 SpringBoot + EasyExcel + PostgreSQL）。
2. **复制 Tier 1 文件**：`ProductImportController`、`ProductImportService`、`ProductImportRow`、结果 DTO、模板生成器。
3. **复制/简化工具类**：Excel 校验、字典归一、品类路径、尺寸解析。
4. **复制字典服务**：`DictService`、`DictResolverService`、`DictAliasService`、`RspuCodeService`。
5. **简化实体**：按第 4 节 schema 建表，去掉 RSKU/价格历史/工厂能力等多余字段。
6. **改造写入逻辑**：把 `ProductImportService` 中对 `RskuSupply`/`FactoryLeadTimeRule`/`PriceHistory` 的调用删除，改为写入单工厂的 `factory_product_price`。
7. **删除数据权限**：移除 `DataScopeHelper`、复杂 RBAC，只保留简单登录校验。
8. **生成模板**：复用 `ProductImportTemplateBuilder` 生成含六维标签列的 Excel 模板。
9. **端到端测试**：准备 20~50 条办公 SKU 的 Excel，验证导入 + 六维标签落库 + 模糊搜索。

---

## 9. 下一步建议

- 如果你确认这个方案，我可以开始**实际抽取代码**到 `/Users/rooom01/work/rsdp-office/backend`。
- 或者先帮你**生成简化后的建表 SQL + Excel 模板 + 一个最小可运行的导入 Controller/Service 骨架**。
