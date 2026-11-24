# FLOW-006 方案确认服务端购物车数量复核

状态：已编码待验证
更新日期：2026-11-20
关联：FLOW-005 方案页家具选品门禁；产品需求第 7、8 节

## 目标与验收

用户在 `/proposal` 确认整体方案时，服务端必须基于已保存画布与服务端购物车，逐空间复核家具数量及选品关联，不能依赖只在进入页面时执行的前端门禁。

- [x] 购物车总数量与空间画布家具件数完全一致才可通过。
- [x] 每件家具必须关联同空间内、商品 ID 相符且数量可用的购物车行。
- [x] 现有商品在售状态、尺寸与价格校验继续执行。
- [ ] Java 回归测试实际运行通过。

## 现状与依赖

`POST /api/v1/office/projects/{projectId}/proposal/catalog-check` 已被 `/proposal` 的“确认方案”调用。原 `OfficeService.checkProposalCatalog` 只复核已选商品的目录状态/规格/价格，没有核对服务端购物车数量。前端 `FLOW-005` 已按逐空间家具数、购物车数量和有效行关联门禁，但页面内商品详情弹窗可以修改数量。

## 实施

- `OfficeService.checkProposalCatalog` 在目录复核前按空间比较最新画布家具和 `office_cart` 数量。
- 验证购物车行的空间、商品 ID、有效数量及画布家具 `productSelection.cartLineId` 关联；失败抛业务错误，使现有确认请求不标记方案已确认。
- 新增 `OfficeProposalCartValidationTest` 覆盖购物车超量、数量匹配和错误购物车行关联。

## 验证记录

| 日期 | 环境 | 命令 | 结果 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-11-20 | 本机 Oracle JDK 17；项目要求 JDK 21 | `mvn -f backend/pom.xml -Dtest=OfficeProposalCartValidationTest test` | 编译阶段失败：JDK 17 不支持 Java 21 release | 未运行测试断言 |
| 2026-11-20 | 本机 Oracle JDK 17（临时覆盖版本） | `mvn -f backend/pom.xml clean -Djava.version=17 -Dtest=OfficeProposalCartValidationTest test` | 编译阶段失败：源代码使用 Java 21 `Executors.newVirtualThreadPerTaskExecutor()`；未修改项目配置 | 需在 JDK 21 环境运行专项与 Java 全量测试 |
| 2026-11-20 | 工作区 | `git diff --check` | 通过 | 业务测试待执行 |

## 交付与下一步

代码已实现，等待具备 JDK 21 的环境执行专项及全量 Java 测试；测试通过后更新验收状态。本轮未部署、未进行真实服务端业务验证。
