# QUOTE-002 报价确认的方案确认门禁

状态：已实现待验收（Java 断言受本机 JDK 17 工具链阻塞未执行，见"验证记录"；前端构建与三项模拟 UI 回归通过）

## 背景与范围

业务顺序要求：空间复核确认 → 排版确认 → 商品推荐 → 方案确认 → 正式报价。此前 `ProjectQuotationService.confirm()` 与方案确认完全独立，只校验报价行本身（商品存在、楼层归属项目、数量价格非负），只要请求里存在商品明细行就能生成 `CONFIRMED` 正式报价，绕过空间复核确认、排版确认与方案确认。

本任务在报价确认入口补后端权威校验，并在项目清单页和报价页补前置状态提示（不依赖按钮禁用）：

- **后端**：确认报价时，报价行涉及的每个楼层必须持有当前有效的方案确认（`office_proposal_confirmation` 快照未失效），否则拒绝。
- **前端**：`/proposal` 确认方案前提示待复核空间与排版未确认空间；`/workspace/quotes` 确认报价前按楼层提示方案确认缺失/失效。

试算（preview）与导出前试算（previewConfirmation）不拦截，保留先查看金额的能力；门禁只拦最终确认。

## 规则

- **门禁范围**：报价明细行中出现的楼层（按 `floorId` 去重），而非项目全部楼层；没有商品行的楼层不拦截。
- **有效性判定**：复用 `OfficeProposalConfirmationService.latest(floorId)`——返回 null（从未确认）或 `current=false`（修订变化、快照指纹不匹配、排版合法性复核失败、记录被退役）均视为不满足。只有指纹比对能识破同 revision 覆盖保存，因此不新写轻量查询。
- **调用时机**：`confirm()` 中 `draftBuilder.build()` 之后、项目行 `FOR UPDATE` 锁之前（fail-fast 不占锁；floorId 已被 draftBuilder 校验属于本项目）。
- **软检查说明**：方案确认走楼层工作区锁、报价确认走项目锁，两把锁不互斥，门禁通过后事务提交前方案理论上仍可被改——与"空间复核→方案确认"的既有竞态同级，属可接受的软检查，不为它拉长锁持有时间。
- **错误文案**：`BusinessException("楼层「A、B」的方案尚未确认或已失效，请先在项目清单页确认当前方案后再确认报价")`，经 `GlobalExceptionHandler` 以 `Result.fail`（body code=400）透出到前端弹窗。
- **权限兼容**：报价确认要求工作区主号（`requireWorkspaceOwner`）；`latest()` 内部走 `requireFloorAccess`，主号自动满足，无 403 风险。

## 前端提示

- `/proposal`（项目清单页）：新增"N 个空间待复核确认 · M 个空间排版未确认"可展开横幅（`data-testid="proposal-precheck-issues"`，复用选品差异横幅样式），逐空间列出并提供「去复核空间」「去排版确认」跳转。数据完全本地推导：`store.pendingReviewZones`（复核凭据）与逐空间家具 `layoutConfirmed`（只统计有家具的空间，无家具空间不算阻塞）。确认按钮禁用条件增加前置条件与 `confirmed`（已确认后不再重复提交）；`confirmProposal()` 守卫同步。
- `/workspace/quotes`（报价页）：试算成功后按楼层并发读取 `loadProposalConfirmation`，单楼层读取失败按"未知"处理不阻塞（后端兜底）。横幅 `data-testid="quote-confirmation-blockers"` 区分"尚未确认方案"与"方案已失效，需重新确认"，逐楼层链接到 `/proposal?floorId=…`。确认按钮在状态加载中或存在阻塞楼层时禁用；`openConfirmation()` 内置防御性检查；后端 400 文案经既有弹窗错误路径原样透出。

## 验证记录

- 前端构建：`pnpm build` 通过。
- 模拟 UI 回归（全部 API 模拟）：
  - `node scripts/e2e-quotes.mjs` 通过：新增门禁三段断言（从未确认 → 横幅+禁用；已失效 → 横幅+禁用；有效 → 横幅消失+启用），既有费用增删、弹窗、下载/确认同载荷、失败保留、窄屏用例不受影响。
  - `node scripts/e2e-product-scheme.mjs` 通过（其 mock 已带 CONFIRMED 复核凭据与 `layoutConfirmed: true`，方案确认按钮仍可用）。
  - `node scripts/e2e-project-floors.mjs` 通过。
- Java：`mvn -f backend/pom.xml test` **未执行**——本机仅有 JDK 17（`D:\Java\jdk-17`），项目要求 release 21，编译阶段被工具链阻塞（与 [FLOW-006](FLOW-006-server-proposal-cart-validation.md) 记录的情况相同）。`ProjectQuotationServiceTest` 新增 `confirmRejectsFloorWithoutProposalConfirmation`、`confirmRejectsStaleProposalConfirmation`、`previewConfirmationSkipsProposalGate` 三个用例及 `stubScenario` 默认有效确认桩，断言待具备 JDK 21 的环境执行。
- 真实后端联调与业务验收未执行，未部署。
