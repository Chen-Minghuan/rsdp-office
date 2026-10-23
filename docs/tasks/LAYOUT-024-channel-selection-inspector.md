# LAYOUT-024 通道画布选中态与检查器编辑

状态：已实现并通过验证
更新日期：2026-10-22
负责人：按实际填写
基线/交付版本：工作区
关联：[LAYOUT-021](LAYOUT-021-canvas-aisle-channels.md)；SpatialInspector 现有家具/空间选中编辑

## 目标与验收

触发：设计模式下点击通道条带。
结果：条带高亮为选中态并弹出画布内浮动编辑卡（宽度、两侧净距、位置、「仅从二排起」、移除），编辑走 `store.updateZoneChannels` 同一入口；点空白、选中家具/对象、切换工具或 Esc 取消通道选中。**实施偏差**：design 模式没有属性检查器（右侧 DesignSidebar 是选品面板，SpatialInspector 只用于识别核对环节），故改为画布内浮动编辑卡（屏幕坐标系，不随画布缩放），比隐藏在校验面板更直接。
范围：选中态与检查器编辑；不改拖动/钳制逻辑；排版面板通道卡片保留（两处编辑同写一份数据）。

- [x] 验收项 1：点击条带高亮且浮动卡可改宽度/净距/位置，画布与面板同步（同写 zone.layoutPreferences）；撤销恢复
- [x] 验收项 2：选中家具/空间/空白处/切换工具正确互斥；Esc 取消选中；Delete 移除选中通道
- [x] 验收项 3：专项 e2e 断言 + 既有回归通过

## 现状与依赖

- 条带现状：CanvasBoard 渲染于 `data-testid="layout-channels"`，只有 pointerdown 拖动，无选中概念；编辑只能去排版面板通道卡片。
- SpatialInspector 已有家具/空间选中编辑路径可复用；注意 `selectedId`/家具多选与通道选中的互斥。

## 实施步骤

- [x] 引入通道选中状态（selectedChannelId，与家具/对象选中互斥）
- [x] 画布浮动编辑卡，复用 updateZoneChannels；宽度阈值提示同步显示
- [x] 专项 e2e + 回归，更新文档

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-22 | 立卡 | 用户操作路径目前必须翻排版面板，不顺手 | 第二期第一项 |
| 2026-10-23 | 实现：点选条带选中（高亮实线边框+加深斜纹），画布顶部浮动编辑卡；选中态在 pointerdown 即建立、拖动提交后保持；互斥清理挂在 select 工具入口、chooseTool、Esc、家具放置 watch | design 模式无检查器，浮动卡替代并记录偏差 | 宽度拖柄（LAYOUT-025）可复用选中态决定手柄显示 |
| 2026-10-23 | 编辑卡 aria-label 改为「通道属性宽度/净距/位置/首排不断开」 | Playwright getByLabel 子串匹配，「选中通道位置（毫米）」与侧栏「通道位置（毫米）」撞名导致 strict violation | e2e 命名注意子串包含关系 |

## 验证结果

| 检查 | 结果与边界 |
| --- | --- |
| `pnpm build` | 通过；无独立 lint/typecheck 配置，不作相应声称 |
| `OUT_DIR=/tmp/rsdp-aisle-channels node scripts/e2e-aisle-channels.mjs` | 通过：新增 5.7（点选出卡、卡内改宽度生效并恢复、Esc 取消）与第 9 步（选中后 Delete 移除、撤销恢复） |
| `pnpm e2e:auto-layout` / `e2e-layout-planning` / `e2e-adaptive-layout` / `e2e-meeting-layout` | 通过；UI mock |
| `MOCK_API=1 pnpm e2e:smoke` | 默认 3001 端口被一旧构建残留实例占用导致资源 500（既有环境残留，未动该进程）；改用 `BASE_URL=http://127.0.0.1:3011` 对当前构建跑通全部 6 步 |
| `git diff --check` | 通过 |
