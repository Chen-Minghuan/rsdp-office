# RENDER-010 生图前商品视觉理解智能体（实验）

日期：2026-11-23
分支：`experiment/render-product-agent`
对照基线：`experiment/render-static-product-prompt`

## 目标与边界

在用户保持手动点击生成的前提下，在现有 F/P 绑定、无编号结构图、Qwen 生图和生成后审核之间加入可缓存、可校验、可降级的商品图片理解。视觉模型只描述商品可见证据，不决定空间轮廓、门窗、家具数量/位置/旋转、商品变体或 F/P 关系，不直接持有生图密钥，不自动重试收费生图。

本轮先把静态提交 `5bffbe3`（`selected-products-multiview-v6-clean-visual-prompt`）同步到实验 worktree，再实现 `selected-products-multiview-v7-product-agent`。静态对照分支没有被修改。当前 `main` 工作区未提交的模型调整没有混入；真实 A/B 必须显式统一 `RSDP_RENDER_MODEL`、图片尺寸、负向提示、空间、商品和审核逻辑。

## 实际链路

```text
已保存画布快照 / revision / expectedSnapshot
→ RenderLayoutContext + RenderFurnitureBindings + RenderArchitecturalOpenings
→ RenderProductReferences 下载并去重当前变体/公共图片（每款候选最多4张）
→ ProductVisualUnderstandingService
   → ProductVisualProfileCache 查找内容寻址档案
   → 缓存未命中且未超本次新款上限时调用 QwenVisionClient.understandProduct
   → 严格校验并归一化 product-visual-profile-v2（当前版本）
→ RenderReferencePlanner 每款优先1张完整整体图，结构证据需要时补1张
→ RenderProductReferences 合成带 P 编号商品参考板
→ RenderPromptCompiler 组合程序事实与校验后的商品外观描述
→ 现有 QwenImageClient 异步生图
→ RenderProductAuditService 布局/建筑/商品/画面审核
→ RenderPreviewDialog 展示智能体状态、实际选图、档案版本和警告
```

视觉理解复用 `RSDP_VISION_MODEL`（当前默认 `qwen3-vl-max`）和 `DASHSCOPE_API_KEY`，与 `RSDP_RENDER_MODEL` 的生图调用分开。沿用原链路的商品资料核验和生图配置检查顺序；商品图片或绑定不合法时不会提交生图。

## 商品档案与硬校验

初版 `ProductVisualUnderstandingService` 使用 schema `product-visual-profile-v1`、提示词 `reference-understanding-v1`；2026-11-24 已升级为 v2，详见文末跟进。档案包含商品/变体 ID、原图哈希、逐图视角和置信度、整体轮廓、部件及证据图片、装配约束、材质颜色、外观短描述、警告和不确定项。

程序拒绝或降级以下情况：

- 商品/RSPU/变体与当前记录不一致；
- 编造输入图片 ID、重复图片 ID或非法证据关联；
- 视角枚举未知、置信度越界、JSON 非对象；
- 返回 `furnitureCount/layout/coordinates/openings/bindings` 等布局覆盖字段；
- 视觉服务不可用或超过单次首次理解数量。

缺失视角按 `UNKNOWN`、零置信度和不适合主参考处理，不猜测。模型 `reasoning_content` 不读取、不保存、不进入生图提示词。外观文本由 `RenderPromptCompiler` 白名单摘取，并附加“不得覆盖程序空间事实”的固定约束；每类装配/保留约束最多编译 6 条，避免大档案无限扩张。完全静态降级时不追加这段智能体提示，保持 v6 生图提示词。生成后审核只接收外观提示和约束，不重复发送候选图片 URL、逐图视角等冗余档案。

## 缓存与历史追溯

`ProductVisualProfileCache` 使用本地内容寻址 JSON 文件，位于 `RSDP_RENDER_STORAGE_DIRECTORY/product-visual-profiles`。缓存键包括：

```text
productId / variantId
+ 排序后的原始图片 SHA-256
+ 视觉模型
+ agentPromptVersion
+ schemaVersion
```

图片、模型或协议变化会生成新键。缓存只保存商品档案，不保存旧空间描述。任务 `renderProductReferences.items` 保存实际使用的档案，`candidateImages/images` 分别保存候选和实际选图 ID/URL/哈希；`productUnderstanding` 保存总状态、模型、协议、缓存命中和逐商品降级状态。缓存是实验期本地实现，多实例共享缓存和正式数据库仓储仍需后续任务。

## 选图与降级

- 每款优先一张 `wholeProductVisible=true`、`suitableAsPrimary=true`、置信度至少 0.65 且无严重警告的整体图。
- 仅当整体轮廓/部件证据明确引用另一张可靠且不同的整体视角时补第二张；主参考始终排第一。
- 视角不确定时只用主图；智能体不可用、未启用或超过首次理解上限时沿用静态候选图。
- 主图被判定混款、错商品、多商品或不可读时，在收费生图前阻止任务并要求核对商品图片。
- 第一版没有商品正面与家具 0° 的统一约定，不声称精确可见面计算。

状态包括总览 `SUCCEEDED/PARTIAL/UNAVAILABLE/DISABLED/NOT_REQUIRED` 和逐商品 `GENERATED/CACHE_HIT/INVALID_RESPONSE/UNAVAILABLE/SKIPPED/DISABLED`。页面不得把静态降级显示为智能体成功。

## 门窗共享基础

`RenderArchitecturalOpenings` 将白模已有规则复用到后端：窗中心线投影到最近墙段；门洞从铰链和两条径向边中查找厚度范围内的贴墙径向边。无法贴墙的门洞只进入 `unresolvedOpeningIds`，不在任意墙面猜洞。

`RenderLayoutContext` 保存 `openingsKnown/openings/unresolvedOpeningIds` 和墙内起止位置；`RenderSceneGuide` 使用同一结果绘制远侧墙、近侧剖切墙和门窗洞口；提示词要求保留已解析门窗。门高 2100mm、窗台 900mm、窗高 1200mm、墙高 2800mm仍明确为 `ESTIMATED` 渲染示意，不回写实测数据。生成后审核增加 `architecture` 与 `imageQuality`，不会自动重新生图。

**A/B 前必须把这部分共享建筑修复独立同步到静态对照分支；不能把门窗改善算成商品智能体收益。**

## 配置

```properties
RSDP_RENDER_PRODUCT_AGENT_ENABLED=true
RSDP_RENDER_PRODUCT_AGENT_MAX_NEW=4
RSDP_VISION_MODEL=qwen3-vl-max
RSDP_RENDER_MODEL=qwen-image-3.0
```

实验期仍在创建任务请求内同步理解，最多首次识别 4 款新商品；缓存命中不占新款额度。首次多种商品可能较慢，工作区事务锁也会等待。正式化若实测耗时明显，再增加 PREPARING/UNDERSTANDING 后台阶段，不在本轮引入新工作流平台。

## 验证与未验收范围

- 2026-11-23 后端全量 `mvn -f backend/pom.xml test`：319 项运行、0 失败、0 错误、3 跳过；专项覆盖结构化档案、缓存、降级、选图、主图拼图顺序、门窗、HTTP 请求和原有生图保护。
- `pnpm build` 通过；使用本机 Edge 执行 `pnpm e2e:render-preview` 通过，覆盖智能体状态、商品档案、实际选图、历史记录、失败重试和下载。构建仍有项目既存的重复自动导入、chunk 大小及 Node 22.19.0 版本提示，未将其混入本任务修改。
- 已生成并人工查看 `backend/target/render-openings-example.png`，简单矩形样本中门洞和窗台/窗高可见；该样本没有家具，不能据此声称复杂遮挡或真实生图门窗问题已解决。
- 未调用真实 Qwen 商品理解、审核或收费生图，未产生供应商任务；未重启业务后端或部署。
- 未证明商品结构、正反面融合、门窗保留和美观度已经改善，需按静态/智能体同一样本进行真实 A/B。
- 首轮建议仍为单桌两椅、带柜体/挡板工位、多同款加少量不同商品三组，每组静态2次、智能体2次；先评估数量/位置/绑定/门窗，再评估商品结构、画质和成本。

## 2026-11-24 F/P 显式生图与商品目标隔离跟进

本轮在原有 v1 商品理解基础上实施 `selected-products-multiview-v8-fp-target-agent`，不改变“用户手动点击生成、失败不自动重试收费生图”的边界。后续三图与坐标控制见 [RENDER-011](RENDER-011-spatial-layout-control.md)；下文“平面图只供人工核对”仅描述 v8 当时状态。

### 生图与审核共用 F/P

- `RenderPreviewService` 的生图首图、页面 `layoutGuideUrl` 和审核 `bindingGuideUrl` 统一为 `RenderSceneGuide` 生成的带 F 编号 45°结构图；原平面图仍只供人工核对。
- `RenderFurnitureBindings.prompt()` 逐件输出 `F→P` 或未绑定关系。`RenderProductReferences` 的商品板在每格顶部绘制 P 编号，商品说明同时列出 F/P，提示词要求先按编号对位、再进行空间写实，最终图不得保留编号。
- `RenderProductAuditService` 使用同一 F 结构图和带 P 商品板核对，不另建映射。

### 商品理解 v2 与目标裁剪

- `ProductVisualUnderstandingService` 升级为 `product-visual-profile-v2` / `reference-understanding-v2-target-audit`。缓存键随协议变化，旧缓存静默失效，历史任务快照不改写。
- 固定职责通过 Qwen `system` 消息发送，动态商品记录和 JSON 任务通过 `user` 消息发送。智能体先区分单品、套组、多商品、多视角，再识别 TARGET/RELATED_PRODUCT/ACCESSORY/BACKGROUND；不能只生成外观文案而不报告套组。
- 每张图记录 `imageComposition`、目标状态/类别/归一化 bbox/置信度、是否含非目标商品、是否需要主体隔离、原图能否直接生图。程序校验枚举、bbox、证据图片和多商品语义一致性；目标不明确、理解结果未通过校验或无法可靠隔离时阻止使用原图；只有视觉服务不可用、未启用或超过新款限额时才保留静态降级。
- `RenderSubjectCropper` 将可靠目标框转换为确定性的像素裁剪，保留 4% 边距并检查边界和最小面积。它是目标框裁剪，不是像素级分割；框内仍可能含配套商品，manifest 明确记录人工复核警告。
- `RenderReferencePlanner` 对 `MULTIPLE_PRODUCTS` 不再一律静态兜底：只有目标明确、置信度达标且 bbox 有效时才允许进入裁剪；否则主图阻断。原图 URL/SHA 与生成图 SHA、隔离方式、目标框和实际裁剪像素分别记录。
- 页面增加 F/P 映射清单，展示图片构成、目标说明、主体隔离和裁剪警告；旧 v1 快照仍按旧数据读取。

### 验证与边界

- `mvn -f backend/pom.xml test`：327 项运行，0 失败，3 跳过。
- `pnpm build`：通过；保留项目已有 Node 版本、重复导入和 chunk 体积警告。
- `git diff --check`：通过（仅既有 Windows 换行提示）。
- 效果图 UI mock 已更新 F/P 与 v2 档案断言，并使用本机 Edge 执行 `pnpm e2e:render-preview` 通过；脚本使用随机端口和拦截 API，未连接主项目或真实生图服务。
- 未调用真实 Qwen-VL 重新识别 P1，未调用收费 Qwen Image，未进行真实效果 A/B。六把侧椅是否全部绑定 P2 属于画布选品数据，代码不会自动推断或补绑。
