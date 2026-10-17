# 项目开发文档

本目录按 2026-10-13 用户明确的业务目标组织：商品入库与上下架；图纸识别；画布空间确认与编辑；家具排版；商品推荐；方案确认；效果图。

| 文档 | 回答的问题 |
| --- | --- |
| [项目规则](../AGENTS.md) | 修改项目时要遵守哪些约定 |
| [产品需求与验收](product-requirements.md) | 用户要完成什么，每一步怎样算完成 |
| [架构与空间数据契约](architecture.md) | 识别、画布、排版、商品和效果图如何对应 |
| [开发规范与流程](development.md) | 开始任务、实现、验证和交付如何执行 |
| [进度表](status.md) | 当前实现到哪里，差距是什么，下一项做什么 |
| [Qwen 图片/PDF 配置](vision-configuration.md) | 后端密钥、模型、地域地址、PDF 页码及识别边界 |
| [原图墙线约束](tasks/RECOG-004-local-wall-contours.md) | 原图墙线与封闭区域校正、来源坐标映射和异常保留 |
| [房名定位与同图轮廓继承](tasks/RECOG-005-label-grounding-and-correction-reuse.md) | 棋牌室位置校正、开放分区人工边界继承及真实/模拟验证范围 |
| [玻璃立面办公图轮廓修正](tasks/RECOG-006-glazed-office-contours.md) | 5.png 当前稿九处修正、顶部玻璃/柱角/门洞校正与真实底图重放 |
| [Qwen 接入任务](tasks/RECOG-002-qwen-vl-integration.md) | 本轮模型接入与验证记录 |
| [尺寸标注与比例确认](tasks/RECOG-003-dimension-calibration.md) | 自动读取标注、一致性校验、用户确认和真实图纸实测 |
| [单张图纸上传](tasks/UPLOAD-001-single-drawing.md) | 单图选择/拖放、更换与旧识别预览清理 |
| [移除项目信息](tasks/WORKSPACE-001-remove-project-info.md) | 上传页面精简、资料接口/表移除、设计数据保留与启动迁移 |
| [任务模板](tasks/README.md) | 每项开发任务如何记录过程和证据 |
| [本轮文档任务](tasks/DOC-001-project-baseline.md) | 本次需求与规范整理的过程及验证 |
| [空间数据任务](tasks/GEOM-001-scene-canvas-contract.md) | 下一项核心工作如何拆分和验收 |
| [商品图片提取任务](tasks/CAT-002-workbook-image-ingest.md) | 系统导入时提取内嵌图片的实现与验证 |
| [供应商尺寸导入任务](tasks/CAT-003-supplier-size-import.md) | 供应商目录的毫米尺寸、价格和预览复核 |
| [商品图片展示任务](tasks/CAT-005-product-image-gallery.md) | 商品详情大图、缩略图与列表悬停放大查看 |
| [单位模块删除](tasks/LAYOUT-006-delete-modules.md) | 画布选中删除、快捷键/撤销、确认失效与目录独立删除 |
| [家具多选与整组操作](tasks/LAYOUT-008-multi-furniture-selection.md) | Shift/Ctrl 点选、框选、组移/批删及边界与撤销校验 |
| [自定义模块与逐件添加](tasks/LAYOUT-005-custom-and-manual-modules.md) | 本机自定义目录、尺寸/图例快照、逐件点击与旋转及不覆盖已有家具 |
| [自动排版入口与结果反馈](tasks/LAYOUT-004-apply-auto-layout.md) | 恢复旧单排后直接自动多排、当前/预估件数与实际应用状态 |
| [一键多排与空间利用](tasks/LAYOUT-003-auto-multi-row.md) | 自动横纵比较、多排、间距、轮廓约束与恢复验证 |
| [常用排布与间距初值](tasks/LAYOUT-007-arrangement-presets-and-spacing.md) | 面对面、错位排、中间通道和默认 0 mm 列间距 |
| [单位模块共用家具图例](tasks/LAYOUT-002-shared-furniture-legend.md) | 手动布局同源目录、毫米规格、参数化线稿与排版恢复验证 |
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

产品目标以用户最新明确要求为准，代码描述当前实现，进度表记录实现与验收的差距。
根目录原型设计和模块抽取文档保留为历史设计资料；与新目标不一致的内容不作为当前验收标准。
根目录上述三份 Spatial 文档是本轮目标设计，正文版本已升级，保留旧文件名以维持引用；它们不是已实现能力证明。
API 名称、字段与状态标为“目标”时，表示后续设计契约，不代表现有接口已支持。
