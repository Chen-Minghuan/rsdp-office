# PROJECT-005 楼层参考图与方案页最终报价展示

状态：已实现待验收（单元测试与模拟 API 的 UI 回归通过；真实后端 + 数据库联调未执行）
更新日期：2026-11-25
负责人：开发 A（Claude 实施）
基线/交付版本：feat/pic 分支（8befa3c…e022628 + 本次测试补充，未提交）
关联：[PROJECT-004](PROJECT-004-project-members.md)（项目级参考图与成员权限）、[QUOTE-001](QUOTE-001-project-quotation.md)（报价快照来源）、[FLOW-007](FLOW-007-product-scheme.md)（方案页）

## 目标与验收

触发：楼层需要自己的参考图（与项目级参考图分开）；方案页在存在已确认报价时展示最终报价。
结果：楼层参考图经 `PUT /api/v1/office/projects/{projectId}/floors/{floorId}/references` 整体替换保存；项目总览页上传/删除/刷新恢复，连续保存乱序不互相覆盖，只读成员无编辑入口；画布工具栏弹层展示当前楼层参考图。方案页最终报价取最新 CONFIRMED 报价快照中当前楼层行小计合计，缺价行（subtotal 为 null）不计入并红色提示，报价行与页面商品（productId+数量）严格一致时才展示。
范围：楼层参考图复用项目级校验与上传接口，不新建附件表；报价展示为只读展示，不改变报价生成与确认流程。

- [x] `office_design_floor.reference_images` 迁移（text 列，幂等）并注册 SchemaInitializer
- [x] PUT 接口整体替换；上限 10 张、仅本站 `/uploads/images/` 前缀、拒绝 `..`、名称 ≤200 字符；null/空列表即清空
- [x] 接口要求项目 EDIT 权限；楼层不存在或跨项目楼层拒绝且不产生写入
- [x] 项目总览页上传→保存→刷新恢复；连续两次保存乱序响应只生效最后一次；保存失败显示 role=alert
- [x] 只读成员（无 EDIT 权限）不显示上传/删除入口，后端仍独立鉴权
- [x] 画布工具栏「参考图」弹层展示当前楼层参考图
- [x] 方案页最终报价/已报价商品小计/缺价提示/无报价/快照与页面不一致五种展示口径
- [ ] 真实后端 + PostgreSQL 的端到端联调（本次未执行）

## 现状与依赖

- 项目级参考图契约与 `validateReferenceImages` 校验来自 [PROJECT-004](PROJECT-004-project-members.md)，楼层级整体复用。
- 报价快照来自 `GET /api/v1/office/projects/{projectId}/quotation/latest`（QUOTE-001）；页面商品口径为空间有清单取清单、无清单取画布家具。
- 前端 `ProjectReferenceImagesEditor` 为通用编辑器（grid/names/hidden 三种列表模式），持久化由父组件负责。

## 实施步骤

- [x] 迁移 + 实体/DTO 字段 + SchemaInitializer 注册
- [x] Service `updateFloorReferences`（鉴权→楼层归属→校验→JSON 序列化落库）与 Controller 端点
- [x] 前端 `useWorkspaceApi.updateFloorReferences`、总览页编辑器接入（单调序号防乱序、请求前固定项目/楼层 ID）、工具栏弹层、权限门控
- [x] 方案页最终报价展示（缺价口径、快照一致性判定）
- [x] Java 单测与 e2e 补充、验证记录
- [x] 更新进度表与架构文档

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-11-25 | 楼层参考图存 `text` 列（项目级为 JSONB），同一 `[{url,name}]` JSON 契约由服务层 Jackson 序列化 | 迁移 `20261124_office_floor_reference_images.sql`；读写异常按空列表降级 | 文档写明列类型差异，避免后续误改 |
| 2026-11-25 | 前端保存用按 `projectId/floorId` 的单调序号防乱序，请求前固定 ID | 5e5402c 修复响应乱序覆盖与切楼层串写 | e2e 场景 2 固化该回归 |
| 2026-11-25 | 最终报价仅在报价行与页面商品严格一致时展示；缺价行不计入合计但计入提示 | c15356b/128b78a；防止报价后改商品仍显示旧价 | e2e-proposal-quote 场景 5 固化 |
| 2026-11-25 | 既有 e2e-proposal-quote 报价行（P1/P2/P3）与页面商品（仅 P1）不一致，严格一致性判定下无法通过 | 按 productId 汇总比较，种类数不同即不一致 | 修正各场景页面商品集合后通过 |

## 验证记录

| 日期 | 版本/环境/样本 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-11-25 | 工作区 | `mvn -f backend/pom.xml test` | 通过：373 运行 / 0 失败 / 9 跳过；含新增 `OfficeProjectServiceTest` 楼层参考图 9 用例（替换/清空/未知楼层/跨项目/null 请求/非法地址/超 10 张/名称超长/EDIT 鉴权，定向运行 31 项通过）与 `OfficeProjectControllerTest` PUT 端点用例（定向 3 项通过） | 9 项跳过为 Postgres 门控测试（无 RSDP_TEST_POSTGRES_URL） |
| 2026-11-25 | 工作区 | `pnpm build`（frontend） | 通过（13.2s） | - |
| 2026-11-25 | 工作区 | `pnpm e2e:proposal-quote` | 通过：完整报价、部分缺价、全部缺价、无确认报价、快照与页面商品不一致五场景 | UI 为模拟 API |
| 2026-11-25 | 工作区 | `pnpm e2e:floor-references`（新增脚本） | 通过：上传保存恢复、乱序防覆盖、失败提示、只读禁用、工具栏弹层五场景 | UI 为模拟 API |
| 2026-11-25 | 工作区 | `MOCK_API=1 pnpm e2e:smoke` | 通过 | UI 为模拟 API |
| 2026-11-25 | 工作区 | `pnpm e2e:workflows` | 通过 | UI 为模拟 API |
| 2026-11-25 | 工作区 | `git diff --check` | 通过（仅 LF/CRLF 提示） | - |

## 交付与下一步

- 后端：`20261124_office_floor_reference_images.sql` 迁移、`OfficeDesignFloor.referenceImages`、`PUT /api/v1/office/projects/{projectId}/floors/{floorId}/references`、`OfficeFloorDto.referenceImages`。
- 前端：`useWorkspaceApi.updateFloorReferences`、`ProjectReferenceImagesEditor` 可配置化（heading/listMode/disabled）、项目总览页楼层参考图编辑器（权限门控 + 乱序防护）、画布工具栏参考图弹层、方案页最终报价展示。
- 测试：`OfficeProjectServiceTest`/`OfficeProjectControllerTest` 新增用例；`e2e-floor-references.mjs`（注册 `pnpm e2e:floor-references`）；`e2e-proposal-quote.mjs` 修正数据并新增一致性场景。
- 下一步：真实后端 + 数据库的楼层参考图读写联调；认证开启下只读成员的真实链路验收；报价与正式方案快照的失效联动仍属 QUOTE-001 待办。
