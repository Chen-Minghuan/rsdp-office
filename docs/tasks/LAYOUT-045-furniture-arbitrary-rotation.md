# LAYOUT-045 家具模块任意角度旋转

状态：已实现并通过模拟回归

日期：2026-11-27
分支：feat/furniture-arbitrary-rotation
关联：SpaceReview 空间任意角度旋转（被复用方）；LAYOUT-008（家具多选）

## 问题

单空间设计画布中，选中家具模块后只有「旋转 90°」（写死 +90°）；空间核对页（SpaceReview）已有成熟的任意角度旋转交互（角度输入 + 实时预览 + 应用/丢弃 + 可撤销）。用户要求单空间设计复用同一能力。

## 方案

照搬 SpaceReview 的「输入预览 + 应用落库」语义，复用其交互模式而非代码（两处对象模型不同：空间转多边形环，家具只改 rotation 字段）。关键前提已核实：渲染层 CSS `rotate(deg)` 与占地校验 `rotatedFurnitureFootprint`（layoutConstraints.ts，旋转矩形的轴对齐包围盒通式）本就支持任意角度，90° 只是特例；失效（layoutConfirmed=false、submitted=null）与撤销（objectUpdate）链路与角度无关。

旋转中心保持现状语义：每件家具绕自身中心旋转，多选不绕公共中心（避免位置错乱）。

## 修改

- `stores/office.ts`：`rotateFurnitureGroup(ids, zoneId, angleDeg = 90)` 新增角度参数，默认 90 保证全部既有调用方（空格键、3D 白模、产品排版编辑器）行为不变；非法/零角度返回「请输入非零旋转角度」（对齐 rotateZone 文案）。
- `CanvasBoard.vue` 选中操作条：新增「旋转 [角度输入]°」（参考家具选择时仍只显示 90°）。输入即渲染层预览（`rotatePreviewItem` 注入 `dragFurnitureItem` 与 `dimensionFurniture`，尺寸标注随预览更新），**自动落库**（2026-11-28 按用户要求从「应用按钮」改为）：Enter 应用并取消选中（操作条收起，直接看到成品状态），焦点离开输入框（点击画布/其他家具/任意操作）、切换选中、画布 pointerdown 静默提交；仅 Esc 放弃。校验失败不取消选中，错误提示保留。提交按输入时刻的选中集快照（`rotatePreviewIds`）落库，点别的家具时旋转落在原选中家具上。「旋转 90°」按钮与空格键保持即时生效。
- 事件顺序说明：初版「blur 丢弃预览」与「watch 切换选中提交」存在竞态（blur 同步先于 watch 异步执行，预览被先丢弃），故统一为 blur 即提交，只有 Esc 是放弃出口，撤销可回退。
- `e2e-multi-furniture.mjs`：新增任意角度断言组——输入 45° 只预览不落库、Enter 应用为 45° 且 layoutConfirmed=false、切换选中自动应用（累计 75°）、点击画布自动应用（累计回 0°）、0° 拒绝不生效。另修复 mock 项目 leader 缺 `permissions: ['EDIT']` 导致 `canEditFloor` 拒载本地草稿、全部家具用例超时的既有问题（新版工作区权限门禁与老 mock 结构不匹配）。

## 已知限制

- 非 90° 角度占地按轴对齐包围盒校验，比真实旋转矩形保守（多占四个角）。
- 座椅净空软提示 `seatSideRect` 只认 4 个朝向，任意角度下提示方位可能不准（仅提示，不拦截）。
- 效果图布局参考（后端 RenderPreviewScene）按轴对齐矩形绘制时，任意角度会画成包围盒；本次未改后端。

## 回归与实测

- `pnpm build`：通过。
- `pnpm e2e:multi-furniture`：通过（含新增任意角度自动应用断言组；同步修复 mock 项目缺 `permissions` 导致的门禁拒载问题）。
- `pnpm e2e:preview-drag`、`pnpm e2e:white-model`：通过。
- `MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows`：失败，但基线对照（stash 本改动后同一提交构建重跑）失败完全相同（smoke 上传页 h1 超时、workflows 商品行数 0≠20），判定为既有环境/脚本问题，与本改动无关，本轮未深入排查。
- 本机 Playwright 锁定版本浏览器缺失，沿用 LAYOUT-044 做法以本机 Edge（PLAYWRIGHT_EXECUTABLE_PATH）执行。
- 未修改后端与数据库，未部署。

## 后续候选（不在本期）

- 拖动旋转手柄（交互最优，工作量单列）。
- 3D 白模与产品排版编辑器的角度输入入口（当前保持 90° 步进）。
- 逐件放置预览的任意角度（幽灵占位当前按 w/h 互换渲染，依赖 % 180 语义）。
