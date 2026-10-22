# Qwen-VL 图片与 PDF 识别配置

更新日期：2026-10-17。当前默认模型为 `qwen3-vl-max`，通过后端调用阿里云百炼兼容 Chat Completions 接口。
接口/页处理和响应适配已实现；真实识别准确率仍需按 [样本规则](spatial/recognition-validation.md) 验证。

## 1. 项目本地 `.env` 配置

复制项目根目录 [.env.example](../.env.example) 为 `.env`，在其中填写：

```text
DASHSCOPE_API_KEY=填写你自己的百炼API密钥
```

模型默认就是 `qwen3-vl-max`，北京兼容地址默认是 `https://dashscope.aliyuncs.com/compatible-mode/v1`。
根目录 `.env` 已被 Git 忽略。Java 后端从项目根目录或 `backend/` 作为工作目录启动时会自动读取；Docker Compose 也会读取根目录 `.env`。修改后重新启动 Java 后端，前端不需要配置密钥。启动环境变量中的同名值优先。

可选变量：

| 变量 | 用途 |
| --- | --- |
| `DASHSCOPE_BASE_URL` | 地域/业务空间对应的兼容接口基地址，末尾到 `/compatible-mode/v1`，不要再加 `/chat/completions` |
| `RSDP_VISION_MODEL` | 模型，默认 `qwen3-vl-max`；如账号或地域未开通，可改回 `qwen3-vl-plus` |
| `RSDP_VISION_REFINE_ROOM_CONTOURS` | 默认 `true`；并行独立房名定位及保守墙线核查；设 `false` 关闭独立请求/混合候选墙线核查，保留主请求初始轮廓；labels-only 仍尝试墙网补救 |
| `RSDP_VISION_URL` | 已有独立识别服务地址；非空时优先调用此服务，直连百炼时保持为空 |

百炼 API Key 与调用地域应对应。模型可用性按账号开通范围为准：2026-10-20 公网服务器密钥请求 `qwen3-vl-max` 返回 404 `model_not_found`（未开通），已改用 `qwen3-vl-plus`（探测 200）。2026-10-22 复查：本地 `.env` 密钥对 `qwen3-vl-max` 同样返回 404 `model_not_found`（账号开通范围已变化），本地 `RSDP_VISION_MODEL` 已同步改为 `qwen3-vl-plus`（同密钥同地址最小对话探测 200），当前本地与公网均使用 plus。官方当前推荐业务空间域名，例如北京
`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`；旧北京域名仍可使用。

墙线核查直接使用 Java 栅格结构线规则，不需要额外分割服务、模型文件或分块推理配置。

新加坡旧地址为 `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`，也可使用对应业务空间域名。
地址与模型支持范围以 [百炼视觉接口文档](https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai) 为准。

## 2. 其他启动方式

本地 Java/Spring 启动进程仍可继承相应环境变量；如果 IDEA 使用其他工作目录，需在运行配置中将工作目录设为项目根目录或 `backend/`。
Docker Compose 已向后端容器传入根目录 `.env` 中的变量。
密钥只填写在已忽略的 `.env` 或本地环境变量中，不填写到前端代码、共享的 IDEA 配置或提交的 YAML 中。

## 3. 页面使用

上传 PNG/JPG 或 PDF 后点击“识别图纸”。PDF 文件行可填写要识别的页码，默认第 1 页。
后端检查页码范围，渲染该页，发送标准 PNG 给模型；同一 PNG 保存为识别预览，避免底图与轮廓使用不同缩放。
不跨页拼接，也不会把 PDF 当作普通 image_url 直接提交。

上传页先创建后台任务并显示“处理图片”，标准底图保存后立即可预览，空间识别继续进行；房间/尺寸与房名框两个 AI 请求并行，完成后进入“校正轮廓”。显示实际阶段、各步骤耗时和总用时，不显示推测百分比。刷新或导航返回继续查询同一任务，查询暂时失败可点“继续查询识别进度”；模型失败保留已生成底图。完成后由用户应用到画布。

相同文件内容、页码、最长边、PDF DPI 和转换版本复用标准图缓存（进程内 30 分钟，8 项/64 MiB，LRU；并发同键只转换一次）。文件名不参与缓存键；模型识别与设计结果不缓存，重新识别仍会调用模型。重启清空缓存，未完成任务明确中断并保留底图，需要重新发起；当前后台队列仅支持单 Java 实例，外部识别服务与 CAD 沿用同步入口。

标准底图最长边默认不超过 3072px，PDF 默认目标 144DPI（超长边时降低渲染比例）。
当前直连支持 PNG/JPEG 和 PDF；加密/损坏 PDF、不支持的图片、无效页码会明确报错。
本地输入文件上限 50MB，编码后图像仍检查百炼请求大小限制。

未配置密钥或调用失败时，页面显示实际错误；已移除默认房间的演示回退。
旧记录中的 `MOCK_RECOGNITION` 是历史演示结果，应重新识别，不自动转为真实证据。

## 4. 结果与精度边界

主请求输出房名、用途、初始候选 `polygon`、房名文字紧框 `labelBbox`（均归一化 0..1000）及明确印刷的尺寸/面积文字。文字框只定位文字，不能代替空间边界。保留 labels-only 响应兼容；最终轮廓统一转为标准底图像素坐标。
原始模型输出、文件/标准图哈希、供应商请求 ID、页码、质量提示保留在识别原始结果中。
非法/自交/重复候选有提示，未命名有效空间保留。

整图识别默认并行独立房名定位。Qwen 初始候选接受保守墙线核查：在同一标准底图上使用 Java 栅格结构线规则，有限闭合门洞并追踪连通轮廓；核查失败保留初始候选、降为 low 并提示复核。8.png 实测表明 labels-only 无法保证空间完整性，因此不再作为主请求目标。开放功能边界仍是估计，不能宣称精确识别。新批次按同项目、同标准图哈希和唯一房名继承人工轮廓作为设计覆盖层，不改写原始识别。见 [RECOG-014](tasks/RECOG-014-space-completeness-recovery.md)。

RECOG-014 对主请求两种真实错误编码作严格标准化：连续闭合的 `[x1,y1,x2,y2]` 边链或单层压平顶点序列可转换，断链/非数/越界仍拒绝；原始主响应完整保留。排除明确的家具设备条目。具有主请求文字紧框的候选，若墙网与其范围明显不符且无可靠印刷面积支撑，则保留初始轮廓、提示复核，避免茶室吞并楼梯核心。两个真实响应回放均保留 22 命名候选，但候选数量不代表精度验收。

RECOG-013 修复语义接口切换后的丢失：主请求有效文字框可补独立定位缺失，保留 `independentLabelLocalization` 原始响应和逐文字 `source`；主请求补位生成的轮廓保持 low / `contourGrounded=false`。同名数量不一致时做一一部分匹配，未定位对象单独待补画；独立检测多出的房名在设计候选中保留。逐房名轮廓还与可靠的图上印刷面积相对比例交叉检查，明显偏小的隔间碎片尝试墙线合并，证据不足则保留待补画；面积文字不直接形成实际毫米比例。新语义流程另扫描保守封闭墙网区域，未归属部分作为 low 的 `unnamedRegions`，不猜房名，不重复覆盖已有轮廓；过小、狭长、越界泄漏及无效边界不应用。墙线来源记录为 `contourRefinement.wallMaskSource=STRUCTURAL_PIXEL_MASK`，首阶段为 `STRUCTURAL_WALL_MASK`，不再生成分割服务可用性字段或缺少模型提示。详见[修复记录](tasks/RECOG-013-recognition-pipeline-recovery.md)。

墙线 V5 支持由结构网络连接的细双线隔墙，并改进同名房间一一匹配；未经墙线证实的候选（包括未尝试校正的分区）统一降置信并逐空间提示。7.png 冻结实图恢复 9 个封闭区域，开放分区及其他未闭合区域仍未验收，不能认为任意新图均准确。无需增加配置或数据库表，详见 [RECOG-008](tasks/RECOG-008-thin-partitions-and-repeated-labels.md)。

原始识别仍保留 `units=px`、`coordinateSystem=SOURCE_PAGE`、`unitResolution.confirmed=false`，毫米宽深与面积在确认前为空。
同时生成独立的 `dimensionCalibration` 候选：后端优先定位外围长尺寸线，裁图读取整段标注；无法定位时提供四边放大图读取端点。比例由后端从尺寸文字/像素跨度计算，至少两条独立参考、严格多数且相对偏差在 3% 内，横纵矛盾时阻止自动应用。该阈值是本轮开发策略。

在 `/canvas` 右侧“图中尺寸自动校准”中对照参考线、检查单位后，点击“确认比例并应用到全部空间”；旧记录可先点“读取图中尺寸”。无单位数字显式按 mm 假设，可切换 cm/m 重验。候选读取不改变设计稿；只有确认应用才换算当前设计轮廓，可直接进入家具排版，识别疑点提示复核并允许按需修改。比例不足或冲突保留两点人工校准。
原始 rooms/来源单位不被修改，重读标注不覆盖已有空间编辑；应用校准使全空间与关联排版确认失效。
`NEEDS_REVIEW` 表示需要核对几何和校准，并非已通过空间确认。
墙/门窗/柱/孔洞完整提取和完整 DesignScene 确认快照仍属于对应 Spatial 任务，不能由尺寸候选代替其验收。

## 5. 接口

- `POST /api/v1/vision/recognize`：multipart `file`，可选整数 `pageNumber`（从 1 开始）。
- `POST /api/v1/floor-plan/analyze`：multipart `image`/`cad`，可选 `pageNumber` 和 `background`（默认 false）。上传页对图片/PDF 发送 `background=true`，直连 Qwen 时立即返回 analysisId/taskId，后台继续处理；CAD/外部服务保持同步。
- `GET /api/v1/floor-plan/{analysisId}`：返回状态、错误、预览、房间、单位及 processing。analyzing 时可先返回标准底图。
- `POST /api/v1/floor-plan/{analysisId}/dimension-calibration/recognize?analysisRevision=1`：从当前缓存标准图重读尺寸，不重识别房间。
- `GET /api/v1/floor-plan/{analysisId}/dimension-calibration?analysisRevision=1&assumedUnit=mm`：重验候选及单位，assumedUnit 允许 mm/cm/m。
- `POST /api/v1/floor-plan/{analysisId}/dimension-calibration/confirm`：JSON 为 `{ "analysisRevision": 1, "reportId": "当前报告ID", "assumedUnit": "mm" }`；服务端重验当前版本/图像/报告并返回确认凭据，不接受客户端任意比例。

后台进度 `processing`（旧同步结果可为空）：

```json
{
  "stage": "RECOGNIZING",
  "startedAt": "2026-10-17T03:00:00Z",
  "updatedAt": "2026-10-17T03:00:01Z",
  "elapsedMs": 1000,
  "cacheHit": false,
  "timingsMs": { "prepare": 125 }
}
```

阶段为 QUEUED → PREPARING → RECOGNIZING → REFINING → SAVING → DONE，异常为 FAILED。timingsMs 仅在实际完成后增加 prepare/spaces/labels/contours/save；关闭轮廓校正时无 labels。spaces 与 labels 并行，不能相加作为识别总耗时；总用时包含排队和保存。完成/失败日志含分析 ID 和计时快照，不记录图片或密钥。分析状态可能先变 awaiting_confirm、进度尚为 SAVING，客户端应继续查询至 DONE/FAILED。识别期间后端拒绝房间确认及尺寸校准写入。

`RSDP_VISION_URL` 模式保留旧文件转发协议，额外发送选定 `pageNumber`；外部服务是否实际选页/返回准确坐标由该服务契约核验。

## 6. 已执行与待执行验证

模型接入初期使用合成图片、两页 PDF 和模拟供应商响应验证请求、鉴权、选页与适配。
RECOG-003 已实测已有 `1.png`：标准图 2094 × 1269，22575 与 11470 两条参考形成约 12.098729 mm/px 候选，最大偏差约 0.10%，底部受遮挡参考被排除；图纸未确认应用。一次性 DB 副本验证了确认持久化、幂等及过期拒绝。具体哈希/证据与 48 项 Java、构建、UI 模拟检查见 [任务记录](tasks/RECOG-003-dimension-calibration.md)。
单张尺寸实测不等于房间识别或跨样本质量达标；需固定真实 PNG/PDF 样本与标注后按质量附件继续验收。

参考：[Qwen3-VL 能力](https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai)、
[百炼 PDF 支持列表](https://help.aliyun.com/zh/model-studio/pdf-understanding)、
[PDFBox](https://pdfbox.apache.org/download)。
