# INTEG-001 商品主档统一：rsdp 权威主档与工厂可见性体系

状态：一期已落地（rsdp 侧可见性体系，2026-11-14）；office 代理/投影与 product-feed 待实施
更新日期：2026-11-14
负责人：开发 A（Claude 实施）
基线/交付版本：rsdp `3b68ca3`（feat(catalog) 工厂可见性体系一期）；rsdp-office `fe9bf16`（录入端打通）
关联：[TENANT-001](TENANT-001-workspace-roles.md)、需求确认书 V2.1 M06/M07 节（仓库外）、rsdp 主仓库（`~/work/rsdp`，GitHub Chen-Minghuan/rsdp）

## 目标与验收

触发：rsdp-office 录入的商品需要进入 rsdp 主档；两个系统商品域同源但已分化，需统一为一套权威主档。
结果：商品主档唯一归 rsdp（含归属标记与工厂可见性体系）；rsdp-office 不再写商品数据，录入页代理 rsdp 管线，商品表降级为只读投影。
范围：跨两个仓库。本文档为总契约，放 rsdp-office；rsdp 侧实施记录按其自身规范登记。

- [x] rsdp `rspu_master` 增加 `owner_type`（PLATFORM/FACTORY）与归属工厂，迁移走 Flyway（V28；列名按 rsdp 惯例定为 `owner_factory_code`，替代草案的 `owner_factory_id`）
- [x] 工厂可见性规则：自家产品无条件可见；公共库开关（工厂级）；整批放行（工厂×工厂）；黑名单（工厂×工厂，优先级最高）
- [~] 「有效可见集」在 rsdp 后端统一收口：`CatalogVisibilityService` 已收口，列表（own/full）与详情深链已接入；导出/AI 匹配等其余读取入口待接入
- [ ] rsdp 供给接口按调用方身份过滤输出（office 经销商 = 全量公共池；product-feed 未建。过渡态：office 经服务账号实时读全库，已验证）
- [ ] office 录入页代理 rsdp 导入管线（预览→确认透传），后端不写本地商品表；rsdp 不可用时明确失败，不本地暂存
- [ ] office 商品表降级为只读投影（增量游标同步 + 录入后即时拉取），上下架状态随同步联动
- [ ] 存量商品为测试数据，不迁移，随投影重建整体废弃（owner 归属已在 V28 按首个报价厂回填，仅服务于过渡期权限判定）

## 现状与依赖

- rsdp：13 个数据库域、Flyway 严格水位、`excel-ai-import` AI 导入管线、factory 域（`factory_master`）、pgvector 向量（`product_image_embedding`）、字典域含 `dict_unresolved_value` 治理通道。
- rsdp-office：简化版 `rspu_master/rspu_variant`（同源抽取）、自有 Excel 导入管线、TENANT-001 工作区/经销商体系。
- 尺寸字段天然兼容：两边 `rspu_variant.dimensions` 均为 `{"w","d","h","unit":"mm"}` JSONB。
- 已排除的路线：微服务合并（见架构分析结论）、共库共表（约束/时区/迁移机制实质冲突）、office 写本地再同步（双写不一致且绕过 rsdp 规则）。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-11-14 | 商品主档唯一归 rsdp，office 只做只读投影；录入统一走 rsdp 管线，office 录入页为代理薄入口；禁止双写 | 用户拍板；rsdp 有完整 AI/去重/编码/人工修正体系，office 是设计子系统 | office 删除商品写路径 |
| 2026-11-14 | 产品无私有/公共之分：工厂上传的产品一律入公共库；`owner_factory_id` 只做归属标记 | 用户明确「工厂上传的所有产品都会在公共库里」；取代早先「私有默认+平台转公共」草案 | 不需要转公共流程与按钮 |
| 2026-11-14 | 工厂可见性 = 自家产品（无条件）+ 公共库开关（ON 看全量）+ 整批放行（工厂×工厂）− 黑名单（最高优先） | 用户逐条确认：先做整批放行；对其他厂默认可见；黑名单封某工厂全部数据 | 规则表两表（放行/黑名单）+ 工厂级开关字段 |
| 2026-11-14 | 上下架权限谁录入谁负责：工厂管自己的，rsdp 管平台录入的；后端强制 | 用户明确 | 可见性服务同时校验操作权 |
| 2026-11-14 | 分类与向量统一以 rsdp 为准：六维标签 + 字典 + pgvector；字典维度不够走字典扩展，office 不另建标签体系 | 用户明确；rsdp 已有 `dict_unresolved_value` 治理通道 | office 投影只带标签快照 |
| 2026-11-14 | 经销商（office 工作区）一期看全量公共库，不受工厂开关/黑名单影响；经销商级黑白名单列二期 | 用户未单列，按最小方案默认；`owner_factory_id` 随投影落 office 数据，二期不返工 | 投影携带归属字段 |
| 2026-11-14 | 存量商品为测试数据，不做迁移 | 用户明确；消灭新旧 ID 映射与历史方案断链问题 | 投影重建即整体替换 |
| 2026-11-14 | 字段级权限（出厂价/毛利/终端客户）、竞品屏蔽、报价体系（M07）、账号打通/SSO 列二期 | M06/M07 确认项未定；归属字段先行落数据 | 二期任务单独立项 |
| 2026-11-14 | 方案 C 定稿：office 工厂身份一期不拆分，放行/黑名单只在 rsdp 侧生效（工厂管理员各自配置），office 经服务账号继续看全库 | 用户拍板；经销商一期看全量公共池本就是定稿口径 | rsdp 侧先行落地，office 零改动 |
| 2026-11-14 | 全库视图删除旧"工厂能力覆盖自动隐藏"口径，开关开即看全库 | 用户定稿"打开公共库就是看所有"；能力隐藏与该口径直接冲突 | `applyFullViewFilter` 删除，可见性收口接管 |
| 2026-11-14 | 多厂账号取并集近似：放行/自家报价并集、黑名单并集（最严格）、厂级开关任一开即具备全库资格 | 服务账号与典型工厂管理员均单厂；精确按厂拆视图的复杂度高、收益低 | 文档化口径，后续有真实多厂诉求再细化 |
| 2026-11-14 | DELETE 放行/黑名单必须显式归 factory:update，不能落 URL 通配 | 实测 DELETE `/factories/**` 匹配平台专属 factory:delete 导致工厂管理员 403 | SecurityConfig 显式规则前置 |

## 接口契约（草案，实施时定稿）

**rsdp 侧**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/PATCH | `/api/v1/factories/{factoryId}/catalog-config` | 工厂公共库开关读写 |
| GET/POST/DELETE | `/api/v1/factories/{factoryId}/catalog-grants` | 整批放行（目标工厂）管理 |
| GET/POST/DELETE | `/api/v1/factories/{factoryId}/catalog-blocks` | 黑名单（目标工厂）管理 |
| GET | `/api/v1/product-feed?since=...` | 增量供给（游标 + 软删标志），按调用方身份过滤；服务间 token 认证 |
| POST | `/api/v1/products/excel-ai-import/**` | 既有导入管线，供 office 代理透传（预览→确认两段） |

**office 侧**

| 改动 | 说明 |
| --- | --- |
| `/api/v1/products/import*` | 改为代理转发 rsdp 导入管线并透传结果，删除本地写入 |
| 投影同步 | 定时增量拉取 product-feed + 录入确认后即时拉取；失败告警与重放 |
| `/api/v1/products`（读） | 数据源切到本地投影表（表结构瘦身：ID/名称/毫米尺寸/价格/图片/在售状态/归属/标签快照） |

## 字段映射（要点）

| rsdp 权威 | office 投影 | 说明 |
| --- | --- | --- |
| `rspu_id` / `rspu_code` | 同值引用 | 方案快照引用的稳定 ID 即 rsdp 主档 ID |
| `rspu_variant.dimensions`（mm JSONB） | 直读 | 两边同构，无需换算 |
| `owner_type` / `owner_factory_id` | 原样落库 | 二期可见性/黑白名单数据基础 |
| `status`（active/discontinued/draft） | 在售标志 | office 下架语义 = 非 active；下架不入新推荐、旧方案保留引用 |
| 六维标签 / 图片（MinIO URL） | 快照 | 展示与筛选用，office 不维护 |

## 实施步骤

**rsdp 仓库**
- [x] Flyway 迁移：`owner_type/owner_factory_code` + 工厂开关字段 + `factory_catalog_grant` / `factory_catalog_block` 两表（V28）
- [x] 可见性服务：有效可见集统一收口并接入列表/详情；上下架操作权按 owner 校验（导出/AI 匹配待接入）
- [x] 工厂可见性配置接口（开关/放行/黑名单，FactoryCatalogController）
- [ ] product-feed 供给接口（含身份过滤与服务间认证）
- [x] rsdp 侧任务文档与验证（docs/05-status/当前进度.md，2026-11-14 条）

**rsdp-office 仓库**
- [ ] 录入接口代理化（透传预览/确认，删除本地写路径）
- [ ] 投影表瘦身迁移 + 增量同步任务 + 即时拉取
- [ ] 商品读接口切换投影；下架联动推荐失效
- [ ] 前端录入页对接透传结果（交互不变）

## 验证记录

| 日期 | 版本/环境/样本 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-11-14 | rsdp `3b68ca3` / 本地 dev 库 / 演示数据 167 产品 | `mvn test`（rsdp server） | 2261 运行 / 0 失败 / 0 错误 / 0 跳过；新增 CatalogVisibilityServiceTest 11 例、DataScopeHelperTest 归属 7 例 | 导出/AI 匹配读取入口 |
| 2026-11-14 | 双路径结构校验 | `scripts/check_flyway_schema_sync.sh` | schema 重放与 Flyway V1–V28 零差异 | - |
| 2026-11-14 | 真实链路（8081，临时 fa/fb 账号，验证后已清理） | curl 三工厂正反用例 | 开关ON见全量 167 → 拉黑 TEST 剩 12 → 厂级开关关回退 own=0 → TEST 放行 F002 后 own=155 → 再拉黑优先=0 → 详情深链 404 → 越权管他厂配置 400 → 工厂改平台产品 400 / 改本厂产品 200 → office 商品库回归 167 | 新品随放行自动可见（规则天然覆盖，未单独建新品复验） |
| 2026-11-14 | office 录入端（8082→8081） | office 手工录入/同码重交 | 录入落主库归属 A034；同码重交不重复建档、价格经 PUT 更新；office 库无商品行 | Excel 导入链路端到端 |

验收用例（实施时执行）：三工厂正反用例——自家产品无条件可见；公共库开关 ON 见全量/OFF 仅自家+放行；整批放行覆盖该厂全部产品含新品；黑名单剔除且优先于放行与开关；office 录入只写 rsdp 一份（office 库无新增商品行）；下架同步后 office 新推荐不出现、旧方案引用保留。

## 交付与下一步

- 当前交付：本契约文档。
- 下一步：rsdp 侧迁移与可见性服务先行（office 代理依赖供给接口）；随后 office 代理与投影。
- 二期：字段级权限与竞品屏蔽（M06）、报价体系（M07）、经销商级黑白名单、账号打通/SSO、厂家业务员按归属过滤。
