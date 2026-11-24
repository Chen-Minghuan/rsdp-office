# LAYOUT-045 删除模块同步购物车

状态：已实现并通过模拟 UI 回归

日期：2026-11-20
关联：LAYOUT-006 单位模块删除；FLOW-005 方案页家具选品门禁

## 2026-11-23 PR #12 合并复核：共享行历史同步

状态：已修复，通过模拟 UI 回归。复现两件家具共享 `qty=2` 的行，删除一件后撤销仅恢复本地数量，服务端仍为 `1`。撤销、重做现共用历史差异同步，处理行新增、删除及数量变化；请求固定楼层及会话上下文，迟到响应只回写行 ID，不覆盖最新数量。历史快照解析服务端行 ID 的连续映射，恢复新 ID 后再次保存画布关联。

验证：Nuxt 生产构建通过；`node scripts/e2e-delete-modules.mjs` 核验共享行扣减/撤销/重做的服务端模拟数量、延迟请求后的最新数量、三次恢复生成新 ID 后的关联，以及既有跨楼层和清空场景；模块推荐、项目楼层和工作流 UI 回归通过；购物车扣减 6 个用例、路由判断及 `git diff --check` 通过。UI 全部拦截 API，未执行真实服务或服务器发布。本轮仅修改前端，Java 沿用 PR 复核的 303 项、0 失败、3 跳过结果，未重复执行。

## 目标与验收

删除已选择商品的家具实例时，同步移除该实例关联的购物车行；不影响同商品的其他模块、手动购物车项或其他空间。无有效选品关联的家具删除行为保持不变。撤销需恢复模块、购物车行及有效选品关联。

## 实现

- `deleteObject` 对家具转发到 `deleteFurnitureGroup`，让画布删除按钮、快捷键及排版侧栏共用一条清理路径。
- 删除根据 `productSelection.cartLineId` 精确移除本地购物车行，并通过既有购物车 API 删除服务端行；画布和购物车同属一次撤销快照。
- 撤销恢复购物车行时，以服务端返回的新行 ID 更新家具 `productSelection.cartLineId`；购物车服务端变更串行执行，避免快速撤销/重做造成请求顺序错乱。

## 验证

- `pnpm build`：通过；Node 22.18.0 版本低于项目建议范围，构建存在既有重复导入与 chunk size 警告。
- `pnpm e2e:delete-modules`：通过，全部 API 模拟。覆盖画布按钮与 Delete 快捷键按 `cartLineId` 删除对应行、同商品额外购物车行及其他空间保留、撤销服务端加购和新行 ID 关联回写、无选品关联模块删除不改购物车。
- `git diff --check`：最终检查待执行。
- 全程使用模拟 API；未执行真实服务/商品业务验收，未部署。

## 2026-11-23 购物车删除/清空/计数修复

用户复核发现四处购物车同步缺陷，本轮一并修复：

1. **删除队列跨楼层串请求**：`useOfficeApi` 购物车写操作（`addCartItem`/`updateCartItemQty`/`deleteCartItem`/`clearCart`）新增可选 `workspaceId` 参数；`deleteFurnitureGroup`、`redo`、`restoreCartLinesOnServer` 在任务入队时捕获当前楼层 ID，串行队列执行时固定使用快照楼层，不再读取切换后的当前楼层，避免删除请求发到错误楼层留下脏行。
2. **共享行整行误删**：删除/清空改为按被移除家具对每行的引用数扣减——`qty > 引用数` 时调用 `updateCartItemQty` 扣减，`qty <= 引用数` 时才删除整行；多件家具共享一条 `qty>1` 的行时，删一件不再使剩余家具失去有效选品关联。扣减/删除计划抽取为纯函数 `frontend/app/utils/cartLineReduction.ts`，删除与清空共用。
3. **清空排版不同步购物车**：`clearFurniture` 现在复用同一扣减计划清理被清空家具的关联购物车行；未关联的手动加购行和其他空间行保留，仍由方案门禁提示核对。
4. **选品计数与分组键不一致**：`ModuleProductRecommendations.vue` 的计数键改为复用分组键 `sameModuleKey`（含 `furnitureTemplateId`/`moduleSnapshot`/`heightMm`/宽/深），与服务端 `sameModule` 对齐；同名同宽深但不同高度的分组不再串用「已选」计数。

验证：

- `node scripts/test-cart-line-reduction.mjs`（新增 6 个纯函数用例：共享行扣减、引用耗尽删行、清空保留无关行、无关联不动购物车、数量大于引用数只扣引用）：全部通过。
- `pnpm build`：通过（Node 22.18.0，既有警告不变）。
- `pnpm e2e:delete-modules`：通过，全部 API 模拟。
- `pnpm e2e:workflows`：通过。
- `pnpm e2e:module-recommendations`：改动前后同样失败（脚本第 108 行 beforeEach 重置 `cart`，冲掉第 135 行预置的手动行），属脚本既有缺陷，与本次修复无关。
- `git diff --check`：通过。
- 未执行真实服务验收，未部署；未提交。

## 2026-11-23 复核遗留问题修复

代码复核后修复三处遗留风险：

1. **方案页误改回退**：`proposal.vue` 中商品清单弹窗分支被误写为 `kind === 'productszhang'`，导致空间商品清单详情无法打开，已恢复为 `kind === 'products'`。
2. **非队列路径固定楼层**：`addToCart`、`addComboToCart`、`changeQty` 中的购物车写调用补充传入 `this.workspaceId`，与其他已固定楼层的调用口径一致，用户点击后即使立即切楼层也不会串请求。
3. **e2e 覆盖补齐**：`e2e-delete-modules.mjs` 新增三个场景断言——共享行（qty=2）删一件家具走 `PUT qty=1` 扣减而非整行删除；删除入队后切到二层，请求仍发往原楼层（通过 workspace store 真实切换链路）；`clearFurniture` 同步删除关联购物车行、未关联行保留。

验证：

- `node scripts/test-cart-line-reduction.mjs`：6 例通过。
- `pnpm e2e:delete-modules`（含三个新场景）：通过，全部 API 模拟。
- `pnpm build`：通过（Node 22.18.0，既有警告不变）。
- `git diff --check`：通过。
- 未执行真实服务验收，未部署；未提交。
