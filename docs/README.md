# RSDP 办公智能体前端应用

基于 Nuxt 4 + Vue 3 + Pinia + Tailwind CSS v4 的可迭代前端应用。图标统一使用 `@heroicons/vue`（通过 `app/components/AppIcon.vue` 按名调用）。

| 文档 | 回答的问题 |
| --- | --- |
| [项目规则](../AGENTS.md) | 修改项目时要遵守哪些约定 |
| [产品需求与验收](product-requirements.md) | 用户要完成什么，每一步怎样算完成 |
| [架构与空间数据契约](architecture.md) | 识别、画布、排版、商品和效果图如何对应 |
| [开发规范与流程](development.md) | 开始任务、实现、验证和交付如何执行 |
| [进度表](status.md) | 当前实现到哪里，差距是什么，下一项做什么 |
| [Qwen 图片/PDF 配置](vision-configuration.md) | 后端密钥、模型、地域地址、PDF 页码及识别边界 |
| [底图先行与后台并行识别](tasks/RECOG-007-progressive-recognition.md) | 预览先到、模型并行、真实进度和标准图缓存 |
| [原图墙线约束](tasks/RECOG-004-local-wall-contours.md) | 原图墙线与封闭区域校正、来源坐标映射和异常保留 |
| [房名定位与同图轮廓继承](tasks/RECOG-005-label-grounding-and-correction-reuse.md) | 棋牌室位置校正、开放分区人工边界继承及真实/模拟验证范围 |
| [玻璃立面办公图轮廓修正](tasks/RECOG-006-glazed-office-contours.md) | 5.png 当前稿九处修正、顶部玻璃/柱角/门洞校正与真实底图重放 |
| [细双线隔墙与同名房间纠偏](tasks/RECOG-008-thin-partitions-and-repeated-labels.md) | 7.png 错位原因、结构网络、重复房名匹配与两张冻结实图回归 |
| [旧稿覆盖与隔间碎片修复](tasks/RECOG-009-contour-history-and-partitions.md) | 会议室截短旧框保护、来源轮廓恢复、女卫/电梯厅补救与未完成范围 |
| [中央办公区漏失修复](tasks/RECOG-010-missing-open-office.md) | 越界模型轮廓被过滤、同名组连带失效与开放地面追踪补救 |
| [Qwen 接入任务](tasks/RECOG-002-qwen-vl-integration.md) | 本轮模型接入与验证记录 |
| [PNG 墙网拓扑识别](tasks/RECOG-011-png-wall-topology-pipeline.md) | Qwen 语义识别、墙网构建、门洞闭合与空间多边形化 |
| [移除未启用分割服务](tasks/RECOG-015-remove-unused-segformer.md) | 清理服务、Java 接入、Docker 和环境配置，保留栅格墙线核查 |
| [空间完整性回退修复](tasks/RECOG-014-space-completeness-recovery.md) | 8.png 漏识别、恢复初始候选与保守墙线核查 |
| [语义切换空间丢失修复](tasks/RECOG-013-recognition-pipeline-recovery.md) | 主请求文字定位补位、同名部分匹配、未命名墙网候选与真实输入回归 |
| [尺寸标注与比例确认](tasks/RECOG-003-dimension-calibration.md) | 自动读取标注、一致性校验、用户确认和真实图纸实测 |
| [单张图纸上传](tasks/UPLOAD-001-single-drawing.md) | 单图选择/拖放、更换与旧识别预览清理 |
| [当前图纸进入对应空间](tasks/UPLOAD-002-drawing-canvas-handoff.md) | 上传 ID/识别绑定、后台空间入口、旧底图/模板/家具隔离与空稿恢复 |
| [移除项目信息](tasks/WORKSPACE-001-remove-project-info.md) | 历史上传页精简、资料接口/表移除、设计数据保留与启动迁移 |
| [项目创建与多楼层](tasks/WORKSPACE-003-project-floors.md) | 项目/需求/楼层规划、分层上传与工作区隔离、楼层树和需求备注 |
| [任务模板](tasks/README.md) | 每项开发任务如何记录过程和证据 |
| [本轮文档任务](tasks/DOC-001-project-baseline.md) | 本次需求与规范整理的过程及验证 |
| [空间数据任务](tasks/GEOM-001-scene-canvas-contract.md) | 下一项核心工作如何拆分和验收 |
| [商品图片提取任务](tasks/CAT-002-workbook-image-ingest.md) | 系统导入时提取内嵌图片的实现与验证 |
| [供应商尺寸导入任务](tasks/CAT-003-supplier-size-import.md) | 供应商目录的毫米尺寸、价格和预览复核 |
| [商品图片展示任务](tasks/CAT-005-product-image-gallery.md) | 商品详情大图、缩略图与列表悬停放大查看 |
| [单位模块删除](tasks/LAYOUT-006-delete-modules.md) | 画布选中删除、快捷键/撤销、确认失效与目录独立删除 |
| [家具多选与整组操作](tasks/LAYOUT-008-multi-furniture-selection.md) | Shift/Ctrl 点选、框选、组移/批删及边界与撤销校验 |
| [排版页切换空间](tasks/LAYOUT-009-design-space-switcher.md) | 下拉/前后选择空间、排版状态、家具保留与单空间隔离 |
| [自定义模块与逐件添加](tasks/LAYOUT-005-custom-and-manual-modules.md) | 本机自定义目录、尺寸/图例快照、逐件点击与旋转及不覆盖已有家具 |
| [自动排版入口与结果反馈](tasks/LAYOUT-004-apply-auto-layout.md) | 恢复旧单排后直接自动多排、当前/预估件数与实际应用状态 |
| [一键多排与空间利用](tasks/LAYOUT-003-auto-multi-row.md) | 自动横纵比较、多排、间距、轮廓约束与恢复验证 |
| [常用排布与间距初值](tasks/LAYOUT-007-arrangement-presets-and-spacing.md) | 面对面、错位排、中间通道和默认 0 mm 列间距 |
| [单位模块共用家具图例](tasks/LAYOUT-002-shared-furniture-legend.md) | 手动布局同源目录、毫米规格、参数化线稿与排版恢复验证 |
| [产品样式与专用排布规则](tasks/LAYOUT-010-product-style-arrangement-rules.md) | 一字/L 型职员台、屏风卡位样式、F/T/C/十字模板与规则版本 |
| [按空间用途分流排版](tasks/LAYOUT-011-space-layout-profiles.md) | 会议室单桌居中与开放办公区多排工位的流程分流 |
| [排版画布尺寸标注](tasks/LAYOUT-012-layout-dimensions.md) | 单空间画布显示空间外接尺寸、模块规格、排布总长与排间净距 |
| [排版面板操作体验](tasks/LAYOUT-013-layout-panel-ux.md) | 模块、排布、参数三步导航与紧凑操作区 |
| [按标注修正开放分区](tasks/GEOM-008-annotated-open-zones.md) | 当前方案五处分区、比例保留、修正包及真实 API 保存记录 |
| [空间轮廓自由变换](tasks/GEOM-007-free-contour-transform.md) | 八点缩放、内部平移、等比拉角、整边推拉及视图缩放 |
| [沿墙轮廓修正](tasks/GEOM-006-wall-contour-correction.md) | 入口/退台完整边界、补转折点、沿墙重描与撤销恢复 |
| [删除不参与排版的空间](tasks/GEOM-010-delete-spaces.md) | 删除空间及关联内容、撤销与刷新后保持空稿 |
| [单空间视图隔离](tasks/GEOM-005-single-space-view.md) | 当前房间/家具隔离、底图留在总览、负坐标适屏与视图回归 |
| [空间模板与布局复用](tasks/GEOM-004-space-template.md) | 彩色整层模板、保存/读取、独立布局副本与来源版本 |
| [空间复核提示规则](tasks/FLOW-002-advisory-space-review.md) | 无需逐个确认，提示复核后直接排版；前后端一致的计算条件 |
| [工装空间核对工作台](tasks/GEOM-003-space-review-workbench.md) | 原图贴合、毫米编辑、图层对照与进入排版前的复核门禁；本轮实现和模拟验证 |
| [空间首期文档调整](tasks/DOC-002-spatial-canvas-first-phase.md) | PDF/PNG 识别、画布编辑与保存确认的首期范围和调整证据 |
| [Spatial 系统架构](../Spatial_Canvas_Engine_空间解析编辑与无限画布系统架构设计_V1.1.md) | PDF/PNG 如何成为可校准、可编辑的无限画布空间 |
| [Spatial 数据协议](../Spatial_Scene_数据协议规范_V1.0.md) | 来源、设计副本、底图变换、几何与确认版本如何保存 |
| [Spatial 实施方案](../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md) | 首期如何复用已有代码、扩展接口并验收 |
| [附件 A：几何规则](spatial/geometry-rules.md) | 房间边界、容差、局部轴与确定性操作 |
| [附件 B：接口与存储](spatial/api-storage-contract.md) | 全稿保存、删除、重试、队列和数据库键关系 |
| [附件 C：确认失效](spatial/confirmation-rules.md) | 每种操作改变哪些版本、逐空间与整体如何确认 |
| [附件 D：真实样本](spatial/recognition-validation.md) | 自动识别与人工修正如何分别测量和验收 |
| [API Schema](contracts/spatial-api.schema.json) / [完整报文](contracts/spatial-api.examples.json) | 首期设计稿接口的机器可读结构与正反用例 |
| [本轮契约附件任务](tasks/DOC-003-spatial-contract-attachments.md) | 六项优化的落地和文档/schema 验证证据 |
| [外部流程参考评估](development-workflow-assessment.md) | vibe-coding-cn 中哪些方法适用于本项目 |

## 启动

基础设施通过项目根目录的 `docker compose up -d --build --wait postgres cad-parser` 启动（PostgreSQL 和 CAD 解析服务）。前端和 Java 后端在本地启动；前端默认访问 `http://localhost:8082` 后端。

本地开发时，在项目根目录执行：

```bash
cd frontend
pnpm install
pnpm dev --port 3001
```

> 端口 `3000` 通常被本地 RSDP 服务占用，本应用固定使用 `3001`。访问 `http://localhost:3001/`。

## 页面与流程

常规工作区左侧分为「项目工作台」和「商品资源」。空间核对页使用独立三栏工作台与五阶段提示；其他项目页暂保留原步骤导航。离开项目查看商品后，点击「空间设计」会回到之前的项目步骤与空间。

| 步骤 | 页面 | 路由 | 说明 |
|---|---|---|---|
| 1 | 上传办公图纸 | `/` | CAD/PDF/PNG 上传与识别；图片/PDF 使用后端 Qwen 密钥，PDF 可选页，实际尺度待校准 |
| 2 | 无限画布·空间核对 | `/canvas` | 原图与轻透明轮廓、毫米编辑/顶点拖动、自动读尺寸并确认比例/两点校准、图层对照、复核提示、空间模板生成与复用后排版 |
| 3 | 无限画布·单空间设计 | `/canvas?mode=design&zoneId=xxx` | 选择单元、Agent 布局、供应链选品、效果图 |
| 4 | 整体方案与清单 | `/proposal` | 汇总所有已确认空间的家具配置、数量、报价与 CSV 导出 |

页面 2 与页面 3 共用 `pages/canvas.vue`，通过 `mode` 查询参数切换工作模式；旧 `/spatial-canvas` 地址转到 `/canvas`：

- **空间核对**：`SpaceReview` / `SpatialCanvas` 共用来源到世界、世界到屏幕变换；左侧名称/毫米尺寸/净面积/状态对应画布，右侧尺寸与比例校准分开维护。仅选中空间显示顶点和真实尺寸，底图细节保留。识别候选/重叠疑点标记需复核，不要求逐个确认，可直接排版；未知比例或不可计算轮廓仍需处理。前端入口/刷新与后端保存使用相同规则，进入排版不伪造人工确认。
- **单空间设计**：画布只显示当前房间真实轮廓、归属家具及局部标注/参考图；整层底图、其他房间和无归属内容留在总览。自动居中适屏，切换、缩放及调整视口不改变几何或家具归属；已校准尺寸显示毫米。

首次进入任意页面时，若没有项目数据，会加载明确标为演示的 12 空间工装示例（`星河科技总部办公空间`）。核对页可用「加载工装示例（可撤销）」查看界面；已有项目/空画布不会自动插入示例。演示不是实际识别或业务验收证据。

### 商品资源

| 页面 | 路由 | 说明 |
|---|---|---|
| 商品库 | `/products` | 紧凑统计、名称/编码搜索、状态筛选、客户端分页、商品详情 |
| 批量导入 | `/products/import` | 上传文件 → 预览编辑与确认 → 导入结果 |
| 手工录入 | `/products/manual` | 录入商品资料 |
| 商品详情/编辑 | `/products/:rspuId` | 查看与编辑商品资料 |
| 商品审核 | `/products/audit` | 审核商品；审核不等于上下架 |
| 导入记录 | `/products/import-records` | 当前浏览器最近 30 次导入，支持查看结果与下载失败明细 |

商品列表、模板下载、导入、预览校验、手工录入、编辑与审核使用 `/api/v1/products` 下的业务接口。批量导入支持预览编辑后确认写入；上传的原文件不会存储在浏览器导入记录中。独立商品上下架流程尚需完善与验收，不能用审核或删除代替。

导入记录按 API 服务地址隔离，保存于当前浏览器 `localStorage`，不是服务器完整历史，也不在多用户或多设备之间同步。浏览器禁止本地存储时，记录仅保留在当前会话。导入请求失败时，不能据此断言没有商品写入，应先检查商品库再重试。

商品页区分加载中、空数据、筛选无结果和请求失败。请求失败或正在加载时，统计显示 `—`，不会误显示为 0；接口错误放入可展开的技术详情。

## 核心功能

- 分组模块导航 + 仅项目页面显示的四步步骤条
- 空间核对：选择、平移/滚轮缩放、矩形毫米尺寸和异形顶点编辑、撤销、本地草稿恢复、原图/设计轮廓/原始识别/尺寸图层对照
- 空间核对 → 家具排版：逐个处理待复核空间后，点击「确认全部空间，进入排版」；直接 URL 和 store 动作也受同一门禁约束
- 设计模式 Agent 对话与按区域商品推荐（演示数据，未接真实服务）
- 整体方案清单：按空间分组、预算分布、CSV 导出
- 浏览器本地草稿持久化（`localStorage`）

## 验证

空间核对专项回归（先 `pnpm build`，自动启动/关闭独立生产预览，模拟接口）：

```bash
pnpm e2e:space-review
```

覆盖底图/轮廓同变换、缩放不改几何、毫米编辑/撤销、图层开关、两点校准、L 形顶点拖动、刷新恢复、CAD Y 轴与偏移、复核仅提示/直接排版和刷新恢复/比例与非法轮廓校验及移动布局。截图位于 `screenshots/space-review/`，均为演示或模拟数据。实际验证结果及剩余边界见 [GEOM-003 记录](../docs/tasks/GEOM-003-space-review-workbench.md)。

空间模板：校准实际比例后，在画布底部点击“生成空间模板”，查看彩色分区、名称与面积。可命名后保存到项目、选择历史模板、导出 JSON，或基于模板进入排版。改动草稿会提示模板需要更新；使用历史模板会创建独立布局副本，当前草稿可撤销。直接进入排版先捕获本地模板，只有明确保存成功才显示远端已保存。数据库迁移见 [GEOM-004](../docs/tasks/GEOM-004-space-template.md)。

```bash
pnpm e2e:templates
```

该专项自动启动独立生产预览，拦截全部接口；验证预览、异形/面积、保存失败/幂等、历史复用、撤销/刷新及过期响应。截图在 `screenshots/space-template/`。模板保留的孔洞/建筑层若超出现有兼容排版能力，会明确提示并保留导出，不静默丢弃。

尺寸读取与比例确认专项回归（先 `pnpm build`，自动启动/关闭独立生产预览，全部接口模拟）：

```bash
pnpm e2e:dimensions
```

右侧“图中尺寸自动校准”支持读取/重新读取、点击标注对照底图、切换无单位标注的 mm/cm/m 解释及确认应用。候选不会自动改变尺寸；应用后可直接进入家具排版，复核疑点按需修改。测试覆盖确认/撤销/重复应用、编辑保留/刷新、冲突及异步过期保护。截图位于 `screenshots/dimension-calibration/`；真实调用结果与剩余边界见 [RECOG-003](../docs/tasks/RECOG-003-dimension-calibration.md)。

图片/PDF 上传页专项测试（先执行 `pnpm build`，脚本自动启动并关闭独立测试预览服务，使用模拟接口）：

```bash
pnpm e2e:vision
```

覆盖识别预览、待校准显示、置信度、PDF 指定页码及识别失败反馈；不调用真实百炼。

构建：

```bash
pnpm build
```

端到端冒烟测试（需要本地 `pnpm dev --port 3001` 已启动）：

```bash
pnpm e2e:smoke
```

脚本会依次走通：上传页 → 空间核对（完成两个待复核示例空间）→ 单空间设计 → 加购 → 整体方案，并在 `screenshots/` 下保存每步截图。

双流程回归测试（使用接口拦截，不需要后端，不写入真实商品）：

```bash
pnpm e2e:workflows
```

覆盖模块切换、设计上下文恢复、商品搜索/筛选/分页/详情、模板下载、导入确认/部分失败/跳过/请求重试、本地历史、错误/空状态和窄屏布局。截图保存于 `screenshots/workflows/`。
当前工作流脚本仍在商品详情使用旧 button/弹窗选择器，而商品页已使用详情链接；本轮执行在该步骤失败，未通过完整回归。空间核对由专项与隔离冒烟独立验证。

如未安装 Playwright 浏览器，可设置 `PLAYWRIGHT_EXECUTABLE_PATH` 为本机 Chrome/Chromium 可执行文件路径。`BASE_URL` 和 `OUT_DIR` 可覆盖测试地址与截图目录。

原设计流程冒烟测试也可使用 `MOCK_API=1 pnpm e2e:smoke` 隔离真实后端，避免受到现有项目状态影响或修改真实画布与方案。

## 与正式项目的边界

本应用尚未包含：

- 已验收的 CAD/PDF/PNG 识别到画布完整流程；当前 CAD 已有真实服务调用，图片/PDF 已接入 Qwen，真实样本与校准仍需验收
- 保留真实多边形、统一世界坐标及数值尺寸编辑的完整空间模型
- 空间确认 → 排版确认 → 商品推荐 → 方案确认 → 效果图的完整阶段控制
- 真实 LLM / Agent 服务调用
- 真实商品库存、价格与供应链
- 用户鉴权、权限与多人协同

项目、画布、购物车和会话已接入本地 Java 后端与 PostgreSQL，浏览器本地持久化用于保留草稿。后续可继续接入 RSDP 的识别、Agent 和权限服务。

单空间视图专项：先 `pnpm build`，再 `pnpm e2e:single-space`。使用模拟 API 检查房间/家具隔离、L 形与负坐标适屏、缩放、拖动、标注归属、切换/刷新及返回总览数据保留；截图默认输出 `/tmp/rsdp-single-space/`，见 [GEOM-005](../docs/tasks/GEOM-005-single-space-view.md)。

单位模块使用与手动布局一致的 9 类、28 种可见办公家具图例（共享目录及 `ManualFurnitureGlyph` 线稿），支持常用规格和自定义毫米宽深（兼容排版器最小 10mm）。新模块实例可不绑定商品，保留图例 ID、空间及空间模板归属；画布缩放保持线稿可读，旧商品家具仍兼容。先 `pnpm build`，再 `pnpm e2e:furniture-modules` 运行全目录/线稿一致性、尺寸、排版与恢复专项，全部 API 模拟，截图默认保存到 `/tmp/rsdp-furniture-modules/`。参见 [LAYOUT-002](../docs/tasks/LAYOUT-002-shared-furniture-legend.md)。

一键排版默认“自动优化”：比较横纵方向，以房间宽深生成多排并整体居中，预览件数/排数；“单排”仍可显式选择。间距设置可调排间净距、离墙距离、同排间距，默认 900/300/100mm（产品初值，非规范承诺）。只有点击一键排版才替换当前空间家具，参数随新实例刷新/撤销恢复。使用家具完整外包占地核对轮廓，L 桌缺口不参与塞放其他家具；尚不覆盖门窗/出入口与完整通达性校验。算法验证：`pnpm test:auto-layout`（Node 22.13+ 内置 TypeScript 擦除）；UI：构建后 `pnpm e2e:auto-layout`（API 模拟）。见 [LAYOUT-003](../docs/tasks/LAYOUT-003-auto-multi-row.md)。

自动排版入口修正：恢复旧“单排”后，主按钮“一键自动排版”仍直接生成自动多排；需要单排/背靠背/矩阵/沿边时，使用对应的“应用…方案”按钮。当前已放置与当前方式预估分别显示，未应用时保留旧家具并提示；应用后反馈实际件数。几何/图例/尺寸不同也视为尚未应用，主操作固定可见、参数区域可滚动。构建后使用 `pnpm e2e:auto-layout` 复测旧单排恢复及实际应用，详见 [LAYOUT-004](../docs/tasks/LAYOUT-004-apply-auto-layout.md)。

自定义与逐件添加：点击“＋ 新增单位模块”，填写名称、选择办公家具图例及毫米宽深/可选高度，保存到“我的模块 · 本机”；可编辑/移除目录，已摆家具保留各自模块快照。自定义目录在当前浏览器保存，已放置实例随既有画布保存。选好模块后点击“添加到画布”，在空间中连续点击放置；R 或“旋转 90°”切换朝向，Esc/“结束逐件添加”退出。检查真实轮廓、离墙和家具间距，拒绝越界/重叠且保留旧家具；成功添加使当前空间排版待确认，可撤销。先构建后 `pnpm e2e:custom-modules` 验证，API 全部模拟，见 [LAYOUT-005](../docs/tasks/LAYOUT-005-custom-and-manual-modules.md)。

删除单位模块：在单空间画布选中家具，点击右上“删除选中模块”，或按 Delete/Backspace；可用工具栏“撤销”恢复。删除使当前空间排版待重新确认，其他空间及单位模块目录保留。自定义目录卡片有“删除”按钮，仅删除本机目录项，已摆家具保留；可点“撤销删除目录模块”恢复最近一次目录删除（当前页面会话）。输入框及打开的弹窗会拦截画布删除快捷键。构建后 `pnpm e2e:delete-modules` 使用模拟 API 回归，见 [LAYOUT-006](../docs/tasks/LAYOUT-006-delete-modules.md)。
