# RENDER-009 家具与商品参考绑定

状态：已编码并完成本地模拟验证，真实模型视觉验收待执行  
更新日期：2026-11-20  
基线/交付版本：`fix/render-furniture-product-binding` 工作区  
关联：RENDER-003 商品图片、RENDER-004 多视角与空间引导、RENDER-005 实际空间约束、RENDER-006 白模入口

## 目标与范围

解决45°概念效果图中“灰色块无法对应家具类型、家具无法对应所选商品图片”的表达断链。F表示一件家具，P表示一种已选商品；同款商品的多件家具共用P编号。绑定完全由已校验空间快照生成，不需要人工填写，也不让AI反推设计数据。

本轮只保留原有45°斜俯视和Qwen全图生图，不包含消费者模式、室内相机、风格参考、固定seed、局部蒙版精修或真实商品3D模型。不承诺像素级商品还原。

## 实现链路

1. `RenderFurnitureBindings` 按稳定家具ID顺序生成F编号，按productId生成共享P编号；从家具类型、旋转外包中心、房间边界和rotation生成逐件类型、方位与朝向说明。
2. `RenderSceneGuide` 保留原有多边形、L形桌、镜像、旋转、尺寸和遮挡绘制；全部块体完成后绘制F标签。标签重叠时选择冲突面积最小的位置，必要时增加引线；坐标和对应关系保存到任务附件。
3. `RenderProductReferences` 复用原商品目录核验、多视角读取、内容去重和参考板逻辑，将提示关系改为 `P1 → 图1中的F1、F2`，并在manifest保存 `furnitureReferenceIds` 和原图片摘要。
4. `RenderPreviewScene` 与绑定提示明确逐件数量、类型、位置、商品映射；F/P标签只作输入定位，不得留在成品。
5. 2D工具栏和3D白模AI按钮进入同一弹窗。新任务只提交 `view=ISOMETRIC`，后端生成统一结构图；不再上传当前Three.js相机截图。历史 `whiteModelView` 任务仍可查看。
6. PNG下载成功后，`RenderProductAuditService` 将生成图、结构图、商品板和F/P清单交给 `QwenVisionClient.auditRender`，只检查明显数量、布局和商品错误。结果写回现有 `snapshot_json.renderProductReferences.qualityAudit`，不新增数据库列。

## 审核状态与边界

- `PASS`：本次视觉核对未发现明显问题，不代表精确还原或正式方案验收。
- `FAIL`：发现明显数量、布局或绑定商品错误，页面明确提示人工核对。
- `UNCERTAIN`：遮挡、看不清或模型返回不完整，不猜测。
- `UNAVAILABLE`：审核请求或本地参考资源不可用，不影响已生成PNG。
- `SUCCEEDED` 仍只表示供应商生图和本地PNG保存成功；审核不自动重新生图，不额外触发生图费用，但会发起一次视觉理解请求。

## 数据与兼容性

新任务保存 `bindingVersion=furniture-product-v1`、`furnitureBindings`、`guideLabels`、商品manifest和审核结果。布局快照投影不变，旧任务不回填、不冒充新版绑定。新请求拒绝 `INTERIOR` 和非空 `viewImage`，避免新旧输入混用。

## 验证记录

- 专项Java：51项通过，覆盖稳定F/P分配、同款共享P、逐件提示、商品参考板映射、审核JSON归一化、供应商请求结构、任务快照及审核只写一次。
- 结构图：已生成并人工查看单家具和四家具PNG，F1–F4位于对应块体，45°几何和空间轮廓保持。
- 前端：`pnpm build` 通过；`pnpm e2e:render-preview`、`pnpm e2e:white-model` 与 `pnpm e2e:workflows` 使用本机Edge及全mock API通过，覆盖统一45°提交、无viewImage、审核状态、历史/失败/下载、白模入口及原有工作流。
- Java全量：`mvn -f backend/pom.xml test` 共283项运行，0失败、0错误、3项因数据库环境未配置跳过。
- 未调用真实Qwen生图或审核，未产生收费任务；真实家具数量、位置及商品外观提升仍需部署后用同一布局与商品做新旧对照。未执行生产部署或正式业务验收。

## 主要变更文件

后端新增 `RenderFurnitureBindings.java`、`RenderProductAuditService.java` 及专项测试；修改 `RenderSceneGuide`、`RenderProductReferences`、`RenderPreviewScene`、`RenderPreviewService`、`QwenVisionClient`、`RenderPreviewFiles` 和 `RenderPreviewMapper`。前端修改 `RenderPreviewDialog.vue`、`WhiteModelPreview.vue`、`canvas.vue`、`renderPreview.ts` 及两项E2E。详细架构与使用说明同步至 `docs/architecture.md`、`docs/render-configuration.md` 和 `docs/status.md`。