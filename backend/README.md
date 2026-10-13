# office-product-import

办公产品 Excel 导入模块（从 RSDP 抽取并简化）。

`src/` 为 Java 业务后端；`cad-parser/` 为独立的 .NET CAD 解析服务，统一归属后端目录。CAD 由 Docker 构建运行，接口为 `POST http://localhost:8091/parse`。

## 功能

- 下载产品导入模板（含六维标签列）
- 上传 Excel 批量导入办公产品
- 支持单工厂报价（工厂价 / MOQ / 交期）
- 支持产品变体（尺寸 / 颜色 / 材质）
- 支持风格、场景关联
- 六维标签以 JSON 形式存储到 `rspu_master.six_dim_tags`

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
| 品类码 | 必填，如 `DK`（办公桌）、`CH`（办公椅）、`OF`（办公家具） |
| 六维标签 | JSON 字符串，如 `{"A":["A1"],"B":["B2"],"C":["MC"],"D":["MESH"],"E":["中端"],"F":["M"]}` |
| 工厂价 | 单工厂报价 |
| MOQ | 最小起订量 |
| 交期天数 | 整数 |

## 技术栈

- Java 21
- Spring Boot 3.1.12
- MyBatis-Plus 3.5.9
- PostgreSQL 16
- EasyExcel 3.3.4
