# LAYOUT-049 参考原图一键清空并识别

状态：已实现并通过模拟 UI 回归

日期：2026-11-27
关联：LAYOUT-043 参考原图与混排布局

## 目标与验收

已应用排版（画布有家具）后打开「参考原图摆位」点击「识别原图家具」会被「请清除当前画布上的家具」拦截，而参考面板打开期间画布只渲染参考层、普通家具不可选中删除，用户只能返回组合布局手动清空后再回来识别，操作繁琐。目标：拦截提示旁提供一键「清空画布家具并继续识别」，用户显式确认后在面板内完成清空并自动发起识别，不离开当前面板；不违反「重新识别不静默清空用户编辑」原则（有显式确认弹窗）。

## 实现

- `LayoutReferencePanel.vue` 新增 `gateClearAndRecognize` 标记：仅 `recognize()` 的空画布拦截置为 true，`applyCurrent()` 的兜底拦截置为 false（识别结果面向空画布，应用拦截不提供一键清空，避免用旧结果覆盖新排版）。
- 新增 `clearAndRecognize()`：`window.confirm` 显式确认后调用既有 `store.clearFurniture(zoneId)`（同步扣减关联购物车行），清空 gate 状态后立即重新执行 `recognize()`。
- 模板在 gate 提示下渲染 `data-testid="reference-clear-and-recognize"` 按钮，识别进行中禁用。

## 验证

- `pnpm build`：通过（既有警告不变）。
- `node scripts/e2e-preview-drag.mjs`：通过，全部 API 模拟。原「返回组合布局→清空排版→重开面板→再点识别」迂回路径替换为一键清空按钮场景：拦截后点击按钮、接受确认弹窗、画布家具清空、识别自动发起且按钮禁用，后续跨标签页任务保留等既有断言不受影响。运行需指定 `PLAYWRIGHT_EXECUTABLE_PATH` 指向本机已有 chromium_headless_shell-1254（本地 playwright 1.64.0 默认期望 1248，未安装）。
- 未执行真实服务验收，未部署；未提交。

## 2026-11-27 确认方式改为面板内两步确认

初版用浏览器原生 `window.confirm`，样式与项目不一致且位置固定在浏览器顶部。改为面板内两步确认卡片（`role="alertdialog"`）：首击「清空画布家具并继续识别」展开确认卡片，说明影响范围并提示可撤销，「确认清空并继续」执行清空 + 识别，「取消」回到首击状态。新增 `confirmingClear` 状态，`recognize()` 重置 gate 时一并复位；`data-testid="reference-clear-confirm-ok"` 供 e2e 使用。验证：`pnpm build` 与 `node scripts/e2e-preview-drag.mjs` 复跑通过（条件同上）。

## 2026-11-28 按钮颜色对齐主题

「清空画布家具并继续识别」按钮与确认卡片、确认按钮由红棕色（#a65e4c）改为主题紫色（bg-accent / border-accent / text-accent-ink / bg-accent-tint），与全局主色一致；gate 错误提示文字保留红棕（错误语义）。识别写入门禁弹窗的「取消识别并继续」同步改为 bg-accent。验证：`pnpm build` 与 `node scripts/e2e-preview-drag.mjs` 复跑通过（选择器全部走 testid，不受颜色影响）。

## 2026-11-28 打开面板即亮出清空入口

画布有家具时，参考面板裁图完成后直接显示「请清除当前画布上的家具」与「清空画布家具并继续识别」，不再要求先点一次注定被拦截的「识别原图家具」。识别任务进行中（busy）不亮出，避免与「正在识别」状态冲突。e2e 对应场景改为不点识别直接断言入口出现，并保留「点识别仍不发起请求」的断言。验证：`pnpm build` 与 `node scripts/e2e-preview-drag.mjs` 复跑通过。

## 2026-11-28 面板内取消识别

「正在识别…」旁新增「取消识别」按钮：走既有 dismiss 放弃任务，面板立即回到可重试状态（busy 计算排除已放弃记录）。配套修复 store 两处隐患：取消后旧请求仍在飞行时不再复用 inflight（允许立即重新识别）；旧结果落地按运行令牌（runTokens）丢弃，不写进新记录——Pinia 状态是响应式代理，不能比对象引用。e2e 场景 4a/4b 覆盖取消后重试与门禁联动。验证：`pnpm build` 与 `node scripts/e2e-preview-drag.mjs` 复跑通过。

## 2026-11-28 取消识别直接回到组合布局

取消识别后原来停留在参考面板（显示「已取消识别」），组合方案区被面板占住，用户须点「返回组合布局」或切页才能继续生成组合预览。改为取消即关闭面板（dismiss + emit close），任务已放弃不会自动重开，当前界面立即恢复组合方案与「生成组合预览」。「取消识别」按钮改主题紫（border-accent / text-accent-ink / hover:bg-accent-tint）。e2e 场景 4a/4b 同步调整。验证：`pnpm build` 与 `node scripts/e2e-preview-drag.mjs` 复跑通过。
