# LAYOUT-036 求解模式扩展（桌数优先/最大容量/按面积推荐人数）

状态：已实现待验收（P0 范围：三求解口径 + 可解释评分 + 候选差异化 + 失败建议）
更新日期：2026-11-02
负责人：按实际填写
基线/交付版本：工作区（未提交，遵守「禁止 commit/push」约束）
关联：[LAYOUT-014](LAYOUT-014-layout-planning-review.md)；[LAYOUT-034](LAYOUT-034-candidate-scheme-comparison.md)；[LAYOUT-039](LAYOUT-039-saved-layout-plans.md)（A/B/C 方案保存层，2026-11-02 已按用户要求移除）；外部参考 [培训空间原型差距分析](../references/kimi-training-layout-tool-gap-analysis.md)（仅背景，2㎡/人 为对方策略值）

## 目标与验收

触发：设置排版需求时。
结果：在现有「目标人数」之外支持：桌数优先（给定桌型与数量，校核能否放下并排布）、最大容量（求该空间在当前规则下可容纳上限）；目标人数可选项按面积给出推荐值（㎡/人 系数为可调策略值，可一键采用，手动改过不覆盖）。
范围：求解入口与引擎支持；确认/推荐链路语义不变。

- [ ] 验收项 1：三种求解口径定稿（默认仍是目标人数）并有纯函数单测 —— 已实现：三口径 + 纯函数单测（test-layout-planning/test-mixed-layout），默认目标人数旧行为不漂移；未验收
- [ ] 验收项 2：面积推荐人数联动规则（跟随/不覆盖手改）定稿并实现 —— 已实现：按净面积给出建议人数（AREA_PER_SEAT_HINT_M2 策略值，可一键采用，不覆盖已填值）；「跟随」仅作展示不自动写入；未验收
- [ ] 验收项 3：专项 e2e + 回归；与 LAYOUT-034 候选集的组合行为明确 —— e2e:layout-planning 已扩三口径断言，相关回归套件已跑通（见验证记录）；真实环境综合验收未做

## 现状与依赖

- 现状（2026-10-31 后）：`LayoutPreferences` 增加可选 `objective`/`fixedQuantity`/`scoreMode`（均缺省旧行为）；单模块排版与组合布局两引擎同口径支持三求解目标；候选附 `scoreDetail`（五子项加权总分 + 人读理由/扣分项）与 `topologyKey`，Top3 按拓扑差异挑选；失败返回 `problemAdvice`；设施提示（`facilityLayoutHints`）作为建议性警告接入两引擎。
- 依赖：最大容量求解沿用既有约束集（通道/离墙/禁放区/布置区），与确认校验同口径；容量结果附「非施工或法规核准容量」提示。
- Kimi 原型黑盒实验：本环境无浏览器外网工具，无法访问原型，记为未执行（不影响实现，原型仅作背景参考）。

## 实施步骤

- [x] 定稿求解口径与推荐系数（标注策略值）
- [x] 引擎 + 单测
- [x] 面板 + e2e + 文档

## 契约要点（2026-10-31 实现）

- `LayoutObjective = 'target-seats' | 'fixed-quantity' | 'max-capacity'`，缺省 `target-seats`；`layoutObjectiveGoal()` 归一为 `{objective, goal}`，原值直通（不取整不截断，非法输入交给既有校验拒绝，保持旧报错）；`max-capacity` 归一 goal=0（不设上限，沿用既有容量评分）。
- 固定件数：`fixed-quantity` 以 `fixedQuantity` 为目标件数/套数，报告已放置与缺口（shortfall + 警告文案）。
- 可解释评分：`layoutCandidateScoreDetail()` 输出 fulfillment/comfort/zoning/utilization/alignment（0-1）+ total（0-100）+ reasons/penalties；`BALANCED_SCORE_WEIGHTS = {40,25,20,10,5}` 为策略初始值（非规范、未经样本标定）；`scoreMode: 'balanced'` 按总分降序（旧 tiebreak 兜底），缺省排序与旧版逐分位一致（回归单测锁定 seats 不变）。
- 差异化：`layoutTopologyKey`（标签词干去数字 + 朝向 + 行列 + 补位结构），`selectDiverseCandidates` 贪婪取不同拓扑，不足按分数序补足，不编造候选；20mm 级平移共享签名不再占满 Top3。
- 三档间距候选 v3（2026-11-02 用户补充）：面板与 `store.applyLayoutProposal` 共用 `planTieredLayoutProposal`/`planTieredMixedLayout`。「标准」使用当前间距，`officeLayoutSpacingVariants` 沿用原预设之间的毫米差值派生紧凑和舒适，修改标准后三档一起重算：紧凑排间/面对净距 −100、同排 −300 mm（最小 0）；舒适排间/面对净距 +300、同排 +200、离墙 +600、背靠中缝 +300 mm；其他预留保持。标准固定首位默认；几何相同去重、失败档位提示，不编造差异或缩小家具。原初始间距与已保存参数保持；非法输入不借归零静默放过。候选卡仅显示档位名称、数量（组合含座位）及利用率（有组间净距的工位候选改显实际最小净距），去掉评分、人均余量、目标和长段原因；评分数据仍供引擎使用。候选经 Teleport 放在画布右下角，根 `@pointerdown.stop`。
- 候选始终是只读预览不落库：用户点选一个候选应用上画布后继续手动调整（原「保存走 LAYOUT-039 方案服务」一层已于 2026-11-02 按用户要求移除，未新增平行数据库）。
- 布置区/设施：`allowedPlacementAreas`（null/[]/多边形语义）与 `facilityLayoutHints` 均仅作约束与建议，不产生 bbox 替代轮廓、不阻塞求解。
- 失败建议：`problemAdvice`（`layoutFailureAdvice`）给通用调整方向。

## 验证记录（2026-10-31，均为本工作区实际执行）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 构建 | `pnpm build`（frontend/） | 通过 |
| 单模块排版纯函数 | `pnpm test:layout-planning` | 通过（含 LAYOUT-036 新增组：三口径、shortfall、非核准容量提示、旧行为不漂移、balanced 降序、拓扑差异、失败建议） |
| 组合布局纯函数 | `pnpm test:mixed-layout` | 通过（含固定套数/超容缺口/最大容量/缺省旧行为/可解释评分/失败建议） |
| 布置区纯函数 | `pnpm test:layout-regions` | 通过（14 组） |
| 设施纯函数 | `pnpm test:layout-facilities` | 通过（7 组） |
| 排版 UI 回归（模拟 API） | `pnpm e2e:layout-planning` | 通过（新增三口径切换、balanced 评分展示断言） |
| 布置区 UI 回归（模拟 API） | `pnpm e2e:layout-regions` | 通过；期间修复既有探测脆弱性（见下） |
| 方案保存 UI（模拟 API） | `pnpm e2e:layout-plans` | 通过 |
| 模块推荐 UI（模拟 API） | `pnpm e2e:module-recommendations` | 通过 |
| 工作流 UI（模拟 API） | `pnpm e2e:workflows`（预览服务 localhost:3001） | 通过 |
| UI 冒烟（模拟 API） | `MOCK_API=1 pnpm e2e:smoke` | 通过 |
| 后端 | `mvn -f backend/pom.xml test` | 见 docs/status.md（本任务未改后端代码） |
| 静态检查 | `git diff --check` | 通过 |

已知问题修复（2026-10-31）：`e2e-layout-regions.mjs` 的 `polygonFreePoint` 原按多边形包围盒 2% 边缘探测，在顶点编辑后形状倾斜 + 选中描边使包围盒外扩时探针落在填充区外（余量仅约 0.25px），LAYOUT-039 方案条占用画布高度使缩放略降后暴露；改为按真实顶点屏幕坐标沿边中点向质心内收探测，与应用行为（填充区可整体拖动）一致，属测试探测加固，非应用逻辑改动。

未执行/未验收：真实数据库 + 两个楼层、两个空间、三方案的端到端综合验收未做（LAYOUT-039 已有 Postgres 集成测试 OfficeLayoutPlanDatabaseTest 覆盖方案服务本身）；Kimi 原型黑盒实验因无外部浏览器工具未执行；真实识别链路不在本任务范围。

2026-11-02 三档候选 v2（右下角浮层 + 标准档=当前间距 + 去自定义）后重跑：`pnpm build`、`pnpm test:layout-planning`/`test:mixed-layout`（三档断言改写）、`pnpm e2e:layout-planning`（新增浮层归属与标准首位断言）/`e2e:layout-regions` 全部通过（模拟 API）；期间修复浮层 `@pointerdown.stop` 缺失致候选卡点击失效。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-26 | 立卡（外部参考差距分析） | 参考原型 V1 三模式、V3 改面积推荐人数 | 与 LAYOUT-034 同期设计接口更省返工 |
| 2026-10-31 | 三口径在两个引擎（planLayoutProposal/planMixedLayout）同语义实现；所有新字段可选，缺省路径逐分位不漂移 | 约束「目标人数默认不变」；旧单测原样通过验证 | 后续标定 balanced 权重需真实样本 |
| 2026-10-31 | 可解释评分拆到独立模块 `layoutScoring.ts`，layoutPlanning re-export | test-mixed-layout.mjs 显式别名加载器无法递归解析重依赖图 | 新增纯函数优先放 scoring 模块 |
| 2026-10-31 | 组合布局放置失败即 `break`（原为 `continue`） | 占用只增不减，同规格后续包必然失败；500 件最大容量求解会阻塞 UI 线程数秒 | 性能修复，语义等价 |
| 2026-10-31 | 候选不落库，保存走 LAYOUT-039 方案服务（该层 2026-11-02 按用户要求移除，候选保持只读预览） | 约束「不新增平行画布数据库」；用户明确轻流程「展示 3 个方案选一个再调整」 | 效果图如需方案快照契约，按 LAYOUT-039 任务卡留存记录重启评估 |
| 2026-10-31 | e2e:layout-regions 探测加固 | 见「已知问题修复」 | 探测方式与应用拖动语义对齐 |
| 2026-11-02 | 按用户要求补间距预设档（紧凑/标准/舒适），与原型一致作为全局间距参数一键写入，不作为方案区分维度 | 核对原型 bundle（SchemeDock.tsx/Fx 求解器）确认其方案 A/B/C 实为 Top3 候选（桌型×行列去重签名），间距是全局参数（0.4–2.5m 滑杆+三档预设） | `OFFICE_LAYOUT_SPACING_PRESETS` 定义于 officeAutoLayout.ts，单模块面板与组合面板共用；点击才写入、默认间距不变、应用后可逐项微调；e2e:layout-planning 补两面板断言 |
| 2026-11-02 | 用户定稿「三档间距出三个方案」：新增 `planTieredLayoutProposal`/`planTieredMixedLayout` 引擎层包装——同一模块/目标/约束下按三档预设各求解一次、每档取最佳，候选标注档位（spacingTier+标签前缀）；几何完全相同去重、放不下档位 warnings 说明；手动间距与三档都不同时追加「自定义」候选保住微调能力 | 用户明确想要「紧凑/标准/舒适各一个方案直接比」；引擎层实现保证预览与 store.applyLayoutProposal（重算按索引取用）几何一致；面板切换、应用、撤销全链路沿用候选索引语义 | 候选默认按既有口径排序（balanced 总分 / legacy 目标达成），推荐在首；发现并记录：自适应工位排布行距由 aisle/face 驱动且饱和，自定义间距与档位几何相同时按去重策略不重复出卡（不编造差异） |
| 2026-11-02 | 用户定稿 v2「方案选择放右下角、不用自定义、默认标准、根据标准变化而变化」：候选卡 Teleport 到画布右下角浮层；「标准」档改为用当前间距求解（改间距/点预设后标准档随之变化），自定义候选与 `officeLayoutSpacingMatches` 删除；候选固定 标准→紧凑→舒适，不再按分数重排，candidates[0]=默认标准 | 用户对「自定义」卡与分数排序不认可，要以标准为基线、手动调整直接体现在标准档；固定顺序让 candidates[0]=base 语义与面板默认选中天然一致，选择逻辑零改动 | 浮层根必须 `@pointerdown.stop`（e2e 暴露：pointerdown 冒泡到画布平移导致按钮点击失效）；`spacingTier` 类型收敛为 compact/standard/comfort |

## 2026-11-02 v3：标准联动与卡片精简

用户补充要求：默认标准，标准修改时紧凑/舒适随之改变；每个方案只保留基础信息。修复 v2 只有标准变化、另外两档仍使用固定值的问题，两个排版引擎共享间距派生函数。

本轮实际验证：`pnpm build`、`pnpm test:layout-planning`、`pnpm test:mixed-layout`、`OUT_DIR=/tmp/rsdp-layout-tier pnpm e2e:layout-planning`、`git diff --check` 通过。UI 专项拦截 API，覆盖两面板默认标准、卡片精简、预览切换不落盘、非默认方案预览与应用一致、撤销及刷新；已人工查看组合截图。纯函数覆盖三档参数联动、标准输入不被修改、紧凑最小 0、非法输入保留、旧中缝参数兼容，以及组合方案实际坐标联动。部分自适应排布会受完整单元/座席最小净距影响，参数重算后几何可以相同，继续按实际结果去重。

构建存在评分模块重复自动导入警告；构建通过。本轮未改后端、未运行 Java/CAD 或真实服务集成；未声称完成真实空间业务验收，未提交或推送。

## 2026-11-02 截图追修：点击候选时恢复预览

用户截图反馈三档都显示 12 工位/41%，点击后布局不变。按截图 7524 × 2805 mm、1200 × 600 mm 一字单排与标准组间参数 1000 mm 复算：三档容量和占地确实相同，但实际最小组间净距分别为标准 1302.5、紧凑 1252.5、舒适 1452.5 mm；桌子的实际坐标不同。卡片只显示数量/利用率无法体现此差异。

另复现交互缺陷：`showPreview=false` 时 `selectCandidate()` 仅改变候选签名，没有开启预览，卡片高亮变化但画布仍显示原家具。新增模拟 UI 回归在修复前于点选紧凑后的预览数量等待超时，证实缺陷。修复为显式点击候选（包括同一张卡）时恢复预览；显式选择模块也开启预览，与已有界面说明一致。初次未选功能模块、人工删除/清空仍不自动生成预览。工位卡复用画布 `officeLayoutClearances` 显示实际最小组间净距，避免用策略参数冒充实测净距；没有组间净距和其他模块保留利用率。

实际验证：`pnpm build`、`pnpm test:layout-planning`、`OUT_DIR=/tmp/rsdp-layout-tier pnpm e2e:layout-planning`、`git diff --check` 通过。纯函数新增截图尺寸的三档 12 件/41%/实际净距断言；模拟 UI 新增窄工作间，先保存标准家具、关闭预览，再点紧凑/舒适，验证画布坐标改变、三卡显示不同实测净距、未应用前家具不变、应用舒适与预览相同。截图 `/tmp/rsdp-layout-tier/rsdp-narrow-tier-preview.png` 已查看。构建仍有既有重复自动导入警告；未直接操作用户当前浏览器或写入其布局，真实用户页面待核对；未提交/推送。
