# GEOM-015 窗与开口标注

日期：2026-10-27。状态：已实现并通过构建、纯函数测试、专项 UI mock 与后端测试；真实数据库持久化与用户业务验收待补充。

## 背景

目标画图工具条含「窗」按钮；当前障碍类型（`SpatialObstacleKind`）只有 COLUMN / SHAFT / NO_PLACEMENT / DOOR_CLEARANCE / OTHER，无窗/开口标注。LAYOUT-029 盘点已确认：识别层不产出门/开口，`openings` 契约层恒空。

## 目标与范围（定稿）

用户明确：窗和障碍物一样挂在**单空间**内（`zone.obstacles`），不做跨空间对象。

- 障碍体系新增 `WINDOW` 类型：单空间焦点内沿墙拖出矩形标注窗位，复用障碍的创建/移动/角点调整/删除/贴墙吸附与撤销链路。
- 语义定稿（首版）：窗为**标注型约束**——不扣净面积（`excludesFloorArea=false`）、不参与排版避让（不进 `zoneLayoutExclusions`、不算通道疏散阻挡）、不参与服务端排布校验（`OfficeLayoutValidationPolicy` 跳过 WINDOW 但仍校验几何有效性）。窗下能否放矮柜等业务规则待确认后再决定是否引入避让。
- 识别层自动提取开口不在本任务范围（仍是人工标注首版）；离地高度/窗台高字段未入契约，首版只标平面位置与宽度。
- LAYOUT-029 入口联动复核：窗不算通道阻挡、不作为入口代理，提示口径保持不变（仍以门洞净空为唯一入口代理）。

## 验收标准

1. 单空间焦点内可拖出窗矩形，画布标注「窗 · 宽×深」，可移动/调整/删除/撤销。
2. 窗不扣净面积、不改变空间轮廓、不进入排版排除区；已确认排版与窗重叠时服务端保存不被拒绝。
3. 纯函数测试、后端测试、`pnpm build`、`e2e:space-review`（含窗标注断言）、MOCK 冒烟、`git diff --check` 通过。

## 验证记录

2026-10-27 实际执行：

- `pnpm test:obstacle-window`（新增 `scripts/test-obstacle-window.mjs`，7 项断言）：标签「窗」、矩形 4 顶点、`excludesFloorArea=false`、`zoneLayoutExclusions` 过滤窗保留柱、贴墙吸附（容差内吸附右缘 x=3000、容差外不吸附）——全部通过。
- `pnpm build` 通过。
- `pnpm e2e:space-review` 通过（隔离预览服务 + mock API）：焦点模式拖出窗 → 提示「窗已保存（仅标注，不扣面积、不影响排版避让）」，障碍落库 kind=WINDOW 且不扣面积、轮廓不变，撤销移除。
- `mvn -f backend/pom.xml test` 全量 205 项通过（0 失败；新增 `acceptsConfirmedFurnitureOverlappingWindowAnnotation`：与柱同位置同尺寸的窗不拦截已确认排版保存）。
- `MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口预览）通过。
- `git diff --check` 通过。

实现要点：`SpatialObstacleKind` 增加 `WINDOW`（前后端契约同步，`SpatialObstacle.KIND_WINDOW`）；`obstacles.ts` 标签/矩形创建/贴墙吸附扩展，`zoneLayoutExclusions` 过滤窗；`review.ts` 类型白名单放行；`channelEgress.ts` 疏散阻挡排除窗；`SpatialCanvas.vue` 窗渲染蓝色 `#4a7fbf`；`SpaceReview.vue` 约束工具条新增「窗」按钮（WindowIcon，标柱/门洞/禁放区同步补图标），保存提示按类型区分；后端校验策略跳过 WINDOW 排除区。

未覆盖：窗台高/离地高度字段、窗对矮柜等具体品类的避让规则（待业务确认）、识别层自动提取开口。e2e 为模拟 API，未声称真实业务验收。

## 排障记录（e2e 编写）

- 焦点模式只渲染当前空间，世界→客户端映射固定从被聚焦空间的路径重建，不能取「第一个有轮廓的空间」。
- `zoneLayoutProblem` 有障碍类型白名单，新增类型需同步放行，否则保存报「建筑约束类型无效」。

## 依赖

GEOM-011 障碍体系；LAYOUT-029 入口联动目前以门洞净空为唯一入口代理，窗接入后已复核其提示口径（不变）。
