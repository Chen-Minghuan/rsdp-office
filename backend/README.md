# office-product-import

办公产品 Excel 导入模块（从 RSDP 抽取并简化）。

`src/` 为 Java 业务后端；`cad-parser/` 为独立的 .NET CAD 解析服务，统一归属后端目录。CAD 由 Docker 构建运行，接口为 `POST http://localhost:8091/parse`。

图片/PDF 识别使用 `qwen3-vl-plus`，在 Java 启动环境中填写 `DASHSCOPE_API_KEY` 后重启；页码与完整配置见 [识别配置](../docs/vision-configuration.md)。未配置密钥不会返回演示空间。

## 功能

- 下载产品导入模板（商品、规格、价格与图片字段）
- 上传 Excel 批量导入办公产品
- 支持单工厂报价（工厂价 / MOQ / 交期）
- 支持产品变体（尺寸 / 颜色 / 材质）
- 商品规格用毫米尺寸参与画布候选匹配

## 启动基础设施

在项目根目录执行 `docker compose up -d --build --wait postgres cad-parser`，会启动 PostgreSQL 和独立 CAD 解析服务。Java 后端通过 IDEA 或 Maven 本地启动。详见根目录 `README.md`。

## 本地运行 Java 后端

### 1. 启动 PostgreSQL

```bash
docker compose up -d postgres
```

这会创建数据库 `office` 并自动执行 `schema.sql` 初始化字典数据。

### 2. 运行应用

```bash
mvn spring-boot:run
```

服务默认监听 `8082` 端口。

### 3. 下载模板

```bash
curl -o 办公产品导入模板.xlsx 'http://localhost:8082/api/v1/products/import-template'
```

### 4. 导入 Excel

项目已提供样例数据 `samples/sample-products.xlsx`，可直接导入测试：

```bash
curl -X POST 'http://localhost:8082/api/v1/products/import' \
  -F 'file=@samples/sample-products.xlsx'
```

## 数据库配置

默认连接：

```
url: jdbc:postgresql://localhost:5434/office
username: postgres
password: postgres
```

可在 `application.yml` 中覆盖。

## Excel 关键字段

| 字段 | 说明 |
|---|---|
| 商品名称 | 必填，用于商品识别与展示 |
| 工厂价 | 单工厂报价 |
| 宽度(mm) / 进深(mm) | 正整数毫米，必须成对填写；缺少时不会进入当前画布尺寸候选 |
| 高度(mm) | 可选正整数毫米；原文有多个高度选项时须人工核对 |
| 包数 / 体积(CBM) / 重量(原表值) | 供应商 G/H/I 列，按变体保存；包数与商品数量分开，重量单位仍须核对 |
| MOQ | 最小起订量 |
| 交期天数 | 整数 |

品类码以及旧定位、等级、质保、价格带、六维、材质/场景标签和关键规格字段已从导入模板、接口和商品主档结构移除。已有数据库会在 Java 服务下次启动时执行对应迁移删除这些列；其中已有值会丢失。需要留存旧资料时应先备份数据库。画布候选只使用变体的结构化毫米尺寸。

## 商品 Excel 内嵌图片

在商品批量导入页直接上传 `.xlsx`。预览会识别普通锚定图及 WPS `DISPIMG` 单元格图，按商品展示小尺寸图片及每行数量、编码冲突提示；单行预览最多展示 8 张。供应商表 A 列纵向合并图片区的图片会列给覆盖的每个商品行，用户可逐行取消不适用图片并选择一张主图；其余已选图片作为详情图。合并区域出现多个图片说明编码时会提示复核。未合并的图片区不会借用相邻行图片。缩略图仅在上传预览响应中返回，重新校验和确认请求不传缩略图，预览阶段不保存图片文件。无法解码或超出预览限制的图片会提示核对，不会凭客户端预览决定图片归属。确认时浏览器把原文件与编辑后的行一同提交；服务端重新按工作表、物理行号和原商品编码核对，并校验图片选择，仅在该行成功入库时保存已选图片并写入 `image_assets`。用户跳过、校验失败或已存在且选择跳过的行不会产生图片文件。重复导入按图片内容哈希复用已保存文件。

支持标准模板首工作表，以及使用 `ITEM NO.` / `ITEM NAME` 表头的多工作表供应商目录。后者读取 B 列编码、C 列名称、D 列颜色、E 列尺寸原文、F 列说明、G 列包数、H 列体积（CBM）、I 列重量原表值、J 列展示价格和 L 列出厂价格。F 列的多行说明保留段落换行；纵向合并的 F 列说明按可见首格内容提供给覆盖范围内各商品，未合并的空白说明不继承邻行。Excel 数值会先消除二进制浮点尾数，重量按最多 3 位小数、体积按最多 9 位小数归一；例如 `86.49999999999999` 保存为 `86.5`，不会为了显示而统一截成 1 位小数。当前指定供应商表的规格单位已由用户确认为毫米：严格三段规格自动拆成宽/进深/高；明确的双宽度写法展开为两个规格，并按用户确认的共用规则复制原行价格与包装数值；其他歧义或缺失只保留能确定的数值并提示复核。预览会提示缺尺寸、缺展示价和缺图；点击“重新校验”可检查编辑后的结果。变体尺寸保存为毫米 JSON，包装信息按变体独立存储；现有数据库在下次启动时执行 `db/migrations/20261014_variant_packaging.sql`。当前画布商品候选将毫米尺寸换算成厘米；缺宽深或缺展示价不进入候选。普通单行图片说明编码与商品编码冲突时不自动关联；合并图片区按覆盖范围列出并提示用户核对。`.xls` 和 `.csv` 仍可导入文本字段，但不提取内嵌图片。单文件上限 150 MB，单张图片上限 10 MB。

图片保存在 Java 进程工作目录的 `uploads/images/`。部署时应把此目录作为持久存储，并保持对外 `/uploads/images/` 路径可访问。旧 JSON 确认接口保留给无图客户端；需要自动提取图片的客户端须使用 multipart 确认接口。

## 技术栈

- Java 21
- Spring Boot 3.1.12
- MyBatis-Plus 3.5.9
- PostgreSQL 16
- EasyExcel 3.3.4

## 空间模板迁移与接口

空间模板使用独立的 `office_space_template` 表。已有数据库须显式迁移，应用启动的 `schema.sql` 不代替此操作。当前本地 Docker 环境可在仓库根目录执行：

```bash
docker exec -i office-postgres psql -v ON_ERROR_STOP=1 -U postgres -d office < backend/src/main/resources/db/migrations/V20261015__space_template.sql
```

接口为 `POST/GET /api/v1/office/projects/{projectId}/space-templates`、`GET /api/v1/office/projects/{projectId}/space-templates/{templateId}`。
保存请求含 name、clientChangeId、sourceCanvasRevision、可选 sourceAnalysisRevision、毫米 scene（现有 SpatialSceneSnapshot）。后端核验可计算几何/比例、重新推导面积，以不可变快照保存；逐空间确认不是前置条件。列表最近 50 条为摘要，详情含 scene。具体范围与实际验证见 [GEOM-004](../docs/tasks/GEOM-004-space-template.md)。
