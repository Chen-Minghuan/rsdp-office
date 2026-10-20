# LAYOUT-019 职员圆角 L 桌并入 L 型屏风卡位

日期：2026-10-20。状态：已实现，专项验证通过；真实服务保存/回读未新增验收。

## 用户要求

行业中 L 形屏风卡位即职员圆角 L 桌，两者重复。移除"职员圆角 L 桌"模块，保留"L 型屏风卡位"；把圆角 L 桌的两种排布（L 型单排、L 型对坐）移到 L 型屏风卡位。

## 实现

- `desk-l-rounded`（职员圆角 L 桌）标记 `catalogHidden: true`，与 `desk-u` 先例一致：目录（排版侧栏与手动布局共用 `furnitureCatalogTemplates`）不再展示，旧布局实例的图例、尺寸与恢复逻辑保留；自定义模块新建不能再选它做图例（沿用 `moduleInputProblem` 对隐藏图例的拒绝）。
- 排布 `l-desk-row`（L 型单排）与 `l-desk-face-to-face`（L 型对坐）的 `styleIds` 增加 `l-screen`；`STRICT_PRESETS['l-screen']` 变为 L 型单排、L 型对坐、F 型单排、T 型对坐、C 型对坐、十字型对坐共六种，默认排布因此为 L 型单排。`planStrictArrangement` 的样式兼容表同步开放。
- 职员 L 桌（`desk-l`）保留 L 型单排/对坐不变；旧 `l-screen` 布局（F/T/C/十字）的预设 ID 与几何不变，`OFFICE_ARRANGEMENT_RULE_VERSION` 维持 `2026-10-module-selection-v6`，旧布局不需要重新应用。
- `OFFICE_PRODUCT_STYLES` 的 L 型屏风卡位描述更新为六种排布；后端 `RenderSceneGuide` 的 L 型模板清单保留 `desk-l-rounded` 以兼容历史家具。

## 验证

- `pnpm build` 通过；test:arrangement-presets、test:auto-layout、test:adaptive-layout、test:reference-layouts、test:layout-planning、test:layout-references 通过。
- UI 模拟（隔离服务 + API mock，PLAYWRIGHT_EXECUTABLE_PATH 指向本机 Chrome）：e2e:furniture-modules、e2e:auto-layout、e2e:module-selection、e2e:layout-planning、e2e:reference-layouts、e2e:layout-references、e2e:adaptive-layout、e2e:meeting-layout、e2e:custom-modules、e2e:delete-modules、e2e:multi-furniture 通过；`MOCK_API=1 pnpm e2e:smoke`（预览服务跑在 3001）通过。
- e2e:furniture-modules 新增断言：目录无 `desk-l-rounded`、可见图例 29 款/9 类、选中 L 型屏风卡位后六种排布齐全；e2e:auto-layout 同步覆盖六种拓扑与规则版本。
- 顺带修复与本任务无关的既有过时断言：e2e:furniture-modules 的样式按钮入口（LAYOUT-017 已移除）、目录件数（28→按实际 29）、座席示意与会议椅角色（LAYOUT-014/018）、容量文案单位；e2e:auto-layout 的三步导航与净距标签；e2e:custom-modules 的"逐件添加"命名与手动家具保留断言；e2e:delete-modules 的三步导航焦点。
- e2e:single-space 仍在既有已知断言不匹配处失败（画布 `line` 计数，LAYOUT-009 补记已记录），与本次改动无关，未扩大处理。

## 边界

本轮未重启正式业务服务、未改动真实用户布局；下架模块的旧实例渲染与撤销恢复按既有快照逻辑保留。以 `desk-l-rounded` 为图例的旧自定义模块在刷新恢复时会被 `restoreCustomModules` 按隐藏图例规则跳过，与 `desk-u` 先例一致；已摆放实例的快照不受影响。真实服务保存/回读与业务验收仍按 LAYOUT-017 口径待执行。
