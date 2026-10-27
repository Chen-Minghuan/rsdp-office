# LAYOUT-038 椅背对背通行净宽与排距自动加宽说明

状态：已实现并通过专项验证
更新日期：2026-10-26
负责人：按实际填写
基线/交付版本：工作区
关联：[LAYOUT-007](LAYOUT-007-arrangement-presets-and-spacing.md)；[LAYOUT-035](LAYOUT-035-warning-levels-and-advice.md)；外部参考 [培训空间原型差距分析](../references/kimi-training-layout-tool-gap-analysis.md)（仅背景，1.46m/0.5m 为对方策略值）

## 目标与验收

触发：前后排/相邻组椅子背对背时。
结果：椅背对椅背场景要求最小通行净宽（椅背伸出之外再留通行量，数值为可调策略值）；排版引擎排距不足时自动加宽到满足净宽（放不下则减少排数并说明），校核提示中明确说明自动加宽原因与数值；手动拖小间距时给出同级提示。
范围：排距/组距计算与提示；硬校验拒绝语义不变。

- [x] 验收项 1：净宽口径与自动加宽规则定稿（含"加宽后放不下→减排+说明"）并有单测
- [x] 验收项 2：自动加宽的提示文案说明原因与数值；手动编辑触发同级提示
- [x] 验收项 3：专项 e2e + 排版回归；与 LAYOUT-035 分级体系对齐（黄级）

## 净宽口径与规则定稿（策略值，非建筑规范）

- **椅背相对净宽** = 2 × 拉椅活动预留（`seatClearanceMm`，默认 400）+ 通行量 `CHAIR_BACK_PASSAGE_MM` = 500mm；默认合计 **1300mm**。
- **适用判定**（几何推演）：`backToBack`（座席朝外）椅背跨**组间排距**相对 → 加宽 `aisleMm`；`faceToFace`（座席相对）椅背在**组内对坐间隔**相对 → 加宽 `faceGapMm`。课桌式 grid/row/auto 全同向（椅背对前排桌背，非椅背相对）、`centerAisle`（椅子朝向中央通道）不适用。
- **自动加宽**：`planOfficeFurniture` 配对模式（generic-back-to-back / generic-face-to-face 及旧数据排版模板路径）有效间隔 = max(用户值, 净宽)；放不下时 `fit()` 自然减少排数。
- **参考排布例外**：样式素材排布（straight-desk-row 等 adaptive 预设）间距按素材固定，**不自动加宽**，低于净宽只给黄级提示——参考拓扑保真优先（LAYOUT-015/016 语义）。
- **确认校验不变**：硬校验不含此策略项（椅子活动区重叠等既有硬校验不受影响）。

## 实现

- `officeAutoLayout.ts`：`CHAIR_BACK_PASSAGE_MM`/`chairBackMinGapMm`；`planOfficeFurniture` 新增可选参数 `seatClearanceMm`（默认 400），faceToFace 的 pairGap 与 backToBack 的组间排距按净宽自动加宽（`rowAisleMm` 只作用于网格两路径，centerAisle 语义不变）。
- 接线：`layoutPlanning`（general 网格传 `prefs.seatClearanceMm`）、`furnitureTemplateArrangement`/`createTemplateFurniture`/`layoutFurnitureTemplate`（传空间已保存拉椅预留）。
- `layoutWarnings.ts`：`chair-back-gap` 黄级条目，说明当前值/净宽数值/原因；generic 路径文案含"已自动加宽到 Xmm，排数可能因此减少"，参考排布路径文案注明"未自动加宽"；手动调小间距走同一检查。
- `DesignLayoutSidebar.vue`：`panelWarnings` 传入 spacing/presetId/seatClearanceMm。

## 验证结果

| 检查 | 实际结果与范围 |
| --- | --- |
| `pnpm build` | 通过。 |
| `pnpm test:layout-planning` | 通过；新增 038 用例：净宽公式、faceToFace 组内加宽=显式 1300 一致、显式更大不缩减、backToBack 组间加宽一致、扁空间加宽减排（4→2 件）、提示触发/达标不提示/faceToFace 与非配对不提示、参考排布"未自动加宽"文案。 |
| `MOCK_API=1 node scripts/e2e-layout-planning.mjs` | 通过；新增：一字对坐下「图例组间净距」调至 800 → 黄级提示（含"未自动加宽"）→ 调回 1600 消失，后续应用/刷新断言不受影响。 |
| 回归 `e2e-aisle-channels` / `e2e-auto-layout` / `e2e-meeting-layout` / `e2e-adaptive-layout` / `e2e-proposal-layout`、`test-layout-channels` | 全部通过（API 模拟）。 |
| `MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows` | 通过（自建预览服务 3019 端口）。 |
| `git diff --check` | 通过。 |

未执行：`mvn -f backend/pom.xml test`（后端零改动）、真实后端集成与业务验收。generic 配对排布的自动加宽目前只经模板排版 API（`layoutFurnitureTemplate`）与旧数据路径触达，面板自动排版对样式模块走 adaptive 引擎（参考排布，只提示）；面板无 generic 配对排布入口，如需开放另立卡。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-26 | 立卡（外部参考差距分析） | 参考原型 V4 椅背对背 ≥0.5m 通行+自动加宽 | 数值为我方策略值 |
| 2026-10-26 | 净宽 = 2×拉椅预留+500 通行（默认 1300mm） | 与既有 seatClearanceMm 语义一致复用；500 与参考原型同量级 | 调整只改 `CHAIR_BACK_PASSAGE_MM` 或拉椅预留 |
| 2026-10-26 | 适用范围限定配对模式（backToBack 组间、faceToFace 组内） | 几何推演：grid/auto 全同向不存在椅背相对；centerAisle 椅子朝通道 | 课桌式排距如需"椅后通行"规则另立卡 |
| 2026-10-26 | 参考排布（adaptive 预设）不加宽只提示 | 素材间距是参考拓扑硬约束（LAYOUT-015/016），加宽会破坏保真；generic 配对模式无此约束可自动加宽 | 提示文案按路径区分"已加宽/未加宽" |
| 2026-10-26 | 确认校验不纳入该净宽 | 策略值非规范，硬拒绝会破坏既有已确认方案 | 后端核验保持现状 |
