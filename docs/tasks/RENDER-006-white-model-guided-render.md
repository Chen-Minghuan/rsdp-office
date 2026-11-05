# RENDER-006 白模视角引导的 AI 效果图

状态：已实现并通过本地验证（方案 A；真实百炼生图目检对比待执行）
更新日期：2026-11-05

## 目标

用户反馈现有 AI 效果图生成效果不佳。现状（RENDER-002/003/004/005）：后端 `RenderPreviewService` 提交给模型的参考图是**俯视 2D 布局导视图**（`RenderPreviewScene.referencePng`）+ 商品多视图参考图 + 物理布局文字约束，模型需从平面图自行推断透视、遮挡与高度，几何漂移大。

本任务将 [RENDER-003 白模](RENDER-003-white-model-preview.md) 的**当前相机视角截图**作为生图主参考：透视、遮挡、家具比例天然正确，模型只负责材质与光影（"白模渲染"管线，与酷家乐等工具一致）。3D 白模的交互能力（拖动/旋转/替换写回设计几何）保持不变，AI 结果仍是静态概念图。

## 实现（2026-11-05，方案 A）

### 后端

- [RenderPreviewRequest](../../backend/src/main/java/com/rsdp/officeproduct/importer/office/dto/RenderPreviewRequest.java)：新增可选 `viewImage`（`data:image/png;base64,...`）。
- [RenderPreviewService](../../backend/src/main/java/com/rsdp/officeproduct/importer/office/service/RenderPreviewService.java)：
  - `viewImage()` 计费前校验：前缀/base64 合法性、≤10MB、64–8000px、宽高比 ≤10:1（对齐百炼输入图要求），无效截图报明确错误且不提交任务。
  - 幂等内容哈希纳入截图 sha256：同一 `clientChangeId` 带/不带截图或截图不同即拒绝（不串任务），相同截图重复提交复用原任务。
  - 方案 A 参考图：有截图时 `[白模截图, ...商品外观板]`（图1 为截图），**不再生成/提交立体导视图**；无截图时保持原 `[立体导视图, ...商品外观板]`。商品板沿用 RENDER-004 多视图合成（上限共 3 张，由 `QwenImageClient` 既有校验兜底）。
  - 快照 `renderProductReferences` 记录 `sourceType`（`whiteModelView`/`spaceGuide`），白模来源存 `viewUrl`（替代 `guideUrl`）；响应透出 `sourceType`/`viewImageUrl`。失效/历史标记、收费失败不落演示图等规则不变。
- [RenderPreviewScene.prompt](../../backend/src/main/java/com/rsdp/officeproduct/importer/office/service/RenderPreviewScene.java)：guided 变体——图1 为白模实时相机截图，严格保持视角/透视/墙体/门窗/家具数量位置朝向比例，仅替换材质与光影；视角句改为"与图1截图完全一致"。[RenderProductReferences](../../backend/src/main/java/com/rsdp/officeproduct/importer/office/service/RenderProductReferences.java) 的商品外观提示同步适配图1 语义。

### 前端

- [WhiteModelPreview.vue](../../frontend/app/components/OfficeCanvas/WhiteModelPreview.vue)：`captureViewImage()` 先隐藏选中提亮/占地标记/轮廓框等交互叠加物，同步渲染一帧后立即 `toDataURL`（无需 preserveDrawingBuffer），`finally` 恢复选中视觉；「AI 概念效果图」按钮改为截图后经 `ai-render` 事件携带发出；场景未就绪时按钮禁用/提示，不打开对话框。
- [canvas.vue](../../frontend/app/pages/canvas.vue)：`openRenderPreview(viewImage)` 统一入口——白模发起带截图，工具栏发起为空。
- [RenderPreviewDialog.vue](../../frontend/app/components/OfficeCanvas/RenderPreviewDialog.vue)：新增 `viewImage` prop；有截图时显示截图缩略图、隐藏视角选择（视角由截图锁定）；提交请求携带 `viewImage`；详情显示"结构参考：3D 白模视角截图"与"查看生成时的视角截图"，历史条目视角标签显示"白模视角"。附带修复：对话框 z-index 90→95，此前与全屏白模同为 90 且先挂载，从白模打开时会被白模遮盖（原 e2e 仅等待存在性未暴露遮挡）。
- [proposal.vue](../../frontend/app/pages/proposal.vue)：AI 概念图页签标注"白模视角引导"来源。
- 截图尺寸直接取当前视口渲染分辨率（devicePixelRatio ≤2），不做离屏重渲染；超出后端校验范围时报错不提交。

## 验证（2026-11-05）

- Java：`mvn -f backend/pom.xml test` 全部 240 项（237 通过 / 3 跳过）。`RenderPreviewServiceTest` 新增 3 项：白模截图为图1 且不生成导视图（含绑定商品时截图+商品板顺序）、截图内容哈希幂等区分（同 ID 不同截图/有无截图拒绝、同截图复用）、非法截图计费前拒绝（坏 base64/非 PNG 前缀/16×16/8000×500）。
- 前端：`pnpm build`、`pnpm test:white-model`、`pnpm test:furniture3d` 通过。
- UI（模拟 API）：`pnpm e2e:white-model` 新增白模发起 AI 流程——截图缩略图展示、视角选择隐藏、提交体含 `data:image/png;base64` 截图、详情标注白模来源；`pnpm e2e:render-preview` 新增工具栏发起不带截图断言；`pnpm e2e:proposal-3d`、`pnpm e2e:workflows`（预览服务 3001）、`MOCK_API=1 pnpm e2e:smoke` 通过。
- `git diff --check` 通过。e2e 均为模拟 API，**真实百炼生图（收费）与白模引导 vs 立体导视图的目检对比未执行**，待用户在真实环境触发后记录。
- 已知边界：待重试请求持久化到 sessionStorage 时含截图数据，超大截图可能超出配额（既有 try/catch 保底为仅内存暂存）；截图即点击时机的视角，之后转动相机不影响已提交任务（快照语义）；白模为示意材质，AI 仍可能偏离真实商品外观，商品参考板继续承担外观约束。

## 调研结论（2026-11-05，实施前）

来源：[千问图像生成与编辑 API 参考](https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference)、[千问图像编辑 API 参考](https://help.aliyun.com/zh/model-studio/qwen-image-edit-api)。

- **现有客户端无需换接口**：[QwenImageClient](../../backend/src/main/java/com/rsdp/officeproduct/importer/service/QwenImageClient.java) 已调用异步 I2I 端点 `POST {base}/services/aigc/image-generation/generation`（`X-DashScope-Async: enable`，messages content 内多图 + text，轮询 `GET {base}/tasks/{taskId}`），与官方图生图调用方式一致。
- **模型与参考图上限**：`qwen-image-3.0` / `qwen-image-3.0-pro`（现用，I2I 支持 1–3 张输入图）；`qwen-image-2.1-pro` / `2.1-turbo` 支持 1–10 张（若需更多商品参考图可作为备选，但需核验模型权限与画质）。
- **输入图要求**：JPG/PNG/BMP/TIFF/WEBP/GIF，宽高 1–8000 px（建议 384–2048），长宽比 ≤10:1，单张 ≤10MB，支持 base64 data URI（与现有提交方式一致）。输出图 URL 有效期 24 小时，落盘逻辑沿用现有 `RenderPreviewFiles`。
- **参数**：`n`（1–6）、`size`（`宽*高`，512×512 至 2048×2048）、`negative_prompt`（仅 3.0 系列）、`seed`、`prompt_extend`、`watermark`，现有客户端均已使用，无需扩展。
- 另有 `qwen-image-edit-plus` / `qwen-image-edit-max` 编辑系列（MultiModalConversation 接口），本任务暂不采用：现有端点已满足"截图 + 商品图 + 提示词"的多图引导需求，避免引入第二套接口与鉴权校验。

## 方案设计（2026-11-05 起草原案，实现以上一节为准）

### 前端

- `WhiteModelPreview.vue` 增加「当前视角生成效果图」入口（实际落地：沿用「AI 概念效果图」按钮携带截图发出，设计页全屏可发起；方案页只读 Viewer 为内联不显示入口）。
- 截图：`renderer.domElement.toDataURL('image/png')`，截图前同步渲染一帧并隐藏选中高亮/占地标记/轮廓框（实际未做离屏固定尺寸，直接取当前视口分辨率）。
- 参考图预算取舍：已按方案 A 实施「截图 + 最多 2 张商品外观板」，不提交立体导视图。

### 数据与状态约束（沿用项目规则）

- 效果图绑定已确认方案快照；空间/布局/选品变更后已有任务按现有失效或待核验规则处理，过期异步结果不得覆盖当前版本。
- AI 结果不作为验收证据；真实识别、估计、演示与人工确认分别记录。
- 费用与限流：真实生图为收费调用，本地验证以模拟 API 为主，真实调用单独记录。

## 风险与边界

- 截图若误含 UI 叠加物（选中框、面板）会污染参考图——截图前隐藏已实现，e2e 覆盖提交流程；叠加物隐藏本身依赖人工目检截图抽查。
- 未来若加载外部 GLB/纹理资源，WebGL 画布存在跨域污染导致 `toDataURL` 抛错的风险；当前程序化 CanvasTexture 无外部资源，不受影响（捕获失败已降级为提示不打开对话框）。
- 白模本身是示意材质（程序化木纹/织物），模型会在此基础上升材质，但家具造型仍可能与真实商品有偏差；商品多角度参考图（RENDER-004）继续承担外观约束。
- 服务端地域/baseUrl 一致性校验（`QwenImageClient.requireConfigured`/`poll`）继续生效；更换模型需同步该校验白名单。
