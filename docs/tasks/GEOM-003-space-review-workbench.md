# GEOM-003 工装平面图空间核对工作台

2026-10-15 规则更新：用户取消逐空间确认门槛。复核只提示、按需修改后直接排版，当前行为见 [FLOW-002](FLOW-002-advisory-space-review.md)。下文的逐空间确认描述/测试保留为此前实施记录。

状态：部分实现；界面与模拟接口回归通过，真实图纸业务验收未执行。
更新日期：2026-10-14
基线：2413a93 工作区（保留已有 Spatial/Qwen/手动布局等未提交开发）
依据：用户提供的空间核对截图，以及[产品需求](../product-requirements.md)、[数据契约](../architecture.md)。

## 本轮结果

- 上传后的实际入口 `/canvas` 总览改为空间核对工作台：左侧空间名称、毫米宽深/范围、几何净面积和核对状态；中央原图/轻透明多边形；右侧实际尺寸、两点比例校准、图层和透明度。
- `/spatial-canvas` 兼容地址转到同一入口，避免独立演示页与实际上传路径不一致；既有 DesignScene API/store 保留，尚未将该工作台迁移到这些写队列。
- 原图、当前轮廓、原始识别轮廓、标注使用同一相机。适屏读取真实容器尺寸和变换后的底图/几何范围；移除校准后底图漏乘来源矩阵的问题，核对页不再把房间同时画成固定区域外的彩色矩形。
- 图像来源坐标 Y 向下；CAD 服务的几何/预览范围 Y 向上，导入时明确转换为设计副本的 Y 向下坐标，渲染适配到 WORLD_MM。CAD 分支仍复用原服务，没有重复实现解析器。
- 仅当前空间显示顶点；矩形显示两条实际尺寸，异形按边长显示。矩形毫米编辑固定局部左下角；异形通过顶点拖动保留轮廓；净面积从多边形计算。底图与来源副本不随房间编辑变形。
- 修改空间/用途使本空间和发生重叠的邻室取消确认，关联家具取消排版确认、已提交需求失效。全局比例校准影响全部空间。
- 比例未知、轮廓缺失/退化/自交、空间重叠、未逐空间确认时禁止进入排版。地址直达、store 动作和后端画布设计模式保存均有检查；后端读取旧无效设计稿时降为核对模式。
- 数值编辑、拖动、校准、确认、示例替换可撤销；本地草稿保存设计多边形与来源比例。重复加载同一 analysisId 不重导入覆盖编辑；新的来源应用将底图与几何一起纳入撤销快照。
- 默认无项目时提供 12 空间工装演示，显式标注 DEMO。已有项目/空画布不自动插入演示区域；旧 px 草稿缺少可靠几何/比例时显示待补充，不猜测实际毫米值。

## 数据与边界

复用现有 Office Canvas 对象 JSON，新增 ZoneObject 可选字段：
`reviewUnit`、`sourceAnalysisId`、`sourceYAxis`、`sourcePolygon`、`mmPerSourceUnit`、`evidenceKind`。
`polygon` 是当前设计轮廓，`sourcePolygon` 是导入时统一方向后的来源副本；FloorPlan 原始 rooms/previewBounds 保留原协议方向。
`x/y/w/h` 用于兼容旧排版与定位，不能替代异形面积。没有明确 evidenceKind 的旧识别响应按 ESTIMATED 展示。

后端 OfficeCanvasReviewPolicy 核验提交的空间状态、毫米标记、ID、轮廓合法性和空间重叠；不是已完成 DesignScene 不可变整体确认快照/幂等回执的证明。
当前工作台草稿仍使用 Office Canvas 保存与浏览器本地来源信息；数据库集成、跨设备来源恢复、409 写队列、完整确认版本快照仍属 FLOW-001/GEOM-001。
家具排版后续的异形避障/通道/商品需求解耦没有在本轮实现。
真实模型识别质量与图纸原始轮廓是否准确，须用真实样本另行验收；同一坐标变换不保证识别候选本身已正确。

## 验证记录

| 检查 | 实际结果 | 范围 |
| --- | --- | --- |
| `pnpm build` | 通过；已有重复自动导入警告仍存在 | Nuxt 生产构建；未运行独立 lint/typecheck |
| `pnpm e2e:space-review` | 通过，脚本自动启动/关闭独立预览服务 | 模拟 API：底图角点与轮廓一致、缩放不改几何、图层开关、毫米编辑/撤销、重叠拦截、逐空间确认/阶段门禁、图片校准、L 形拖动、刷新恢复、CAD Y 轴/偏移、390px 布局 |
| `pnpm e2e:vision` | 通过 | 既有图片/PDF 上传页；模拟 API，未调用百炼 |
| `MOCK_API=1 BASE_URL=http://127.0.0.1:3012 OUT_DIR=/tmp/rsdp-review-smoke pnpm e2e:smoke` | 通过 | 生产预览，隔离接口；空间复核 → 排版 → 选品/清单现有演示流程 |
| `BASE_URL=http://127.0.0.1:3012 OUT_DIR=/tmp/rsdp-review-workflows pnpm e2e:workflows` | 未通过 | 商品详情步骤等待旧 button/弹窗；当前商品页使用 NuxtLink 到详情页，测试尚未更新。尚未执行到该脚本的画布步骤，画布由专项/冒烟独立验证 |
| `mvn -f backend/pom.xml test` | 24 项通过，0 失败/错误/跳过 | 含新增 4 项阶段策略测试：共享边、未复核/未知比例、交叉/重叠、L 形凹口 |
| `git diff --check` / 文档链接检查 | 通过 | 工作区空白与本轮文档本地链接 |
| 真实 PDF/PNG/CAD、数据库集成、CAD 金样本 | 未执行 | 不能用演示或模拟接口截图替代业务验收 |

测试过程最初复用了已运行预览的旧资源清单，出现资源 404；重启专用生产预览后冒烟通过。未停止用户原有的 3000 开发服务。

截图证据（均为模拟或演示）：
[桌面核对](../../frontend/screenshots/space-review/01-review-desktop.png)、
[进入排版](../../frontend/screenshots/space-review/02-layout-gate-open.png)、
[图片校准与编辑](../../frontend/screenshots/space-review/03-image-calibration-edit.png)、
[移动布局](../../frontend/screenshots/space-review/04-review-mobile.png)、
[CAD 方向模拟](../../frontend/screenshots/space-review/05-cad-axis-mock.png)。

## 后续

### 2026-10-16 画布空间统一配色

按用户最新要求，未选中的设计空间统一使用浅黄色填充和黄色虚线，不再按复核状态切换黄/绿配色；选中空间使用浅蓝色填充、蓝色实线和蓝色调节控制点。复核状态仍在列表及属性面板提示，尺寸标注保留。图例同步展示普通空间与当前选中的样式。

`pnpm build`、`pnpm e2e:space-review`（模拟 API）及 `git diff --check` 通过；已查看桌面回归截图，未以模拟图纸替代真实识别验收。构建仍有既有重复自动导入警告。

先用真实 PNG/PDF 核对底图定位与原始自动轮廓质量，再验证数据库保存/刷新和整体确认快照；按既定任务继续完善新 DesignScene API 接入与下游排版几何规则。
