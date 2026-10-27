# LAYOUT-035 校核预警分级与修改建议

状态：已实现并通过专项验证
更新日期：2026-10-26
负责人：按实际填写
基线/交付版本：工作区
关联：[LAYOUT-023](LAYOUT-023-channel-width-threshold-hints.md)；[LAYOUT-029](LAYOUT-029-channel-egress-linkage.md)；[LAYOUT-034](LAYOUT-034-candidate-scheme-comparison.md)（净面积与指标口径复用）；外部参考 [培训空间原型差距分析](../references/kimi-training-layout-tool-gap-analysis.md)（仅背景）

## 目标与验收

触发：排版参数编辑/预览/确认时。
结果：统一校核提示分级（红=硬冲突会被确认拒绝；黄=低于推荐策略值；info=未核验/现场核验说明），每条黄/红提示附修改方向；人均面积校核（㎡/座，策略值）纳入黄级；既有散落提示（通道宽度、入口联动、座位不足、现场核验、边缘补位）归入同一分级口径与展示位。
范围：提示分级体系与面板展示；硬校验拒绝语义不变（后端核验不降级）。

- [x] 验收项 1：分级口径定稿（见下表，阈值均为策略值并注明）
- [x] 验收项 2：人均面积校核实现并有单测；提示附修改建议
- [x] 验收项 3：专项 e2e + 回归；旧提示文案迁移记录完整（见迁移表）

## 分级口径定稿（策略值，非规范）

| 级别 | 条目 | 阈值/条件 | 修改建议 |
| --- | --- | --- | --- |
| 红 error | 方案无法排出（proposal.problem） | 引擎无可行候选 | 调整尺寸/间距/通道/目标，或逐件放置 |
| 红 error | 座位不足目标 | `confirmLayout` 对 seats<target 返回 false（office.ts，硬条件） | 换小桌型/缩间距/减通道占用，或调低目标 |
| 黄 warning | 通道宽度不足 | < `CHANNEL_WIDTH_HINT_MM`=900mm（023 沿用） | 加宽至 900mm 以上，或在效果图阶段复核 |
| 黄 warning | 通道未衔接入口 | 029 规则 1（端头 ±600mm 缓冲无门洞净空） | 拖动通道端头靠近入口或补标注 |
| 黄 warning | 入口衔接路径截断 | 029 规则 2（走廊净宽 < 600mm） | 移开路径上家具/禁放区或调整通道 |
| 黄 warning | 人均面积不足 | 净面积/座位 < `AREA_PER_SEAT_HINT_M2`=2.0 ㎡/座 | 降目标人数/换紧凑桌型/缩减预留 |
| 黄 warning | 边缘补位 | proposal.warnings 中补位提示（020 沿用） | 核对补位位置与现场通道 |
| info | 未标注门洞净空 | 029 unmarked（无门洞标注，连通未核验） | — |
| info | 现场核验提醒 | proposal.warnings 固定文案 | — |

展示位：排版面板预览统计区统一预警块（`data-testid="layout-warnings"`，每条 `data-warning-id` + `data-level`，黄/红附"建议："行）；按红>黄>info 排序。候选方案切换时预警随激活候选重算（座位不足/人均面积反映当前选中）。

## 旧提示迁移记录

| 原位置 | 原 testid | 迁移后 |
| --- | --- | --- |
| 通道面板内联「宽度低于 900mm」（023） | `channel-width-hint` | 统一块内黄级条目，保留原 testid（`layoutWarningTestId` 映射） |
| 通道面板内联入口联动提示（029） | `channel-egress-hint` | 统一块内 info/黄级条目，保留原 testid；`channelEgressHints` 拆出结构化 `channelEgressIssues`（kind=unmarked/unlinked/blocked），旧函数为兼容包装 |
| 座位摘要行内「尚缺 N」 | `layout-seat-summary` 内 span | 统一块红级条目 `data-warning-id="shortfall"`；摘要行不再重复显示 |
| 静态「待现场核验」一行 | 无 | 由 proposal.warnings 的 info 条目承载（此前 proposal.warnings 从未渲染，fill/「仅从二排起」提示首次可见） |

## 实现

- 新增 `utils/layoutWarnings.ts`：`layoutPanelWarnings`（纯函数）、`zoneNetAreaMm2`（净面积=轮廓−约束外包矩形，034 候选指标同源复用）、`layoutWarningTestId`（旧 testid 映射）、`AREA_PER_SEAT_HINT_M2`。
- `channelEgress.ts`：拆出 `channelEgressIssues` 结构化出口；`channelEgressHints` 保留为字符串包装。
- `layoutPlanning.ts`：净面积改用 `zoneNetAreaMm2`（无行为变化）。
- `DesignLayoutSidebar.vue`：移除通道面板两处内联提示与静态核验行；统计区渲染统一预警块；`panelWarnings` 基于激活候选。
- 布局修复：候选卡片块与预警块加 `max-h` + 内部滚动——侧栏中栏滚动区曾被撑到高度 0、内容溢出被统计区遮盖（smoke 点击拦截复现）。

## 验证结果

| 检查 | 实际结果与范围 |
| --- | --- |
| `pnpm build` | 通过。 |
| `pnpm test:layout-planning` | 通过；新增 LAYOUT-035 用例：净面积扣减、座位不足红级+建议、人均面积阈值触发/不触发、problem 红级、补位黄级、现场核验 info、宽度黄级与 testid 映射、egress info 与 testid 映射、红>黄>info 排序。 |
| `node scripts/test-layout-channels.mjs` | 通过（egress 结构化重构后包装兼容）。 |
| `MOCK_API=1 node scripts/e2e-layout-planning.mjs` | 通过；「尚缺」断言迁移为 `[data-warning-id="shortfall"]` 红级+建议行。 |
| `MOCK_API=1 node scripts/e2e-aisle-channels.mjs` | 通过（宽度/egress 提示移展示位后旧 testid 断言不受影响）。 |
| 回归 `e2e-auto-layout` / `e2e-meeting-layout` / `e2e-adaptive-layout` / `e2e-proposal-layout` | 全部通过（API 模拟）。 |
| `MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows` | 通过（自建预览服务 3018 端口）；smoke 曾暴露侧栏溢出问题，修复后通过。 |
| `git diff --check` | 通过。 |

未执行：`mvn -f backend/pom.xml test`（后端零改动）、真实后端集成与业务验收。人均面积阈值的密集小空间 UI 触发由单测覆盖（e2e 种子空间均为 96㎡ 以上，未另造小空间种子）。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-26 | 立卡（外部参考差距分析） | 参考原型红黄分级+修改建议是校核体验核心 | 分级表定稿后，023/029 提示迁入 |
| 2026-10-26 | 分级口径定稿：红=确认/应用硬拒绝（座位不足实测为 confirmLayout 硬条件），黄=策略阈值，绿不单列、未核验用 info | 参考原型红/黄/绿三级中"绿=通过"在面板无信息量；未核验（无门洞标注）不是"通过"，单列 info | 效果图/方案页若需同级口径可复用 `layoutPanelWarnings` |
| 2026-10-26 | 人均面积阈值定 2.0 ㎡/座（黄级） | 与参考原型推荐值同量级；明确标注为策略值 | 阈值调整只改 `AREA_PER_SEAT_HINT_M2` |
| 2026-10-26 | 预警随激活候选计算 | 切换候选后座位数/面积指标变化，预警应反映当前选择 | 与 034 的签名跟随机制共用 proposal 覆盖层 |
| 2026-10-26 | 修复侧栏溢出：候选+预警块加 max-h 内部滚动 | smoke 点击被统计区拦截：中栏滚动区被撑到高度 0，内容溢出被覆盖 | 后续往统计区加内容需注意总高度预算 |
