# LAYOUT-045 L 型家具有效占地面

状态：已实现待验收（真实画布拖拽与确认保存未人工验收）

## 背景

L 型桌此前按 `w × h` 外接矩形参与重叠与净距判定，内角（主台面下方、侧翼左侧的空区）被当成已占用，椅子拖不进内角，自动排版也会把该区域排除。

## 方案

引入“家具由若干矩形块组成”的统一抽象，渲染、落座、碰撞与后端校验共用同一份几何参数：

- 新增 `frontend/app/utils/furnitureFootprint.ts`（单一职责）
  - `furnitureFootprintRects(item)`：普通家具 1 块；L 型桌 2 块（主台面 + 侧翼）；无 `wingWidthMm` 或自定义模块回退单块
  - `footprintsOverlap` / `rectOverlapsFootprints`：块集合重叠判定
  - 几何参数复用 `workstationSeatGeometry` 的 `wing` 与 `desktopDepth`，不新写公式
  - 镜像 `mirrorX` 与旋转按块绕中心变换后取外接矩形，保留 3 位小数消除直角误差
- 判定接入（只换重叠判定，包含/离墙仍用外接矩形，避免放宽越界）
  - `officeManualPlacement.ts`：手动落位与移动的家具重叠、禁放区
  - `layoutPlanning.ts`：自动排版候选、椅子活动区、通道压占
  - `mixedLayout.ts`：组合家具净距
- 后端 `OfficeLayoutValidationPolicy`：`footprint` 改为 `footprints`（L 型两块），包含、禁放区、通道、两两重叠、组合离墙与净距全部按块判定；模板 ID 与 `moduleSnapshot.kind` 两种来源都识别 L 型

## 验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端占地面单测 | `node scripts/test-furniture-footprint.mjs`（新增 `pnpm test:furniture-footprint`） | 内角不重叠、侧翼/主台面重叠、镜像、90° 旋转、回退单块全部通过 |
| 后端策略测试 | `mvn -f backend/pom.xml -Dtest=OfficeLayoutValidationPolicyTest test` | 25 运行 / 0 失败，含新增“内角放椅通过、压侧翼拒绝” |
| 后端全量 | `mvn -f backend/pom.xml test` | 284 运行 / 0 失败 / 3 跳过 |
| 前端构建 | `pnpm build` | 通过（20.5s） |
| 既有单测 | `test-furniture-snap`、`test-layout-planning`、`test-auto-layout` | 全部 PASS |

未执行：真实画布拖拽、旋转/镜像后保存确认的浏览器验收；`e2e:custom-modules` 需前端服务未运行。

## 已知边界

- 圆角 L 仍按两块矩形近似，内角圆弧误差在几十毫米内。
- 自适应排布（`officeAdaptiveLayout`）在“格子”层面仍按矩形占位，未改为块集合；该路径只影响自动生成的直排布局。
- 家具“是否在房间内/布置区内”继续用外接矩形，保证不会因挖空内角而放宽越界判断。
- `wingWidthMm` 仍有多处默认 550 的硬编码，本轮未收敛为常量。
