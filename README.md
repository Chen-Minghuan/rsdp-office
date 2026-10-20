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

当前前端已通过 Java `POST /api/v1/cad/parse` 转发到 CAD 服务，可显示解析结果并应用到画布。现有工作台保留房间多边形，并支持底图对照与毫米编辑；真实 CAD 办公样本及完整业务流程仍待验收。

图片/PDF 通过 Java 直连百炼 `qwen3-vl-plus`；在后端启动环境变量中填写 `DASHSCOPE_API_KEY`，详情见 [识别配置](docs/vision-configuration.md)。PDF 可填写选定页码并渲染为模型输入。未配置密钥时明确报错，不再返回演示房间。图像结果的实际尺寸与面积待校准；配置 `rsdp.vision.url` 时可继续使用已有独立识别接口。

## 本地启动前后端

后端使用 IDEA 的 `OfficeProductImportApplication`，或者在 `backend/` 执行：

```bash
mvn spring-boot:run
```

后端监听 `http://localhost:8082`，默认连接上述 PostgreSQL。

本版移除了“项目信息”表单及专用资料表 `office_project`。下次启动新版 Java 时自动执行事务迁移，删除资料表和指向它的外键，保留现有画布、识别、模板、清单和预览数据；无需重建数据库或删除数据卷。应停止旧版 Java 后再启动新版。接口中的 projectId 继续作为设计隔离键，详见 [迁移记录](docs/tasks/WORKSPACE-001-remove-project-info.md)。

前端在 `frontend/` 执行：

```bash
pnpm install
pnpm dev --port 3001
```

访问 `http://localhost:3001`，前端默认调用本地 `8082` 后端。

## 状态和日志

服务器 Jenkins 流水线、生产容器、`.env` 路径及 HTTPS 接入说明见 [部署文档](docs/deployment.md)。生产入口为 `compose.deploy.yaml`，本地开发仍使用上面的原有命令。

```bash
docker compose ps
docker compose logs -f cad-parser postgres
docker compose stop
```

`docker compose stop` 保留数据库数据。
