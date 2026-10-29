# GEOM-013 入口与禁放区统一为建筑约束

状态：已实现并通过专项验证
更新日期：2026-10-29

## 2026-10-29 四类建筑标注迁至布局选品

按用户最新要求，柱、门洞净空、窗和禁放区统一在布局选品的单空间画布中创建与编辑，空间总览移除这些编辑按钮及选中/拖动手柄，既有约束仍保留显示和排版避让。布局选品支持拖框创建、移动、角点/弧线缩放、宽深或半径数值调整、门洞换向、柱/窗贴墙吸附、Delete/按钮删除及刷新恢复；门洞在拖动过程中即按最终几何实时显示四分之一圆弧，不再以矩形预览代替。保存/删除仍使相关排版确认失效，但保留当前设计页和空间，不再强制跳回总览。

单空间设计同时按既有来源到毫米变换显示原始图纸，并直接复用空间总览的「图层与对照」面板：原始图纸、默认 75% 透明度、设计空间轮廓、实际尺寸标注和原始识别轮廓均可独立开关；底图不单独适屏、不拦截编辑，也不改变空间或家具尺寸。验证（API 全部模拟）：`pnpm build`、`pnpm e2e:space-obstacles`（四类标注全流程及总览入口移除）、`pnpm e2e:layout-planning`、`pnpm e2e:single-space` 和 `git diff --check` 通过。

## 背景与目标

系统中存在两套“入口/禁放区”：空间核对页的建筑约束（`zone.obstacles`，画布标注、持久化、排版避让、后端核验）和排版面板的预留区域（`layoutPreferences.exclusions`，本地草稿、仅预览显示、应用方案才落库）。用户确认收敛方案：以建筑约束为准，排版面板改为只读摘要 + 跳转“返回空间核对标注”。

- 旧 `layoutPreferences.exclusions` 在草稿加载/初始化时迁移为 `kind: NO_PLACEMENT` 的 obstacle（保留稳定 ID 与用户命名，新增 `SpatialObstacle.label` 可选字段），随后删除旧字段；非法矩形跳过并记为变更。
- 迁移注意坐标系：exclusions 用画布坐标（y 向下），毫米空间 obstacle 几何存储 y 取反。
- 排版面板「入口与禁放区」改为只读列表（名称 + 毫米尺寸），附“返回空间核对标注”按钮；跳转回空间核对页并自动选中、聚焦当前空间（`store.reviewFocusZoneId` 会话态，不持久化）。
- 设计页画布常显 obstacles 红/蓝虚线，无需勾选预览即生效，修复“看不到效果”。
- 移除 `zoneLayoutExclusions` 的 `includeLegacy` 兼容分支与预览层 exclusions 渲染；`LayoutPreferences.exclusions` 标记 deprecated 可选（旧服务端数据兼容）。
- 后端不改：确认校验与效果图上下文已同时读取 obstacles。

## 验证计划

| 检查 | 实际结果与范围 |
| --- | --- |
| `pnpm build` | 通过。 |
| `pnpm test:layout-planning` | 通过；新增迁移用例（y 轴换算、label 保留、幂等、空键清理），旧 exclusions 阻挡用例改为整间 NO_PLACEMENT obstacle。 |
| `pnpm e2e:space-obstacles` | 通过；API 模拟。新增排版面板只读列表（2 处约束）、新增入口按钮不存在、「返回空间核对标注」跳回并聚焦（出现「返回全图」）断言。 |
| `pnpm e2e:layout-planning` | 通过；API 模拟。种子整间禁放 obstacle 后应用按钮禁用、面板只读展示、画布常显 1 处约束，移除后恢复。调试中发现种子数据 y 轴符号写反被 `zoneLayoutProblem` 正确拦截，已改为按空间包围盒动态取反。 |
| `pnpm e2e:space-review`、`pnpm e2e:annotations` | 通过；SpaceReview 聚焦 watcher 与画布预览层改动的回归。 |
| `mvn -f backend/pom.xml test` | 通过；191 项（188 通过、3 跳过），后端零改动。 |
| 截图核对 | 已人工查看 `output/space-obstacles/02-layout-obstacles.png`：设计页常显柱（红框）与门洞净空（蓝色扇形）。 |
| `git diff --check` | 通过（仅既有 LF/CRLF 提示）。 |

未执行：真实后端集成与真实图纸业务验收；旧服务端已存数据中的 exclusions 由前端加载时迁移并随下次保存写回。
