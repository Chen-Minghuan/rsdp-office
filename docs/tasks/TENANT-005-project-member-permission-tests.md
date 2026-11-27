# TENANT-005 项目成员权限写入与校验测试

更新日期：2026-11-26

关联 [TENANT-004 Office 角色、主号名额与授权](TENANT-004-office-role-model.md)、[项目报价](QUOTE-001-project-quotation.md)

## 目标

核实项目设置弹窗（`ProjectSettingsDialog.vue`）权限矩阵中七种项目权限（查看 READ、编辑 EDIT、报价查看 QUOTE_READ、毛利查看 MARGIN_READ、方案导出 EXPORT_PROJECT、报价导出 EXPORT_QUOTE、成员管理 MANAGE_MEMBERS）：

1. 勾选结果真实写入数据库；
2. 每个权限在对应功能处有真实检查；
3. 每个权限与对应功能有测试覆盖。

## 核实结论（代码审查，本任务开始前）

**写入链路完整**：勾选 → `workspace.updateMembers` → `PUT /api/v1/office/projects/{id}/members`（`permissions` 为 `userId → 权限数组` 映射）→ `OfficeProjectService.replaceMembers` 经 `OfficeProjectPermission.encode()` 编码为位掩码，先删后插写入 `office_project_member.permission_flags`；项目详情/列表 DTO 回读权限返回前端。

**七个权限全部有真实后端校验**：

| 权限 | 后端校验点 | 性质 |
| --- | --- | --- |
| READ | `OfficeProjectAccessService.requireMember` / `requireFloorAccess` / `accessibleProjectIds` | 拒绝访问 |
| EDIT | 项目/楼层/参考图/画布保存等写端点（`OfficeProjectService`、`OfficeService` 等） | 拒绝访问 |
| QUOTE_READ | `ProjectQuotationService` 已确认报价读取（latest / latest-record） | 拒绝访问 |
| MARGIN_READ | `ProjectQuotationService.latest` | 软校验：不拒绝请求，把 `pricingMode/pricingValue/factoryPrice/missingPriceLines` 置 null 返回 |
| EXPORT_PROJECT | `OfficeController` `POST .../proposal/export` | 拒绝访问 |
| EXPORT_QUOTE | `ProjectQuotationService` `latest/export` | 拒绝访问 |
| MANAGE_MEMBERS | `requireLeader` → `replaceMembers` | 拒绝访问 |

前端检查点基于 `projectPermissionsFor` 做入口门控；MARGIN_READ 前端无独立门控，报价页对后端置 null 的毛利字段有自然兜底（列随 `pricingMode == null` 隐藏、单元格 `?? '—'` 兜底），不构成泄露，本次不新增前端隐藏逻辑。

**发现的测试缺口**（本任务补齐）：

- `replaceMembers` 的权限位写入/保留逻辑无测试；
- `OfficeProjectPermission.encode()` 两条业务规则（其他权限须先勾查看；毛利/报价导出须先勾报价查看）无单测；
- EXPORT_PROJECT 导出端点无测试；
- 「只有工作区主号可以授权或更换负责人」规则无测试；
- 前端 e2e 中 QUOTE_READ / MARGIN_READ / EXPORT_QUOTE / MANAGE_MEMBERS 四权限零覆盖。

## 变更内容（仅新增测试，不改生产代码）

### 后端（`mvn -f backend/pom.xml test`）

- `OfficeProjectServiceTest` 追加 5 用例：权限位掩码真实写入（负责人 1|2|32=35、协作者 1|4|64=69）并经 DTO 回读；`permissions` 缺省时保留既有授权（非主号仅调成员集合可放行）；授权对象必须是本项目成员；非法权限组合（缺 READ、缺 QUOTE_READ）拒绝且不触碰成员表；非主号授权或更换负责人拒绝、仅调协作者放行。
- 新建 `OfficeProjectPermissionTest` 5 用例：encode 空值为 0、缺前提权限抛业务异常、null 元素拒绝、七项编码 127 与 `assigned` 往返、`granted` 要求 READ 位。
- `OfficeWorkspaceControllerTest` 追加 2 用例：`proposal/export` 无 EXPORT_PROJECT 时 `code=403` 且不读购物车；有权限时输出 BOM CSV 且校验的是 EXPORT_PROJECT 位。
- `OfficeProjectAccessServiceTest` 追加 1 用例：权限位矩阵——仅授 READ+QUOTE_READ 时其余五位逐一拒绝，补授 MARGIN_READ/EXPORT_QUOTE 后放行。
- `OfficeProjectControllerTest` 追加 1 用例：PUT members 路由把 JSON 权限数组反序列化为枚举 Map 透传给 service。
- 新建 `OfficeProjectMemberPermissionsPostgresTest`（opt-in，需 `RSDP_TEST_POSTGRES_URL`）：迁移回填存量成员（负责人 51、协作者 19）且幂等、新成员默认 0、check 约束拒绝 128、insert flags=69 → 读回 → `assigned` 解码全链路。

### 前端（`frontend/scripts/e2e-project-permissions.mjs`，`pnpm e2e:project-permissions`）

自起构建产物 + 模拟 API，五场景：

1. 主号勾选「毛利查看」级联勾上「报价查看」「项目查看」，保存后 PUT body 含 `READ/QUOTE_READ/MARGIN_READ/EXPORT_QUOTE` 四项且负责人/协作者集合正确；刷新后重开弹窗勾选态从项目数据回读。
2. 取消「项目查看」级联清空该成员其余六项，PUT 中该成员为 `[]`；无任何改动时保存不发 PUT（`grantsChanged` 短路）。
3. 只读协作者（仅 READ）：项目设置入口、添加楼层、双击改名、用户面板「商品报价」入口全部关闭；`/workspace/quotes` 显示无权限提示；方案页「导出方案清单 CSV」「确认方案」「返回继续设计」不可见，「查看空间总览」可用。
4. QUOTE_READ 无 EXPORT_QUOTE：报价入口与已确认报价可见、导出按钮隐藏；`pricingMode` 为 null 时「出厂价」「商品毛利」列不展示（后端 MARGIN_READ 软校验契约的前端落点）；补授 EXPORT_QUOTE 后导出按钮出现。
5. MANAGE_MEMBERS 无 EDIT：可打开设置弹窗（入口提示「管理项目成员」）但项目名称禁用；可添加协作者；权限矩阵表格不渲染；PUT body 不含 `permissions` 键（与后端「缺省保留授权 + 非主号不得授权」闭环）。

## 验证记录

- 后端定向测试（六个相关测试类）：69 项，0 失败；`OfficeProjectMemberPermissionsPostgresTest` 1 项因未配置 `RSDP_TEST_POSTGRES_URL` 跳过，不计为数据库验收。
- 后端全量 `mvn -f backend/pom.xml test`：480 项、0 失败、0 错误、13 跳过（跳过均为 Postgres 门控）。
- 反向验证：临时把 `OfficeProjectService.replaceMembers` 的 `encode` 调用替换为常数 0，`replaceMembersWritesEncodedPermissionFlags` 与 `replaceMembersRejectsInvalidPermissionCombinations` 如期失败，随后恢复生产代码并复跑通过。
- 前端构建 `pnpm build` 通过；`pnpm e2e:project-permissions` 五场景通过（API 全部模拟）。
- 回归：`pnpm e2e:workflows`、`MOCK_API=1 pnpm e2e:smoke`（预览服务 3001 端口）、`pnpm e2e:quotes` 通过。
- `pnpm e2e:floor-references` 场景 2（并发保存乱序响应）两次运行均在等待第二次 PUT 响应处超时失败；本任务未改动任何前端生产代码，该失败为当前分支既有问题，非本次变更引入，未深入排查。
- 真实数据库验证未执行（未配置 `RSDP_TEST_POSTGRES_URL`）；真实后端服务未重启，业务验收未做。

## 已知边界

- 报价试算/确认/草稿导出流程不看权限位，仅允许经销商主号（既有设计，QUOTE_READ/MARGIN_READ 只作用于「查看已确认报价」）。
- 购物车增删改等部分写接口的 EDIT 校验在 Service 内部，本次未逐路径补测试。

## 2026-11-27 合并与迁移回归补强

- 首次迁移后核对历史权限回填，再将两个旧成员分别改为仅查看和无权限。重跑迁移后核对两项人工授权仍保留，并用 PostgreSQL SQLSTATE 23514 确认无效权限位由 CHECK 约束拒绝。
- 在独立 PostgreSQL 16 中运行专项通过。仅从临时构建产物移除空值回填条件时，新增断言如期失败，旧负责人被回填为 51。恢复原 SQL 后复跑通过。
- 合并快照的 Java 全量 501 项，0 失败、0 错误、13 跳过。前端构建及权限、工作流、报价、冒烟模拟 UI 通过。楼层参考图模拟 UI 仍在场景 2 等待第二次 PUT 超时。
- 未运行生产迁移或真实业务联调。
