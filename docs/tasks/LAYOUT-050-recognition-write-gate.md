# LAYOUT-050 识别进行中的排版写入门禁

状态：已实现并通过模拟 UI 回归

日期：2026-11-28
关联：LAYOUT-043 参考原图与混排布局；LAYOUT-049 参考原图一键清空并识别

## 目标与验收

识别原图家具进行中（请求跨页面不中断，属既有设计），用户仍可手动添加家具、应用自动布局或清空排版。识别结果面向空画布，落地前的 layoutSignature 兜底会丢弃结果——数据不会冲撞，但白白浪费一次识别（可能为收费视觉 API）。目标：同空间的家具写入操作在识别进行中先弹门禁确认——「取消识别并继续」放弃任务后放行，「保持识别」中止本次操作；其他空间不受影响；不写动画布的预览不拦截。

## 实现

- `layoutReference` store 新增 `runningTaskFor(zoneId)`：进行中且未放弃的该空间任务。
- 新增 `useRecognitionGuard` composable：`gateFurnitureEdit(zoneId)` 无任务直接放行，有任务则挂起 Promise 并打开门禁；`answerGate` 结算，取消识别走既有 `dismiss`（settle 时丢弃结果，请求本身不中断）。共享 `pending` 状态用 `useState`，旧未决请求先按「保持识别」结算防悬挂。
- 门禁弹窗 `OfficeCanvas/RecognitionGateDialog.vue`：画布中央（CanvasBoard 内 absolute 居中），`role="alertdialog"`，项目风格。
- 接入六个写入入口（同空间判断）：`DesignLayoutSidebar` 在画布添加/应用排版、`ProductLayoutEditor` 添加模块/清空排版、`MixedAutoLayoutPanel` 应用组合、`DesignWorkflowSidebar` 清空排版。
- 不覆盖的画布内拖动/改尺寸等既有编辑：仍由 layoutSignature 兜底丢弃结果，不拦。

## 验证

- `pnpm build`：通过（既有警告不变）。
- `node scripts/e2e-preview-drag.mjs`：通过，全部 API 模拟。新增场景 4：识别挂起期间从产品选型点图例 → 门禁弹出 → 「保持识别」不开始摆放 → 再次点击 → 「取消识别并继续」开始摆放且任务被放弃，到达的结果被丢弃。运行需指定 `PLAYWRIGHT_EXECUTABLE_PATH` 指向本机 chromium_headless_shell-1254。
- `pnpm e2e:workflows`：通过，全部 API 模拟。
- 未执行真实服务验收，未部署；未提交。
