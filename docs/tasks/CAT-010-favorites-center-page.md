# CAT-010 收藏中心页面与全角色收藏入口

## 2026-11-24 PR #16 合并修复

状态：已修复并验证。保留收藏弹窗未提交的分组勾选，刷新商品/收藏夹时只清理已删除的分组；当前商品直接取消某分组时只更新对应勾选。副操作回传已保存关系，避免把草稿误当成已收藏，操作进行中禁止并发保存。认证关闭时按需创建禁用登录、无工作区的本地收藏身份，个人和项目收藏继续保留真实用户外键；认证开启时禁止使用该开发身份。

- 前端生产构建、`e2e-favorites-page`、`e2e-account-center`、`e2e-workflows` 通过。新增回归核验清理其他商品保留勾选、进行中的移除禁止并发保存、移除当前商品只调整对应勾选、删除收藏夹保留其余草稿，以及取消弹窗不提交草稿；UI 拦截 API。
- Java 全量 341 项，0 失败、0 错误、3 跳过；含新增开发身份隔离测试，以及实际运行的 `LocalFavoritePostgresTest`。后者在临时 PostgreSQL 独立 schema 内执行真实 Mapper 与收藏服务，核验默认夹幂等创建、禁用用户属性及个人/项目收藏新增、回读、删除；商品目录和项目权限依赖使用 mock，不代表真实商品源或会话联调。
- 真实数据库测试使用 `RSDP_TEST_POSTGRES_URL`、`RSDP_TEST_POSTGRES_USER`、`RSDP_TEST_POSTGRES_PASSWORD` 指定测试库后执行 `mvn -f backend/pom.xml test`；每次创建独立 schema 并清理。未提供连接时该测试会跳过，不算真实数据库验收。
- `git diff --check` 通过；未执行服务器部署或真实登录页面联调。

## 目标

- 侧栏账号浮层新增「收藏」入口：所有登录角色（经销商、项目成员、管理员）可见，点击直达 `/favorites`；商品报价、工作区账号仍仅经销商可见，非经销商不渲染入口、不发起报价/成员请求。
- 新增收藏中心页面 `/favorites`：个人收藏（全部/默认/自建收藏夹、新建收藏夹、搜索）与项目收藏（项目选择器、空状态）两个页签，商品卡片复用商品库样式，爱心打开既有统一收藏弹窗。

## 方案

- 数据库零改动：继续复用 `office_favorite_folder`、`office_favorite_item`、`office_project_favorite_item`，按 `created_at` 倒序返回最近收藏。
- 后端只新增两个只读列表接口，返回既有 `ProductListItemDto`，复用 `ProductQueryService.listProducts(false)` 做商品可见性过滤；不可见商品不展示，收藏记录保留。
- 前端浮层对所有角色开放；徽标请求按角色拆分（所有角色读取个人收藏数，经销商额外读取最近报价与成员数）。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/office/favorites/items?folderId=` | 个人收藏商品列表；不传 `folderId` 为全部收藏（按商品去重）；他人 `folderId` 返回 403，不存在返回业务错误 |
| GET | `/api/v1/office/projects/{projectId}/favorites/items` | 当前账号在指定项目下的收藏商品列表；先校验项目成员权限 |
| DELETE | `/api/v1/office/favorites/folders/{folderId}` | 删除自定义收藏夹：事务内先删该夹收藏关系再删收藏夹；默认收藏夹返回业务错误，他人收藏夹 403，不存在返回业务错误 |

两者均按收藏时间倒序；`/favorites/items` 与既有 `/favorites/{rspuId}` 由 Spring 精确字面量优先匹配，不冲突。

## 删除收藏夹交互

- 自定义收藏夹行悬停/聚焦显示红色删除按钮（`group-hover`/`focus-visible`），「全部收藏」与「默认收藏夹」不提供删除；收藏中心收藏夹栏与统一收藏弹窗个人页签行为一致。
- 点击弹出原生确认对话框：说明商品去向（其他收藏夹不受影响，仅在此夹的商品从个人收藏移除），红色「确认删除」；删除中禁用按钮，失败就地提示。
- 删除成功后若正在查看该夹，自动回到「全部收藏」，并刷新收藏夹列表、全部收藏计数与商品列表。
- 弹窗内删除使用独立确认对话框（顶层叠加），删除后弹窗保持打开并重新加载收藏夹勾选状态，同时 `saved` 事件通知页面刷新；侧栏徽标经 `favorite-count-version` 同步失效。

## 收藏夹内容展开与取消收藏（2026-11-23 追加）

- 统一收藏弹窗个人页签：每个收藏夹（含默认收藏夹）右侧有常显展开按钮，展开后通过 `GET /favorites/items?folderId=` 展示商品缩略列表（图/名称/编码）；每件商品可点「取消收藏」逐件移出当前收藏夹，复用 `PUT /favorites/products/{rspuId}` 保存去掉当前夹后的勾选集合，弹窗保持打开并刷新计数与勾选状态。
- 收藏中心卡片：实心爱心点击改为「取消收藏」确认弹窗——具体收藏夹中只移出当前夹，「全部收藏」中清空所有个人收藏夹勾选，项目页签保存 `selected=false`；卡片底部新增「调整收藏夹」入口打开统一弹窗做分组调整。
- 后端零新增接口；取消收藏与删除收藏夹都会递增 `favorite-count-version` 同步侧栏徽标。
- e2e 新增：展开列表加载与逐件取消（断言保存载荷、计数、徽标、弹窗不关闭）、项目卡片取消（selected=false）、全部收藏取消（folderIds=[]）、调整入口打开对应页签。

## 审查修复（2026-11-23 复审）

1. 项目收藏页在非当前项目点击爱心：`FavoriteFolderDialog.open` 新增可选 `projectId`，页面传入正在查看的项目，弹窗默认选中该项目；`projectSaved` 回调按载荷 `projectId` 匹配后才刷新列表。e2e 覆盖跨项目保存并断言写入项目。
2. 收藏夹列表与全部收藏计数增加 `folderRequest`/`countRequest` 代次守卫；新建收藏夹改为成功后重新拉取列表，旧的慢响应不得覆盖新列表（e2e 延迟首读 700ms 验证）。
3. 认证关闭的本地开发模式：两个收藏服务 `requireUserId` 回退到固定 `LOCAL-DEV` 用户，不再固定 403；项目成员校验在开发模式本就跳过。新增开发模式单测，既有测试显式开启认证。
4. 同页收藏保存/删除收藏夹后，`useOfficeApi` 递增共享 `favorite-count-version`，侧栏徽标立即失效并在下次展开（或当前展开时）重新读取个人收藏数。

## 前端改动

- `AppUserSession.vue`：移除 `workspaceCenter` prop，浮层对所有角色开放；新增「收藏」菜单项（heartSolid 图标 + 个人收藏数徽标，为 0 不显示）；`showDealerEntries` 控制报价/账号入口与请求；收藏数与经销商徽标分别缓存，路由切换时重置，项目切换不刷新个人收藏数。
- `AppSidebar.vue`：不再按角色关闭浮层。
- `pages/favorites.vue`：个人/项目页签；个人页含收藏夹侧栏与新建收藏夹；项目页含项目选择器（当前项目优先且默认）；页签/收藏夹/项目切换均重置搜索；`personalRequest`/`projectRequest` 代际守卫拒绝过期响应；保存收藏后刷新对应列表与计数。
- `components/FavoritesProductGrid.vue`：两个页签共用的商品网格，含加载、错误重试、空状态（去商品库挑选）与搜索无结果状态。
- `app.vue`：`/favorites` 与 `/products` 一样不按楼层重挂载，也不参与楼层加载占位。
- `useOfficeApi.ts`：新增 `loadFavoriteItems`、`loadProjectFavoriteItems`。

## 验证记录（2026-11-23）

- TDD：先写失败测试（编译期缺失方法/选择器超时），再实现至通过。
- Java：`OfficeFavoriteServiceTest` 22 项、`OfficeProjectFavoriteServiceTest` 13 项通过；全量 335 项，0 失败、0 错误、3 跳过。
- 前端：`e2e-favorites-page`（收藏夹切换、搜索、新建/删除收藏夹、弹窗页签、跨项目保存、慢响应守卫、保存后徽标刷新、空状态、非经销商访问）、`e2e-account-center`（三角色门控与徽标请求）、`e2e-workflows`、`e2e-workspace-isolation`、`e2e-project-floors`、`e2e-quotes` 全绿；`pnpm build` 通过；`git diff --check` 通过。
- 未执行：真实 PostgreSQL + 真实会话的页面联调（8082 后端当前未运行）；收藏夹改名/删除、分页不在本期。

## 商品库卡片实心爱心直接取消收藏（2026-11-23 追加）

- 商品库（`/products`）卡片上，未收藏商品点空心爱心仍打开统一收藏弹窗（个人页签）；已收藏商品（实心爱心）点击不再打开收藏弹窗，而是弹出「取消收藏」确认对话框。
- 弹窗说明商品将从所有个人收藏夹中移除；确认后调用 `PUT /api/v1/office/favorites/products/{rspuId}`（`folderIds: []`）清空个人收藏，爱心变回空心，侧栏个人收藏数徽标经 `favorite-count-version` 同步刷新。后端零改动、零新增接口。
- 「调整收藏夹」为次要按钮，打开原统一收藏弹窗（个人页签）做分组调整；「取消」/关闭不发请求。
- 失败时就地提示并保留弹窗与实心爱心；保存中按钮禁用、对话框 `@cancel` 阻止 Escape 关闭。
- e2e 新增场景：实心爱心打开确认弹窗（含文案与调整入口）、取消不保存、确认保存载荷 `{rspuId: 'PRODUCT-2', folderIds: []}` 且爱心变为空心、失败保留弹窗与实心状态、保存中 Escape 不关闭、调整入口进入个人页签并保持默认收藏夹勾选。原有实心爱心调整分组流程改为经确认弹窗进入。
- 验证（2026-11-23）：TDD 先失败（`getByRole('dialog', { name: '取消收藏' })` 超时）再实现通过；`e2e:workflows`、`e2e:favorites-page`、`e2e:account-center`、`e2e:workspace-isolation`、`e2e:project-floors`、`e2e:quotes` 全绿；`pnpm build` 通过（25.2s）；`git diff --check` 通过。未执行：真实 PostgreSQL/真实会话联调（8082 后端未运行）；代码未提交。
