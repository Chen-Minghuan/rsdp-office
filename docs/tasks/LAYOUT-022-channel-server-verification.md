# LAYOUT-022 通道服务端核验

状态：已实现并通过验证（真实数据库集成未测）
更新日期：2026-10-22
负责人：按实际填写
基线/交付版本：工作区（LAYOUT-021 未提交改动之上）
关联：[LAYOUT-021](LAYOUT-021-canvas-aisle-channels.md) 通道契约；[架构 §5.1](../architecture.md)；项目规则"后端核验阶段条件，不能只依赖 UI 禁用按钮"

## 目标与验收

触发：前端调用排版确认/方案提交等阶段接口（画布 JSON 持久化或确认动作）。
结果：后端复算通道禁放带（宽度 + 2×clearanceMm，「仅从二排起」按家具 `layoutSettings.firstBlockDepthMm` 豁免首排），家具旋转占地压占禁放带即拒绝并返回明确原因；无通道空间行为不变。
范围：仅核验既有通道数据（`zone.layoutPreferences.channels` 随 office_canvas JSON 存储）；不新增表；通道通畅性/疏散连通性属 LAYOUT-029。

- [x] 验收项 1：构造压占通道的画布数据提交，接口拒绝且文案指明通道冲突（单元/接口测试）
- [x] 验收项 2：合法布局（含「仅从二排起」首排豁免、净距扩展带）通过；旧数据无 channels 字段按空处理
- [x] 验收项 3：`mvn -f backend/pom.xml test` 通过；前端既有 e2e 不受影响

## 现状与依赖

- 通道校验现状只在浏览器 `frontend/app/utils/layoutPlanning.ts` 的 `confirmedLayoutProblem`（旋转占地经 `officeFurnitureFootprint`）。
- 后端 Java（`backend/src/`）已有画布持久化与阶段接口，需找到确认/提交链路中合适的核验点；几何单位为毫米、世界坐标，语义须与前端 `channelRects` 逐条对齐（含 fromSecondRow 豁免、clearanceMm 扩展、包围盒裁剪）。
- 待明确：后端是否已有 furniture/zone 几何校验工具可复用；没有则在服务端新建与前端语义对应的纯函数模块并配单测。

## 实施步骤

- [x] 梳理后端确认/提交接口与画布 JSON 解析现状，确定核验挂点
- [x] 实现服务端通道禁放带计算与压占校验（语义对齐前端，附单测）
- [x] 接入确认/提交链路，返回可读冲突原因
- [x] 执行 mvn test 与前端构建/相关 e2e，更新任务卡与 status.md

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-22 | 立卡，列为通道补全第一期首项 | 验收链路硬规则，且反向约束前端数据契约 | 先做本项再做前端体验项 |
| 2026-10-22 | 实现：核验挂点复用既有 `OfficeLayoutValidationPolicy.requireConfirmedValid`（saveCanvas 与复核链路已调用），新增 `channelBands` 按前端 `channelRects` 语义计算禁放带（宽度+2×净距、包围盒裁剪、fromSecondRow 按家具 layoutSettings.firstBlockDepthMm 豁免），已确认家具旋转占地压占即抛"已确认家具压占通道，请调整家具或通道位置"；与既有策略一致只约束已确认家具、非法通道条目跳过（对齐前端确认路径不调用 validateChannels 的行为） | 语义逐条对照 frontend/app/utils/layoutChannels.ts | 服务端与前端确认校验语义一致；通畅性联动留 LAYOUT-029 |

## 验证结果

| 检查 | 结果与边界 |
| --- | --- |
| `mvn -f backend/pom.xml test` | 190 测试通过（0 失败，3 跳过为既有）；`OfficeLayoutValidationPolicyTest` 12/12，新增 4 例：压占拒绝+草稿豁免、贴边允许+净距扩展拒绝、二排起首排豁免/后续排仍拦截/无深度记录从严、横向通道与非法通道跳过 |
| 前端 `pnpm build`、通道专项与自动排版/规划/自适应/会议 e2e | 通过（UI mock），服务端改动不影响前端链路 |
| 真实数据库/真实提交流程 | 未测；核验逻辑经单元测试覆盖，集成验收待后续 |
