# RSDP 办公

项目目标：商品入库与上下架；CAD/PDF/PNG 空间识别；在无限画布核对并编辑空间；确认空间后进行家具排版，再推荐商品，确认方案后生成效果图。

开发文档从 [文档入口](docs/README.md) 开始，修改规则见 [AGENTS.md](AGENTS.md)，当前实现、缺口与下一步见 [进度表](docs/status.md)。目标流程尚未完整实现，进度以实际验收证据为准。

## Docker 基础设施

在项目根目录执行：

```bash
docker compose up -d --build --wait postgres cad-parser
```

仅启动 PostgreSQL 和独立 CAD 解析服务，前后端在本地启动。

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | localhost:5434，数据库 `office`，用户名/密码均为 `postgres` |
| CAD 解析 | `POST http://localhost:8091/parse` |

数据库使用独立的 `backend_postgres_data` 数据卷。首次创建空数据卷时自动执行 `backend/src/main/resources/db/schema.sql`；已有数据卷不会重复初始化。Compose 项目名为 `backend`。

CAD 服务源码在 `backend/cad-parser/`，归属后端目录，抽取自本机 RSDP 项目的 `rsdp-cad-parser/`。Java 业务后端和 .NET CAD 解析服务各自构建和运行。CAD 宿主机端口使用 `8091`，与原 RSDP 的 `8090` 服务互不冲突。

上传 DWG/DXF 测试（把路径换为实际图纸）：

```bash
curl -F 'file=@图纸.dxf' http://localhost:8091/parse
```

CAD 服务支持 DWG/DXF，返回房间、墙体、家具候选、尺寸及预览等解析结果。PDF/PNG 不走该解析接口。

当前前端已通过 Java `POST /api/v1/cad/parse` 转发到 CAD 服务，可显示解析结果并应用到画布。现有导入将房间转成矩形包围盒，真实轮廓、底图对齐和完整办公样本流程仍待完善与验收。

PDF/PNG 通过 Java `POST /api/v1/vision/recognize` 调用配置的识别服务；`rsdp.vision.url` 未配置时返回带 `MOCK_RECOGNITION` 标识的演示结果，不能视为真实识别。

## 本地启动前后端

后端使用 IDEA 的 `OfficeProductImportApplication`，或者在 `backend/` 执行：

```bash
mvn spring-boot:run
```

后端监听 `http://localhost:8082`，默认连接上述 PostgreSQL。

前端在 `frontend/` 执行：

```bash
pnpm install
pnpm dev --port 3001
```

访问 `http://localhost:3001`，前端默认调用本地 `8082` 后端。

## 状态和日志

```bash
docker compose ps
docker compose logs -f cad-parser postgres
docker compose stop
```

`docker compose stop` 保留数据库数据。
