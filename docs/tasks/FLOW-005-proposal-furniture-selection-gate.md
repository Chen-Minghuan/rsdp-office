# FLOW-005 方案页家具选品门禁

2026-11-24 更新：清单入口直接打开独立页面，选品差异移到页内核对区域，仍阻止最终确认。见 [FLOW-007 界面反馈修正](FLOW-007-product-scheme.md#2026-11-24-界面反馈修正)。下文保留原实现记录。

状态：已实现，跨楼层浏览器回归已通过（模拟 API）

日期：2026-11-19
更新日期：2026-11-23
基线/交付版本：`becbe3d`；弹窗交互调整见当前分支
关联：产品需求第 6、7、8 节；LAYOUT-045；FLOW-006

## 目标与验收

进入整体方案页前，阻止家具布局与购物车选品不一致的项目进入方案确认。缺少选品时，用户可以直接回到对应楼层、空间和家具的设计上下文补齐选品。

- 比较当前项目每个空间的画布家具数量与购物车 `qty`，数量不一致时阻止进入 `/proposal`。
- 每个画布家具必须持有有效的购物车选品关联；缺失关联时展示家具名称、毫米规格和缺失数量。
- 选择弹窗中的空间卡片，或使用 Enter/Space 激活卡片后，先关闭弹窗，再进入该楼层及空间的设计页。
- 门禁不替代后端方案确认核验；商品状态、尺寸和价格仍由后续确认流程复核。

## 实现与边界

- 全局 proposal 路由中间件读取当前项目画布和购物车，以空间为单位生成缺失选品项；可通过项目报价等正常入口触发，不依赖单一页面按钮。
- `ProposalFurnitureGateDialog` 显示可跳转的空间卡片；弹窗最大高度为 `80dvh`，空间列表独立滚动，标题和底部操作固定。
- 初始门禁实现已在 `becbe3d` 提交。当前分支的弹窗跳转与滚动样式调整保留浏览器交互待复核的状态，未将该项表述为真实业务验收。
- `/proposal?floorId=…` 以路由目标楼层为唯一门禁对象：守卫先加载目标 `workspace` / `office` 状态，再读取画布家具、购物车与选品关联；状态未就绪时中止 proposal 导航，不使用来源楼层数据放行。
- 楼层树、路由 watcher 与 proposal 守卫同向切换同一楼层时复用同一完成信号；守卫加载目标楼层期间，旧 URL 不会反向切回来源楼层。
- 删除画布家具与购物车行同步由 LAYOUT-045 处理；服务端在 FLOW-006 继续对确认请求复核数量、购物车行、空间和商品关联。

## 验证记录

| 日期 | 版本/环境 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-11-19 | 前端本地构建 | `pnpm build` | 通过，门禁代码可构建 | 未进行真实服务验收 |
| 2026-11-19 | 文档与分支差异 | 本地链接与 `git diff --check` | 当时记录为通过 | FLOW-005 任务文件在本次文档修复前未随分支提交 |
| 2026-11-19 | 浏览器交互 | 未执行 | 用户明确要求不运行 Playwright | 卡片点击、键盘激活、滚动和跳转仍待浏览器复核 |
| 2026-11-23 | 前端本地构建 | `pnpm build` | 通过；Nuxt 构建成功 | Node 22.18.0 低于 Nuxt 提示的兼容版本，且保留既有重复导入/大 chunk 警告 |
| 2026-11-23 | 项目楼层 UI mock | `pnpm e2e:project-floors` | 通过；覆盖楼层树内切换、来源楼层匹配而目标楼层超量时的拦截、目标恢复匹配后的 proposal 放行 | API 由 Playwright 拦截；未验证真实后端、登录会话或生产数据 |
| 2026-11-23 | 工作区差异 | `git diff --check` | 通过 | 不代表真实服务端或生产部署验收 |

## 后续

- 使用真实项目、楼层、空间、购物车和已选商品验证门禁拦截、缺失内容与跳转定位。
- 与 FLOW-006 的服务端复核一并确认：前端拦截不能成为方案确认的唯一防线。

## 2026-11-25 门禁恢复

9cbcb1c 在恢复独立清单页时移除了路由中间件、弹窗与共享状态，本日按当前架构重新实现：

- 新中间件 `frontend/app/middleware/proposalSelectionGate.global.ts`：跳转 `/proposal` 前确保目标楼层数据就绪并读取 `store.proposalFurnitureCartMismatches`；不一致时写入共享状态并 `abortNavigation()`；`?preview=1` 演示模式与无楼层时放行。
- 跨楼层校验先 `workspace.selectFloorContext(floorId)` 再 `store.activateFloor`（`office.init` 始终读取 `currentFloorId`，旧实现直接切 store 导致校验数据串楼层、门禁失效）；中止跳转前把楼层上下文与画布数据还原到来路楼层，避免停留页卡在加载遮罩。
- 直达/刷新（`from.matched` 为空）放行并叠加弹窗，方案页行内差异提示保留兜底。
- 新弹窗 `ProposalSelectionGateDialog.vue`（Teleport + role=dialog + Escape/遮罩关闭），点击区域经 `proposalFurnitureSelectionRoute` 跳回画布（可排版时 `mode=design&view=products`，否则退回复核）；共享状态在 `composables/useProposalSelectionGate.ts`，弹窗挂载于 `app.vue`。
- `e2e-project-floors` 三段用例更新为新语义：楼层树切到不一致楼层被拦截并弹窗、直达另一楼层不一致被拦截且只显示目标楼层、补齐后放行；该测试使用 `.output` 预构建产物，改动后须先 `pnpm build`。

### 验证记录（2026-11-25）

| 命令 | 结果 | 未覆盖项 |
| --- | --- | --- |
| `pnpm build` | 通过 | 无独立 lint/typecheck |
| `MOCK_API=1 pnpm e2e:project-floors` | 通过，含门禁拦截/还原/放行三段 | API 全部模拟，未验证真实后端 |
| `MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows`、`e2e:proposal-3d`、`e2e:product-scheme`、`e2e:project-status` | 通过 | 真实联调与业务验收待执行 |
