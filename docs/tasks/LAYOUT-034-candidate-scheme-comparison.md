# LAYOUT-034 多方案候选评分与对比切换

状态：已实现并通过专项验证
更新日期：2026-10-26
负责人：按实际填写
基线/交付版本：工作区
关联：[LAYOUT-014](LAYOUT-014-layout-planning-review.md)；[LAYOUT-016](LAYOUT-016-space-adaptive-reference-layout.md)；外部参考 [培训空间原型差距分析](../references/kimi-training-layout-tool-gap-analysis.md)（仅背景，其评分权重为对方策略值）

## 目标与验收

触发：请求自动排版时。
结果：排版引擎枚举有意义的候选组合（朝向/位置/尺寸变体），按明确定稿的评分口径排序，面板呈现 Top N 方案卡片（容量、利用率、人均余量可见），点击切换预览；当前选中与应用语义不变（预览不落库、应用走既有失效链路）。
范围：候选集生成与对比 UI；不改动单方案应用/确认语义。

- [x] 验收项 1：候选生成维度与评分公式定稿（目标达成为唯一排序因子，权重为策略值并注明；见下「评分口径定稿」）
- [x] 验收项 2：Top N 卡片切换预览，指标与候选一致；纯函数单测覆盖排序
- [x] 验收项 3：专项 e2e + 排版回归；旧单方案入口行为兼容

## 评分口径定稿（策略值，非规范）

`layoutCandidateScore(seats, targetSeats)`：

- 指定目标人数：每满足 1 座计 1000 分、封顶于目标；超出目标每座扣 1 分。即"先刚好满足目标，再尽量少超出"。
- 未指定目标：仅比容量（每座 1 分）。
- 并列保持候选生成顺序：课桌式/会议的首个候选即既有默认方案，保证默认行为逐位不漂移（既有应用布局、回归基线不受影响）。
- 面积利用率（家具占地/空间净面积）与人均余量（(净面积−占地)/座位）为展示指标，**不参与排序**——避免默认方案漂移；如需参与排序另立卡定稿。

候选生成维度：会议排版沿用既有枚举（桌长尺寸 × 横/纵朝向 × 位置）；课桌式网格经 `OfficeLayoutPlan.alternates` 新增暴露 auto 内部已评估的朝向/对齐变体（引擎本就算后择优，此前丢弃其余）；职员桌 adaptive 与居中单件路径只有单变体，候选恰 1 个、卡片不显示。Top N=3（`LAYOUT_CANDIDATE_TOP_N`）。家具集合签名去重（`layoutCandidateKey`，方形家具归一化旋转角，避免视觉相同的伪候选）。净面积为近似值（轮廓面积 − 建筑约束外包矩形面积）。

## 实现

- `types/layoutPlanning.ts`：`LayoutCandidate`/`LayoutCandidateMetrics`，`LayoutProposal.candidates?`（Top N，`candidates[0]` 即方案本身）。
- `officeAutoLayout.ts`：网格两路径（有/无通道）收集已评估变体为 `alternates`；auto 外模式与沿边/中央通道路径不变。
- `layoutPlanning.ts`：plans 展开 alternates；评分/去重/Top N/指标；导出 `layoutCandidateScore`、`layoutCandidateKey`、`LAYOUT_CANDIDATE_TOP_N`。
- `stores/office.ts`：`applyLayoutProposal(zoneId, request, sourceKey, candidateIndex = 0)` 重算后按索引取候选家具；索引缺省/越界回落默认方案；sourceKey 守卫不变。
- `DesignLayoutSidebar.vue`：`baseProposal` + `selectedCandidateKey`（按签名跟随，应用候选后选中保持、预览正常退场；候选消失自动回落默认）；卡片块 `data-testid="layout-candidates"/"layout-candidate-card"`，≥2 个候选才显示；切换/应用均为纯预览语义。

## 验证结果

| 检查 | 实际结果与范围 |
| --- | --- |
| `pnpm build` | 通过。 |
| `pnpm test:layout-planning`（node scripts/test-layout-planning.mjs） | 通过；新增 LAYOUT-034 用例：评分公式取值、候选 2~N、candidates[0]=默认、签名去重、降序、指标范围、网格双朝向候选、单变体路径恰 1 候选；既有用例全部不漂移。 |
| `node scripts/test-layout-channels.mjs` | 通过（通道与候选正交）。 |
| `MOCK_API=1 node scripts/e2e-layout-planning.mjs` | 通过；新增：卡片出现与指标文案、切换预览更新且不落库、切回默认还原、应用非默认候选携带索引（args[3]===1）、预览=应用几何、撤销恢复。 |
| 回归 `e2e-auto-layout` / `e2e-meeting-layout` / `e2e-adaptive-layout` / `e2e-aisle-channels` / `e2e-proposal-layout` | 全部通过（API 模拟）。 |
| `MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows` | 通过（自建预览服务 3016 端口）。 |
| `git diff --check` | 通过。 |

未执行：`mvn -f backend/pom.xml test`（后端零改动）、真实后端集成与业务验收。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-26 | 立卡（外部参考差距分析） | 参考原型 V1 Top3 方案卡片为用户高频使用点 | 与 LAYOUT-036 求解模式有交互（候选维度随模式变化） |
| 2026-10-26 | 评分仅目标达成单因子排序，舒适/利用指标只展示 | 多因子加权会改变既有默认方案（如会议同座位数时小桌 vs 大桌），破坏已应用布局与回归基线；先保行为稳定，加权口径另立卡 | LAYOUT-035 预警分级可复用本卡的指标计算 |
| 2026-10-26 | 课桌式候选复用 auto 内部变体（alternates），不新增模式枚举 | staggered/row 等模式枚举可能在含障碍房间反超默认方案，属行为变更，需单独评估 | 如需「排布变体」候选（错位/单排），另立卡 |
| 2026-10-26 | 选择按家具签名跟随而非索引复位 | 应用非默认候选后候选集重算，索引复位会让面板把默认方案当待应用预览、遮盖刚落库的家具（e2e 复现：板面无新家具、幽灵预览 7 件） | 签名机制同样用于候选去重 |
| 2026-10-26 | 修复 e2e-proposal-layout 缺 `/api/auth/me` 模拟 | `.env` 开启鉴权后无 mock 的脚本停在登录页，与 e2e-space-obstacles 同类既有缺口 | 其余 10 个无 mock 脚本（见会话清单）如遇同类失败按此模式修复 |
