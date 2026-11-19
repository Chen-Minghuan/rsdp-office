# QUOTE-001 项目报价（试算与确认落库）

状态：已实现待验收（临时 PostgreSQL 上的迁移、确认、回显和导出已验证，真实经销商主号的端到端流程待验收）

## 背景与范围

经销商主号需要按项目查看并确认报价，口径为《项目报价规则》：

- 成交价 = 出厂价 × 系数，或 出厂价 ÷ (1 − 毛利率)
- 入口在左侧导航「经销商工作区」组内、`工作区账号` 之上，名为「商品报价」
- 只有经销商主号可见，其他角色接口一律拒绝

已实现：只读试算、页内手填（数量/成交单价）、可增删的多项费用、底部固定合计、确认前 Excel 风格预览与 `.xlsx` 下载、确认报价落库、最近一次确认回显。
未实现：底线拦截（主号最低系数/最低毛利、项目底线）、部门合并报价、费用项模板、上游变更后的失效联动。

## 数据模型

新库从 `db/schema.sql` 创建报价表。存量库在后端启动时运行 [20261119 迁移](../../backend/src/main/resources/db/migrations/20261119_simplify_project_quotation.sql)。

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `office_quotation` | `quotation_id`、`project_id`、`project_name`、`workspace_id`、`revision`、`status`、`pricing_mode`、`pricing_value`、`fees_json`、`total_amount`、`created_by`、`created_at`、`superseded_at` | 项目内版本唯一，每个项目最多一条 `CONFIRMED`，新确认把旧版置 `SUPERSEDED` |
| `office_quotation_line` | `quotation_id`、`line_no`、`floor_id/name`、`zone_id/name`、`product_id`、`rspu_id`、`product_name`、`variant`、`image_url`、`qty`、`factory_price`、`unit_price`、`issue` | `(quotation_id, line_no)` 为主键，`quotation_id` 外键级联删除 |

`project_name` 保存确认时的名称，项目改名后历史报价单仍显示当时名称。`fees_json` 只保存有顺序的 `[{name, amount}]` 费用列表，已确认总额保存在主表，商品快照保存在明细表。行小计、商品合计、行数和缺价行数从明细计算。响应中的 `serviceFee` 和 `logisticsFee` 从费用列表按同名费用求和，接口结构沿用原版。

迁移优先复制旧 `snapshot_json.fees`，包括显式空数组。没有费用数组的早期记录由旧服务费和物流搬运安装费两列恢复，随后删除重复字段。旧明细的排序编号直接作为行号，历史成交价和确认总额保持原值。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/office/projects/{projectId}/quotation/preview?mode=&value=` | 试算，不落库 |
| POST | `/api/v1/office/projects/{projectId}/quotation/confirm` | 确认报价：服务端重算并落库 |
| POST | `/api/v1/office/projects/{projectId}/quotation/export` | 导出当前编辑稿：与确认共用重算，返回 `.xlsx`，不落库或生成版本 |
| GET | `/api/v1/office/projects/{projectId}/quotation/latest` | 最近一次确认（含已失效版本），无记录返回空 |

## 规则

- **权限**：`OfficeProjectAccessService.requireWorkspaceOwner` —— 认证开启时要求 `role=DEALER` 且为项目所属工作区主号。匿名 401、管理员 403。
- **服务端重算**：`商品合计 = Σ(数量 × 成交单价)`、`合计 = 商品合计 + Σ(全部费用金额)`，不采用前端提交的合计值；导出与确认共用同一准备逻辑。
- **校验**：明细非空、≤2000 行；数量与金额不得为负；毛利率 `0 < m < 1`；系数 `0 < c ≤ 100`；明细楼层必须属于该项目。费用最多 50 条，名称去首尾空格后必填且不超过 100 字，空名称的零金额行忽略，金额按 `HALF_UP` 保留两位小数。
- **价格固化**：`factory_price`、`unit_price`、商品名/规格/图片、楼层与空间名写入明细行，后续商品调价不影响历史报价单。
- **缺价行**：商品不可用或缺出厂价时写入 `issue`，`unit_price` 和响应的小计为空，该行不计入合计。
- **版本与审计**：确认事务锁定项目行后分配版本，旧版替代、新版及明细写入、`QUOTATION_CONFIRMED` 审计一并提交。
- **页面**：底部固定条汇总商品小计 + 全部费用；费用行可命名、添加和删除，默认保留服务费与物流搬运安装费。「确认报价」先冻结当前草稿并打开居中 Excel 风格预览，右上角下载 `.xlsx`，右下角依次为确认、取消；下载与确认使用同一冻结载荷。

## 数据来源（试算口径）

- **楼层**取自 `office_design_floor`；**空间与家具**取自该楼层最新 `office_canvas.objects_json`（`office_canvas.project_id` 实际承载楼层 ID）。
- **数量**优先 `office_cart`（同空间有清单行时以清单为准并合并同类数量），空间无清单行时按画布家具件数；未绑定商品的排版单元不计价。
- **出厂价**按 `rspuId` 调主项目商品详情取变体 `factoryPrice`（同一变体多条报价取最低），进程内缓存 120 秒。
- 金额统一 `BigDecimal`，`HALF_UP` 保留 2 位小数。

## 验证

| 项 | 命令/方式 | 结果 |
| --- | --- | --- |
| Java 回归 | `mvn -f backend/pom.xml test` | 285 运行 / 0 失败 / 0 错误 / 3 跳过（含报价 7 个用例） |
| 前端构建 | `pnpm build`（frontend） | 通过，11.4s |
| 本轮 Java 回归 | `mvn -f backend/pom.xml test` | 288 运行 / 0 失败 / 0 错误 / 3 跳过（报价服务 9 例、工作簿 1 例） |
| 本轮前端构建 | `pnpm build`（frontend，经 Corepack） | 通过 |
| 本轮报价 UI | `pnpm e2e:quotes`（本机 Chrome，模拟 API） | 通过：多项费用增删与合计、居中预览、冻结载荷、下载、确认、取消、失败保留及 390px 窄屏；截图位于 `/tmp/rsdp-quotation/` |
| 本轮工作流 | `pnpm e2e:workflows`（本机 Chrome，模拟 API） | 通过 |
| 建表 | 临时实例 `8083` 启动后 `\d office_quotation` | 表与索引创建成功 |
| 确认落库 | `POST /quotation/confirm`（1 行 × 2，系数 1.25，费用 100+50） | 出厂价 194 → 单价 242.50 → 小计 485.00，合计 635.00，`revision=1` |
| 版本取代 | 再次确认 | `revision=2`，旧版 `SUPERSEDED`，仅一条 `CONFIRMED` |
| 回显 | `GET /quotation/latest` | 返回第 2 版与 1 条明细 |
| 审计 | `office_audit_log` | 2 条 `QUOTATION_CONFIRMED` |
| 权限 | 匿名 / 管理员会话 | 401 / `code=403 只有经销商主号可以查看项目报价` |

上表是 PR 原始验证记录，测试报价单与对应审计记录已清理。

2026-11-19 存储简化验证：Java 293 项、0 失败、3 跳过。前端构建、`e2e:quotes`、`e2e:workflows`、`MOCK_API=1 e2e:smoke` 通过，UI 使用模拟 API。临时 PostgreSQL 16 验证新建库和旧格式升级，旧固定费用、有序自定义费用、空费用列表及缺价明细都可回显。真实 Office 后端连接临时库和模拟商品主档，验证 Excel 响应、导出不落库、首次并发确认得到第 1 和第 2 版、最近报价回显。真实主号会话及连接真实商品主档的业务验收待执行，上游画布或清单变化后的 `STALE` 标记尚未实现。

同步远程 `main`（`3a01ca1`）后重跑 Java 297 项、0 失败、3 跳过。前端构建、报价专项、工作流和冒烟模拟 UI 回归均通过。

## 未覆盖与下一步

- 底线拦截：主号最低系数/最低毛利、项目底线、改价与折扣留痕。
- 费用项：按楼层分摊、电梯与搬运楼层备注、税率。
- 部门合并报价与真实导出链路验收（现有 CSV 导出仍是方案清单口径）。
- 变更联动：空间/布局/商品变化后把有效报价置为 `STALE` 并提示重算。
