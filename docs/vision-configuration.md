# Qwen3-VL-Plus 图片与 PDF 识别配置

更新日期：2026-10-15。当前模型为 `qwen3-vl-plus`，通过后端调用阿里云百炼兼容 Chat Completions 接口。
接口/页处理和响应适配已实现；真实识别准确率仍需按 [样本规则](spatial/recognition-validation.md) 验证。

## 1. IDEA 中填写密钥

后端也会自动读取当前系统用户的 `~/.config/rsdp-office/vision.properties` 本机配置。
其中可填写 `DASHSCOPE_API_KEY=实际密钥`，该文件位于仓库之外，只保存在本机。
本机配置修改后重启后端即可生效；启动环境变量中的同名值优先。

打开后端的 Run/Debug Configuration（`OfficeProductImportApplication`），在 Environment variables 中添加：

```text
DASHSCOPE_API_KEY=填写你自己的百炼API密钥
```

模型默认就是 `qwen3-vl-plus`，北京兼容地址默认是 `https://dashscope.aliyuncs.com/compatible-mode/v1`。
修改后重新启动 Java 后端，前端不需要配置密钥。

可选变量：

| 变量 | 用途 |
| --- | --- |
| `DASHSCOPE_BASE_URL` | 地域/业务空间对应的兼容接口基地址，末尾到 `/compatible-mode/v1`，不要再加 `/chat/completions` |
| `RSDP_VISION_MODEL` | 模型，默认 `qwen3-vl-plus` |
| `RSDP_VISION_REFINE_ROOM_CONTOURS` | 默认 `true`；增加一次独立房名定位，再核查原图封闭房间墙线；设 `false` 同时关闭两步 |
| `RSDP_VISION_URL` | 已有独立识别服务地址；非空时优先调用此服务，直连百炼时保持为空 |

百炼 API Key 与调用地域应对应。官方当前推荐业务空间域名，例如北京
`https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`；旧北京域名仍可使用。
新加坡旧地址为 `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`，也可使用对应业务空间域名。
地址与模型支持范围以 [百炼视觉接口文档](https://help.aliyun.com/zh/model-studio/qwen-vl-compatible-with-openai) 为准。

## 2. 其他启动方式

本地 Java/Spring 启动进程继承相应环境变量。项目提供 [.env.example](../.env.example) 作为变量名模板；
IDEA 或本地 Maven 不会因为存在 `.env` 就自动加载，需通过实际启动配置传入。

Docker Compose 启动 Java 后端时，可将根 `.env.example` 复制为 `.env` 并填写，Compose 已向后端容器传入上述变量。
密钥保留在本地环境配置或已忽略的 `.env`，不填写到前端代码、共享的 IDEA 配置或提交的 YAML 中。

## 3. 页面使用

上传 PNG/JPG 或 PDF 后点击“识别图纸”。PDF 文件行可填写要识别的页码，默认第 1 页。
后端检查页码范围，渲染该页，发送标准 PNG 给模型；同一 PNG 保存为识别预览，避免底图与轮廓使用不同缩放。
不跨页拼接，也不会把 PDF 当作普通 image_url 直接提交。

标准底图最长边默认不超过 3072px，PDF 默认目标 144DPI（超长边时降低渲染比例）。
当前直连支持 PNG/JPEG 和 PDF；加密/损坏 PDF、不支持的图片、无效页码会明确报错。
本地输入文件上限 50MB，编码后图像仍检查百炼请求大小限制。

未配置密钥或调用失败时，页面显示实际错误；已移除默认房间的演示回退。
旧记录中的 `MOCK_RECOGNITION` 是历史演示结果，应重新识别，不自动转为真实证据。

## 4. 结果与精度边界

模型输出房间候选的名称、类型和多边形（归一化 0..1000），后端转换为标准底图像素坐标。
原始模型输出、文件/标准图哈希、供应商请求 ID、页码、质量提示保留在识别原始结果中。
非法/自交/重复候选有提示，未命名有效空间保留。

整图识别默认增加一次独立房名文字框检测，避免模型多边形中心落错房间；后端提取横纵墙线与封闭区域，回算到同一标准底图坐标。初次候选、房名定位与校正结果分别保留，歧义或未闭合时保留候选、降为低置信并提示；包含门洞估计补线，不自动确认单位。开放分区的纯模型边界仍未达到准确验收；新批次导入会按项目、同一标准底图哈希和唯一房名沿用既有人工轮廓，作为明确的人工设计覆盖层，不改写新识别原始房间。当前只针对轴对齐墙线，详见 [RECOG-005](tasks/RECOG-005-label-grounding-and-correction-reuse.md)。

原始识别仍保留 `units=px`、`coordinateSystem=SOURCE_PAGE`、`unitResolution.confirmed=false`，毫米宽深与面积在确认前为空。
同时生成独立的 `dimensionCalibration` 候选：后端优先定位外围长尺寸线，裁图读取整段标注；无法定位时提供四边放大图读取端点。比例由后端从尺寸文字/像素跨度计算，至少两条独立参考、严格多数且相对偏差在 3% 内，横纵矛盾时阻止自动应用。该阈值是本轮开发策略。

在 `/canvas` 右侧“图中尺寸自动校准”中对照参考线、检查单位后，点击“确认比例并应用到全部空间”；旧记录可先点“读取图中尺寸”。无单位数字显式按 mm 假设，可切换 cm/m 重验。候选读取不改变设计稿；只有确认应用才换算当前设计轮廓，可直接进入家具排版，识别疑点提示复核并允许按需修改。比例不足或冲突保留两点人工校准。
原始 rooms/来源单位不被修改，重读标注不覆盖已有空间编辑；应用校准使全空间与关联排版确认失效。
`NEEDS_REVIEW` 表示需要核对几何和校准，并非已通过空间确认。
墙/门窗/柱/孔洞完整提取和完整 DesignScene 确认快照仍属于对应 Spatial 任务，不能由尺寸候选代替其验收。

## 5. 接口

- `POST /api/v1/vision/recognize`：multipart `file`，可选整数 `pageNumber`（从 1 开始）。
- `POST /api/v1/floor-plan/analyze`：multipart `image`/`cad`，可选 `pageNumber`；现有项目上传页使用此入口并持久保存结果。
- `GET /api/v1/floor-plan/{analysisId}`：返回识别状态、错误、预览地址、房间和单位信息。
- `POST /api/v1/floor-plan/{analysisId}/dimension-calibration/recognize?analysisRevision=1`：从当前缓存标准图重读尺寸，不重识别房间。
- `GET /api/v1/floor-plan/{analysisId}/dimension-calibration?analysisRevision=1&assumedUnit=mm`：重验候选及单位，assumedUnit 允许 mm/cm/m。
- `POST /api/v1/floor-plan/{analysisId}/dimension-calibration/confirm`：JSON 为 `{ "analysisRevision": 1, "reportId": "当前报告ID", "assumedUnit": "mm" }`；服务端重验当前版本/图像/报告并返回确认凭据，不接受客户端任意比例。

`RSDP_VISION_URL` 模式保留旧文件转发协议，额外发送选定 `pageNumber`；外部服务是否实际选页/返回准确坐标由该服务契约核验。

## 6. 已执行与待执行验证

模型接入初期使用合成图片、两页 PDF 和模拟供应商响应验证请求、鉴权、选页与适配。
RECOG-003 已实测已有 `1.png`：标准图 2094 × 1269，22575 与 11470 两条参考形成约 12.098729 mm/px 候选，最大偏差约 0.10%，底部受遮挡参考被排除；图纸未确认应用。一次性 DB 副本验证了确认持久化、幂等及过期拒绝。具体哈希/证据与 48 项 Java、构建、UI 模拟检查见 [任务记录](tasks/RECOG-003-dimension-calibration.md)。
单张尺寸实测不等于房间识别或跨样本质量达标；需固定真实 PNG/PDF 样本与标注后按质量附件继续验收。

参考：[Qwen3-VL-Plus 能力](https://help.aliyun.com/zh/model-studio/qwen3-vl-plus)、
[百炼 PDF 支持列表](https://help.aliyun.com/zh/model-studio/pdf-understanding)、
[PDFBox](https://pdfbox.apache.org/download)。
