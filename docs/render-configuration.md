# Qwen-Image-3.0 单空间效果预览

更新日期：2026-10-15。模型默认 `qwen-image-3.0`，输出带透视、材质和光影的二维 PNG；不需要三维模型。

## 使用方式

1. 在 `/canvas` 进入单空间设计，校准实际比例并摆放家具。
2. 点击画布工具栏 **效果预览**，填写装修风格，选择 **45° 鸟瞰** 或 **室内透视**，可补充材质、灯光偏好。
3. 点击 **生成效果预览**。系统先保存当前排版，再提交该空间快照和后端生成的布局参考 PNG。
4. 等待生成，可关闭窗口继续编辑；重新打开会读取历史任务并查询进行中的任务。完成后可查看参考布局、下载图片。

当前入口明确是 **概念效果预览**，允许尚未确认排版、尚未选商品的模块生成，不会确认排版/商品/正式方案。
空间必须有有效毫米多边形，家具必须有有效实际尺寸、位置和朝向；后端检查当前版本、快照一致性、越界与重叠。单次最多 100 个家具模块。
图中的家具数量、位置和款式仍需对照排版核对；未提供的层高、门窗、材质由模型补充，不能当作已有建筑信息。演示空间的快照保留 `evidenceKind=DEMO`。

## 后端配置

沿用 `~/.config/rsdp-office/vision.properties` 的本机配置导入，或在 Java 启动环境变量中填写：

```properties
DASHSCOPE_API_KEY=你的百炼密钥
RSDP_RENDER_MODEL=qwen-image-3.0
RSDP_RENDER_BASE_URL=https://dashscope.aliyuncs.com/api/v1
```

| 配置 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 已有识别密钥默认同时供生图使用；账号需开通对应模型和地域权限 |
| `RSDP_RENDER_API_KEY` | 可选独立生图密钥；不需要时省略，显式空值不会回退 |
| `RSDP_RENDER_BASE_URL` | 百炼**原生 API** 基地址，末尾到 `/api/v1`；与识别的 `/compatible-mode/v1` 不同 |
| `RSDP_RENDER_MODEL` | 默认 `qwen-image-3.0`；也支持显式改为 `qwen-image-3.0-pro`，不自动回退模型 |
| `RSDP_RENDER_STORAGE_DIRECTORY` | 本地图片目录，默认 `uploads/render-previews`；由后端提供 `/uploads/render-previews/**` |

支持业务空间专属地址，例如 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1`；新加坡地址为 `https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api/v1`（旧地址 `https://dashscope-intl.aliyuncs.com/api/v1`）。密钥与地域需对应。
生图配置独立于 `DASHSCOPE_BASE_URL` 和 `RSDP_VISION_URL`，修改后重启后端；不替换现有 `qwen3-vl-plus` 识别模型。
Compose 已传入生图配置并为默认图片目录配置独立持久卷。手动部署同时备份数据库和图片目录；静态图片路径按本项目现有公开资源方式提供，不代表新增了用户权限系统。

## 接口与状态

项目路径：`/api/v1/office/projects/{projectId}/render-previews`。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `POST` | 项目路径 | 请求 `clientChangeId`、`zoneId`、`canvasRevision`、`expectedSnapshot`、`style`、`view`、`instructions`；校验已保存画布并提交异步任务 |
| `GET` | 项目路径 + `?zoneId=…` | 最近 30 条当前空间的任务，含不可变快照；不触发收费生成 |
| `GET` | 项目路径 + `/{previewId}` | 查询任务；进行中时访问百炼，成功后下载 PNG 并保存；不会创建新生图任务 |

`view` 为 `ISOMETRIC` 或 `INTERIOR`。快照的字段投影见前端 `renderPreview.ts` 与后端 `RenderPreviewScene`，包含空间轮廓、家具毫米位置/尺寸/旋转、目录快照和已选商品引用，不包含视图缩放、便签或确认标记。
所有结果固定 `purpose=CONCEPT_PREVIEW`，包含实际 `model`、任务/请求 ID、来源快照、生成参数和时间。修改当前空间、排版或商品选择后，旧图保留并标为历史；其他空间和仅视图变化不会导致此空间预览失效。

百炼创建任务使用 `POST /api/v1/services/aigc/image-generation/generation`，带 `X-DashScope-Async: enable`；查询使用 `GET /api/v1/tasks/{task_id}`。提交 1 张布局参考图和 1 条文字提示，`n=1`、`size=1536*1024`、`prompt_extend=false`，避免自动改写扩充家具需求。
本地状态为 `SUBMITTING → PENDING/RUNNING → SUCCEEDED/FAILED`；服务端以工作区事务 advisory lock（按 projectId，与画布保存/选品共用）串行化同一提交，以任务行锁防止同时查询/保存结果。
重复的 `clientChangeId` 和相同内容返回原任务；不同内容不能复用同一 ID。前端在会话存储保留结果不明的请求 ID；相同参数重试复用 ID。明确失败后按当前布局重试会创建新任务。
连接超时可能已在供应商受理，页面会提示核对百炼记录；没有任务 ID 时无法查询或保证供应商侧去重，不自动重提交。

## 存储与失败处理

启动期幂等建立 `office_render_preview` 表，保存方案外的预览快照、参数、模型、任务状态和本地资源地址；不存储 API Key。
布局参考图与效果 PNG 保存到配置目录，下载成功才标记 `SUCCEEDED`。只下载供应商返回的 HTTPS 阿里云 OSS 地址，限制文件大小和像素数，且不向图片地址转发密钥。
百炼任务及临时图片地址有效期约 24 小时。窗口打开时每 3 秒查询任务；关闭后暂停查询，重新打开恢复。**当前没有后台常驻任务回收器**，若长期不打开窗口，23 小时后的未完成任务会标记超时，可能需要重新生成。
查询或图片下载暂时失败时保留原任务，可点 **重新查询任务**；不自动重新计费。未配置密钥、权限/地域错误、限流、任务失败均明确反馈，无演示结果回退。
项目重置不删除预览历史；被删除空间的任务继续保存在数据库，当前 UI 只提供当前空间记录入口，不提供全项目归档管理。

正式“已确认商品方案 → 效果图”的条件核验、真实商品外观还原与精确对应验收仍属于 RENDER-001 后续范围；本入口不声称完成这些能力。

参考：[千问图像生成与编辑 3.0 官方接口](https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference)。
