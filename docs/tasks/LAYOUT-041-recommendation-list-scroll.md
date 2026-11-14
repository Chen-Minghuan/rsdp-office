# LAYOUT-041 推荐列表内部滚动修复

状态：已修复并通过模拟回归与真实页面验证

日期：2026-11-14

## 问题

款式推荐恢复后，146 个候选全部撑开侧栏，用户无法向下滚动。真实隔离浏览器测量：侧栏可见高度约 741px，商品列表却达到 23530px；列表 `scrollHeight === clientHeight`，程序和滚轮后的 `scrollTop` 都为 0，因此不是画布拦截滚轮。

## 根因与修复

`DesignSidebar.vue` 根节点同时声明基础 `flex-none` 和嵌入模式 `flex-1`，最终计算为 `flex: 0 0 auto`，嵌入侧栏随内容撑高，内部 `overflow-auto` 无法形成滚动区域。

- 非嵌入/折叠分支保留 `flex-none`；嵌入分支只使用 `w-full flex-1 min-h-0`。
- 嵌入内容容器增加 `min-h-0`。
- `ModuleProductRecommendations.vue` 的候选滚动区改为 `flex-1 min-h-0 overflow-auto`，使剩余高度和滚动边界明确。

## 回归与实测

- `pnpm build`：通过；既有重复自动导入和 chunk 提示保留，本任务未扩大处理。
- `pnpm e2e:module-recommendations`：通过。专项显式提供真实项目/负责人和按用户隔离的草稿键，新增 30 条高贴合候选，在 1600×900 视口断言 `scrollHeight > clientHeight`、列表高度受视口约束且滚轮后 `scrollTop > 0`；原有尺寸、旋转、报价、批量应用、失效、空结果和错误回归均通过，全部 API 模拟。
- `BASE_URL=http://localhost:3000 pnpm e2e:workflows`：通过，全部 API 模拟。
- `MOCK_API=1 BASE_URL=http://localhost:3000 pnpm e2e:smoke`：通过，全部 API 模拟。
- 真实 `designer1` 会话、146 个候选：修复后列表 `clientHeight=100`、`scrollHeight=23530`，程序设置 `scrollTop=500` 后滚轮增至 900，确认内部滚动恢复。测试拦截画布 PUT，未改写真实布局；临时会话已吊销。
- 未修改后端、商品或画布数据，未部署生产环境，代码未提交。
