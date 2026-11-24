# Qwen-Image-3.0 单空间效果预览

更新日期：2026-11-24。模型默认 `qwen-image-3.0`，输出带透视、材质和光影的二维 PNG；不需要三维模型。

## 使用方式

1. 在 `/canvas` 进入单空间设计，校准实际比例并摆放家具。
2. 点击画布工具栏 **AI 效果图**，或从3D白模点击 **AI 概念效果图**；填写装修风格和材质、灯光偏好。两个入口统一使用后端生成的 **45°斜俯视家具定位结构图**，不上传白模相机截图。
3. 点击 **生成效果预览**。系统先保存当前排版，再提交无F编号的45°结构图和无编号平面图，以及已选商品P编号参考板；提示词同时携带F/P文字映射、地面坐标和程序计算的排列特征。带F白模另存为审核附件，不参与生图。
4. 等待生成，可关闭窗口继续编辑；重新打开会读取历史任务并查询进行中的任务。完成后可查看参考布局、自动核对结果并下载图片。

空间设计的 **概念效果预览** 允许从尚未确认排版、尚未选商品的模块生成。完成楼层选品后，也可先在 **项目清单** 确认楼层，再进入 **产品方案 → 生成方案效果图**，请求将使用该确认版本。
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
生图配置独立于 `DASHSCOPE_BASE_URL` 和 `RSDP_VISION_URL`，修改后重启后端；不替换现有 Qwen-VL 识别模型。
Compose 已传入生图配置并为默认图片目录配置独立持久卷。手动部署同时备份数据库和图片目录；静态图片路径按本项目现有公开资源方式提供，不代表新增了用户权限系统。

## 接口与状态

楼层路径：`/api/v1/office/projects/{floorId}/render-previews`。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `POST` | 楼层路径 | 请求 `clientChangeId`、`zoneId`、`canvasRevision`、`expectedSnapshot`、`style`、`view`、`instructions`；正式生成另带 `confirmationId` |
| `GET` | 楼层路径 + `?zoneId=…` | 最近 30 条当前空间的任务，含不可变快照；不触发收费生成 |
| `GET` | 楼层路径 + `/{previewId}` | 查询任务；进行中时访问百炼，成功后下载 PNG 并保存；不会创建新生图任务 |

新任务的 `view` 固定为 `ISOMETRIC`；历史 `INTERIOR` 和白模截图任务仅保留读取。快照的字段投影见前端 `renderPreview.ts` 与后端 `RenderPreviewScene`，包含空间轮廓、家具毫米位置/尺寸/旋转、目录快照和已选商品引用，不包含视图缩放、便签或确认标记。
概念预览记录为 `CONCEPT_PREVIEW`，当前空间、排版或商品改变后标为历史。正式效果图记录为 `CONFIRMED_SCHEME`，保存楼层确认 ID，若确认失效即标为历史，晚到的结果仍绑定原输入。两种记录均包含实际模型、任务 ID、来源快照、参数和时间。

百炼创建任务使用 `POST /api/v1/services/aigc/image-generation/generation`，带 `X-DashScope-Async: enable`；查询使用 `GET /api/v1/tasks/{task_id}`。v10 未选品时提交2张无编号布局参考图，已选品时追加1张商品参考板；三图模式单次最多12种商品。`n=1`、`size=1536*1024`、`prompt_extend=false`，避免自动改写扩充家具需求。
本地状态为 `SUBMITTING → PENDING/RUNNING → SUCCEEDED/FAILED`；服务端以工作区事务 advisory lock（按 projectId，与画布保存/选品共用）串行化同一提交，以任务行锁防止同时查询/保存结果。
重复的 `clientChangeId` 和相同内容返回原任务；不同内容不能复用同一 ID。前端在会话存储保留结果不明的请求 ID；相同参数重试复用 ID。明确失败后按当前布局重试会创建新任务。
连接超时可能已在供应商受理，页面会提示核对百炼记录；没有任务 ID 时无法查询或保证供应商侧去重，不自动重提交。

## 存储与失败处理

启动期幂等建立 `office_render_preview` 表，保存效果图快照、参数、模型、任务状态、正式确认 ID 和本地资源地址；不存储 API Key。
布局参考图与效果 PNG 保存到配置目录，下载成功才标记 `SUCCEEDED`。只下载供应商返回的 HTTPS 阿里云 OSS 地址，限制文件大小和像素数，且不向图片地址转发密钥。
百炼任务及临时图片地址有效期约 24 小时。窗口打开时每 3 秒查询任务；关闭后暂停查询，重新打开恢复。**当前没有后台常驻任务回收器**，若长期不打开窗口，23 小时后的未完成任务会标记超时，可能需要重新生成。
查询或图片下载暂时失败时保留原任务，可点 **重新查询任务**；不自动重新计费。未配置密钥、权限/地域错误、限流、任务失败均明确反馈，无演示结果回退。
项目重置不删除预览历史；被删除空间的任务继续保存在数据库，当前 UI 只提供当前空间记录入口，不提供全项目归档管理。

正式方案的生成条件和验证范围见 [FLOW-007](tasks/FLOW-007-product-scheme.md)。真实供应商图片对应和百炼生成效果需继续验收。

参考：[千问图像生成与编辑 3.0 官方接口](https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference)。

## 所选商品外观参考（2026-10-20）

已选品模块会按后端可用商品目录读取图片、去重并生成无P文字的商品参考板，与无文字45°结构图一起发送给模型；最多24种商品、总计最多三张输入图。缺图或商品不可用时生成前报错，不退回通用家具。记录包含 productImagesUsed 和 productReferences（商品/规格/照片摘要、家具绑定及 sheetUrls），这些参考数据保留在后台，页面可展开核对实际使用的参考板。旧图没有自动补用照片，需要重新生成；尚未选品仍可生成概念预览。文件从本系统 uploads/images 读取，独立外链应先入库，格式不可解码时补充 PNG/JPG。详见 [RENDER-003](tasks/RENDER-003-selected-product-image-references.md)。

## 多角度商品参考与完整场景（2026-10-20）

新版从商品图库选择主图及当前变体/公共辅助图，去除重复内容，单款最多 4 个可用视角；每款合为同一 P 编号，照片数不表示家具数量。任务返回 usedImageCount/images，界面显示款数及使用图片张数。生成输入的第一张图改为无文字立体空间引导，原平面图作为生成依据核对附件，页面可展开查看；正向与 negative_prompt 均禁止中央图板、平面叠图、文字清单和分栏拼贴。实际引导图以 layoutGuideUrl 保留，旧任务不会更新成新输入。规则和验证见 [RENDER-004](tasks/RENDER-004-multiview-products-and-scene-guide.md)。

## 实际空间约束（2026-10-20）

新任务自动从当前毫米设计测量房间比例/净面积、主模块行列、真实离墙和组间净距、方向/镜像与禁放区域，完整数值保存在layoutContext；生图提示词只要求保持图1中的比例、离墙关系、组间距离和通道，不发送具体毫米数值。原户型按来源修订和稳定空间ID核对，整张底图不送入生成；未知门窗不作为事实。L型占地与镜像进入无文字立体引导，页面可展开layoutContext核对。重启新版后端并新建任务生效，历史任务不回填。详见 [RENDER-005](tasks/RENDER-005-physical-layout-context.md)。

## 家具与商品绑定、生成后核对（2026-11-20）

新任务从后端已校验scene自动分配F家具编号和P商品编号：一件家具一个F，同款商品共用P。当前45°结构图、平面控制图和商品参考板均绘制对应编号，提示词使用同一F/P映射；成品不得保留编号。早期无编号实验仅适用于历史任务，历史数据不回填。

PNG下载成功后，系统使用现有Qwen-VL配置执行商品/建筑审核和位置几何审核。后者输入结果图、45°结构图和平面图，通过一对一实例匹配计算中心偏移和地面轮廓IoU。页面可能显示PASS、FAIL、UNCERTAIN或UNAVAILABLE；审核不可用不影响已生成图片，审核不通过也不会自动重新生图。SUCCEEDED仍只代表生图和保存成功。详见 [RENDER-011](tasks/RENDER-011-spatial-layout-control.md)。
## 生图前商品视觉理解智能体（2026-11-23，实验）

设置 `RSDP_RENDER_PRODUCT_AGENT_ENABLED=true` 后，已选商品会先经现有 Qwen-VL 视觉配置生成商品档案，再由程序保守选图并编译提示词；`RSDP_RENDER_PRODUCT_AGENT_MAX_NEW=4` 限制单次首次理解款数。档案按图片内容哈希、模型和协议版本缓存，页面可核对智能体/降级状态及实际选图。设置为 `false` 可回到静态 v6 提示词和候选图行为；任务版本仍标记 v7 以区分代码链路。

商品理解使用 `DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL` 和 `RSDP_VISION_MODEL`，不是再调用一次 `RSDP_RENDER_MODEL`。真实 A/B 时两边的生图模型和参数必须相同。新任务会从可靠贴墙的人工门窗标注生成结构洞口并记录未解析标注；高度仍是示意，不能当成实测。详见 [RENDER-010](tasks/RENDER-010-product-visual-agent.md)。

新版效果图任务使用无F编号的45°结构图、无编号平面图和带P编号的商品参考板，带F结构图仅供审核和人工追溯。商品理解 v2 会先报告单品/套组/多商品并识别目标主体；套组只有在目标区域可靠、可裁剪时才进入生图，否则报错要求核对或补充单品图。目标框裁剪不是像素级分割，页面会保留人工复核提示。旧任务和历史商品档案保持原样，需重启后端并新建任务使用新协议。

## 三图空间控制与几何审核（2026-11-24，实验）

`spatial-control-v1` 保存世界毫米地面轮廓及图1/图2各自的 `0～1000` 地面中心和多边形。`RenderLayoutSemantics` 根据实际布局写出两排直线、F顺序、共线偏差、相邻中心间距、旋转和头尾居中关系，禁止模型改成交错、弧形或散乱布局。

几何审核阈值可通过 `RSDP_RENDER_GEOMETRY_AUDIT_CENTER_PASS_RATIO`、`CENTER_FAIL_RATIO`、`IOU_PASS`、`IOU_FAIL`、`MIN_CONFIDENCE` 和 `ROOM_IOU_PASS` 配置，完整变量名见 `.env.example`。v10 的观测提示词不提供预期数量或目标坐标，避免审核模型照抄答案；类别仅作为轻量匹配因素。默认值仅为实验起点，必须用真实样本校准。数值坐标和审核不能等价于 ControlNet、蒙版或三维几何硬约束。详见 [RENDER-011](tasks/RENDER-011-spatial-layout-control.md)。

## 提示词 A/B/C/D/E 实验与生成计时（2026-11-24）

Agent 前端使用 `renderExperiment=1` 查询参数显示实验面板。A 为当前完整提示词，B 为精简提示词，C 精简且生图不传平面图，D 在 B 上开启 `prompt_extend=direct`，E 在 A 上开启同一 Direct 改写。样本 seed 为 `1100+序号`；每次点击只提交一张。实验模式和实际输入图片哈希保存在任务快照，不同实验参数不能复用幂等请求。普通入口默认使用正式 E，实验面板可显式回测 A/B/C/D。任务响应增加 `generationTiming`，页面显示实时等待、最终总耗时及准备/提交、服务生成、图片下载阶段；Direct 改写包含在服务生成耗时内，当前无法单独拆分。详见 [RENDER-012](tasks/RENDER-012-prompt-ab-experiment.md)。
