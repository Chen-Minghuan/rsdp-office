# RENDER-009 家具与商品参考绑定

状态：已编码并完成本地模拟验证，真实模型视觉验收待执行
更新日期：2026-11-20
基线/交付版本：`fix/render-furniture-product-binding` 工作区
关联：RENDER-003 商品图片、RENDER-004 多视角与空间引导、RENDER-005 实际空间约束、RENDER-006 白模入口

## 目标与范围

解决45°概念效果图中“灰色块无法对应家具类型、家具无法对应所选商品图片”的表达断链。F表示一件家具，P表示一种已选商品；同款商品的多件家具共用P编号。绑定完全由已校验空间快照生成，不需要人工填写，也不让AI反推设计数据。

本轮只保留原有45°斜俯视和Qwen全图生图，不包含消费者模式、室内相机、风格参考、固定seed、局部蒙版精修或真实商品3D模型。不承诺像素级商品还原。

## 实现链路

> 历史说明：本文记录当时的无编号生图实验；当前 v9 已改为带 F/P 的三图输入，见 [RENDER-011](RENDER-011-spatial-layout-control.md)。

1. `RenderFurnitureBindings` 按稳定家具ID顺序生成F编号，按productId生成共享P编号；从家具类型、旋转外包中心、房间边界和rotation生成逐件类型、方位与朝向说明。
2. `RenderSceneGuide` 保留原有多边形、L形桌、镜像、旋转、尺寸和遮挡绘制。跟进真实结果后，生图输入改用无F文字版本；带F标签版本仅保存为bindingGuideUrl供审核，标签坐标和对应关系仍保存在任务附件。标签重叠时选择冲突面积最小的位置，必要时增加引线。
3. `RenderProductReferences` 复用原商品目录核验、多视角读取、内容去重和参考板逻辑，将提示关系改为 `P1 → F1、F2`，并在manifest保存 `furnitureReferenceIds` 和原图片摘要。商品板不再绘制P文字，模型通过“图2第1个商品组”等顺序关系识别P引用，避免把编号和规格表当作画面风格。
4. `RenderPreviewScene` 与绑定提示明确逐件数量、类型、位置、商品映射；生图参考图不包含F/P文字，编号只存在于内部数据与审核材料；生图提示词改用家具类型、方位和商品组顺序表达关系，不得把编号留在成品。
5. 2D工具栏和3D白模AI按钮进入同一弹窗。新任务只提交 `view=ISOMETRIC`，后端生成统一结构图；不再上传当前Three.js相机截图。历史 `whiteModelView` 任务仍可查看。
6. PNG下载成功后，`RenderProductAuditService` 将生成图、结构图、商品板和F/P清单交给 `QwenVisionClient.auditRender`，只检查明显数量、布局和商品错误。结果写回现有 `snapshot_json.renderProductReferences.qualityAudit`，不新增数据库列。

## 审核状态与边界

- `PASS`：本次视觉核对未发现明显问题，不代表精确还原或正式方案验收。
- `FAIL`：发现明显数量、布局或绑定商品错误，页面明确提示人工核对。
- `UNCERTAIN`：遮挡、看不清或模型返回不完整，不猜测。
- `UNAVAILABLE`：审核请求或本地参考资源不可用，不影响已生成PNG。
- `SUCCEEDED` 仍只表示供应商生图和本地PNG保存成功；审核不自动重新生图，不额外触发生图费用，但会发起一次视觉理解请求。

## 数据与兼容性

新任务保存 `bindingVersion=furniture-product-v1`、`furnitureBindings`、`guideLabels`、实际无文字guideUrl、内部bindingGuideUrl、商品manifest和审核结果；无编号输入的商品参考版本为selected-products-multiview-v4-clean-references，静态结构提示词实验升级为selected-products-multiview-v5-static-structure-prompt。布局快照投影不变，旧任务不回填、不冒充新版绑定。新请求拒绝 `INTERIOR` 和非空 `viewImage`，避免新旧输入混用。

## 验证记录

- 专项Java：51项通过，覆盖稳定F/P分配、同款共享P、逐件提示、商品参考板映射、审核JSON归一化、供应商请求结构、任务快照及审核只写一次。
- 结构图：已生成并人工查看单家具和四家具PNG，F1–F4位于对应块体，45°几何和空间轮廓保持。
- 前端：`pnpm build` 通过；`pnpm e2e:render-preview`、`pnpm e2e:white-model` 与 `pnpm e2e:workflows` 使用本机Edge及全mock API通过，覆盖统一45°提交、无viewImage、审核状态、历史/失败/下载、白模入口及原有工作流。
- Java全量：`mvn -f backend/pom.xml test` 共283项运行，0失败、0错误、3项因数据库环境未配置跳过。
- 未调用真实Qwen生图或审核，未产生收费任务；真实家具数量、位置及商品外观提升仍需部署后用同一布局与商品做新旧对照。未执行生产部署或正式业务验收。

## 主要变更文件

后端新增 `RenderFurnitureBindings.java`、`RenderProductAuditService.java` 及专项测试；修改 `RenderSceneGuide`、`RenderProductReferences`、`RenderPreviewScene`、`RenderPreviewService`、`QwenVisionClient`、`RenderPreviewFiles` 和 `RenderPreviewMapper`。前端修改 `RenderPreviewDialog.vue`、`WhiteModelPreview.vue`、`canvas.vue`、`renderPreview.ts` 及两项E2E。详细架构与使用说明同步至 `docs/architecture.md`、`docs/render-configuration.md` 和 `docs/status.md`。
## 2026-11-20 无编号生图输入跟进

基准提交：`847dffb`。真实结果发现模型会把F1/F2、P1和尺寸线作为画面元素复制，说明负向提示不足以抵消带文字参考图的影响。本次不改F/P分配、布局快照、商品映射或审核状态，将发送给生图模型的结构图和商品板改为无文字版本，并从生图提示词移除F1/P1等标记词，改用家具方位和商品组顺序表达；审核继续使用内部F/P数据及带F标签结构图。专项Java 51项通过。真实Qwen是否完全不再自行添加文字仍需重新生成验证，不能把输入清理等同于输出保证。

## 2026-11-20 非智能体静态结构提示词实验

分支：`experiment/render-static-product-prompt`。本版不增加商品理解智能体、正反面预识别、可见面筛选或额外模型调用，继续使用同一45°无编号结构图、商品多视角参考板、Qwen生图参数和现有审核。`RenderProductReferences` 增加所有商品共用的固定规则：参考板排版不表示商品方向；只呈现当前可见面；不得融合多个视角；先保证家具连接、支撑和受力结构合理，再赋予商品外观。商品参考版本升级为 `selected-products-multiview-v5-static-structure-prompt`，用于区分实验任务。 效果图弹窗保留商品参考板、空间与间距、空间结构、白模视角截图和平面布局参考的折叠核对区域；这些图片是生成依据，不应作为多个视角拼进最终主效果图。该实验不承诺消除家具扭曲，需与智能体版使用相同布局、商品和生图模型进行真实结果对照。

验证：效果图专项Java 55项通过（含商品多视角、静态结构提示词、无编号输入、任务提交和审核）；`pnpm e2e:render-preview` 使用本机Edge与mock API通过；`git diff --check`通过。未调用真实Qwen，未产生收费生图或审核任务。

## 2026-11-20 精简视觉提示词跟进

静态实验升级为 `selected-products-multiview-v6-clean-visual-prompt`。生图固定使用“生成一张满幅、照片级写实的3D室内效果图，材质真实细腻，光影自然，透视准确，结构合理，构图饱满，细节精致。严格遵循输入参考，不输出文字、标注、图表或拼贴。”；空间类型、45°视角和装修风格仍由原字段控制。RenderLayoutContext、RenderFurnitureBindings、RenderProductReferences 不再向生图模型发送毫米尺寸、离墙/组间数值、旋转角度、商品名称、规格或实际宽深高，facts、manifest、内部F/P绑定与审核数据保持完整。Qwen negative_prompt 同步收敛为画质、家具结构、图纸和文字问题。参考图核对区域已恢复显示，后台生图输入不变。

验证：效果图专项Java 58项通过；`pnpm build` 与使用本机Edge/mock API的 `pnpm e2e:render-preview` 通过。未调用真实Qwen，尺寸线/规格表是否消除以及画质是否提升，仍需重启后端后用同一空间和商品新建任务验证。
