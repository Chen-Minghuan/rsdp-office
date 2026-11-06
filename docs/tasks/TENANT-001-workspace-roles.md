# TENANT-001 工作区与经销商角色权限体系

状态：已实现待验收（真实迁移执行与认证开启链路联调见「交付与下一步」）
更新日期：2026-11-06
负责人：开发 A（Claude 实施）
基线/交付版本：工作区（未提交）
关联：[AUTH-003](AUTH-003-user-admin.md)、[PROJECT-004](PROJECT-004-project-members.md)、需求确认书 V2.1 M06 节（仓库外，仅背景）

## 目标与验收

触发：在「全局 ADMIN/USER + 项目 LEADER/COLLABORATOR」之上增加工作区（经销商）隔离层。
结果：每个经销商一个独立工作区；系统管理员创建/停用/重置经销商主号；经销商主号在工作区内开户（普通账号）并可指定本工作区项目的负责人；负责人仅从本工作区账号池增删协作者；项目唯一归属一个工作区，非本工作区成员访问项目数据后端返回 403。
范围：一期不做字段级权限（出厂价/毛利/终端客户分权）与竞品屏蔽，列为二期。

- [x] 迁移建 `office_workspace`、`office_audit_log`，`office_user` 增 `workspace_id/source`，`office_design_project` 增 `workspace_id`；可重复执行，不改历史迁移
- [x] `office_user.role` 增加 `DEALER`（经销商主号），与全局 ADMIN/USER 及项目内角色正交；`office_workspace.owner_user_id` 唯一，一个主号恰好一个工作区
- [x] 系统管理员接口：`GET/POST /api/v1/office/admin/workspaces`（创建工作区 + 主号，单事务）；逐接口 `requireAdmin`
- [x] 经销商主号接口：`GET /api/v1/office/workspace/me`、`GET/POST /api/v1/office/workspace/users`；只能操作自己拥有的工作区，只能创建 USER 账号；`source` 仅允许 `DEALER_STAFF`/`FACTORY_SALES`，一期不参与鉴权
- [x] 项目访问规则：项目有工作区时，访问者须为该项目成员且同属该工作区，或为该工作区主号（主号可读并管理成员，用于指定负责人）；跨工作区成员关系在写入时拒绝、读取时双重校验；旧项目（workspace_id 为空）保持 PROJECT-004 成员判定
- [x] 创建项目（认证开启）必须落在当前账号的工作区，无工作区账号（含系统管理员）不能创建项目；成员替换接口校验成员来自项目工作区
- [x] 可分配账号搜索（`/api/v1/office/users/assignable`）在认证开启时只返回当前账号同工作区的在用用户
- [x] 成员被移出项目即不可访问（鉴权按请求实时查库，含已登录会话）；成员替换、开户、建工作区写入 `office_audit_log`
- [x] AUTH-003 能力修订后不回退：系统管理员唯一，管理接口只能创建设计师（USER）账号、只能对设计师与经销商主号停用/重置/删除；停用即失效会话、负责人账号与经销商主号拒绝删除
- [x] 前端：系统管理员 `/users` 页增加经销商工作区创建与列表；经销商主号 `/workspace/users` 页开户与账号列表；侧栏与顶栏角色徽标同步

## 现状与依赖

- 已有：`office_user(role=ADMIN/USER)`、`office_project_member(LEADER/COLLABORATOR)`、`OfficeProjectAccessService`（楼层→项目→成员）、`OfficeUserAdminService`（开户/停用/重置/删除）、前端 `/users` 管理页。
- 缺口：无工作区实体；任何在用账号可被拉进任何项目；项目列表/成员接口不区分渠道。
- 确认书 M06 指出完整多租户（租户/工作区/成员授权/公共商品主档/竞品策略/审计）远超一期；本任务只落地「工作区隔离 + 经销商主号开户/指定负责人 + 成员日志」最小闭环。

## 实施步骤

- [x] 明确角色矩阵与决策（见下表）
- [x] 迁移 + 实体/Mapper + SchemaInitializer 注册
- [x] 鉴权中间层扩展（工作区归属校验）与项目接口收紧
- [x] 经销商主号/工作区接口与审计日志
- [x] 前端管理入口与角色徽标
- [x] Java 单测、构建与验证记录
- [x] 更新进度表与架构文档

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-11-06 | 经销商主号用 `office_user.role='DEALER'` 第三档表示，工作区归属由 `office_workspace.owner_user_id`（唯一）承载 | AUTH-003 的两档是当时最小方案；TENANT-001 明确要求新角色，单列角色比「USER + 反查工作区」在鉴权点更直接，也不影响 ADMIN/USER 存量语义 | 顶栏/用户页角色文案同步；全局角色仍不建组织树/RBAC |
| 2026-11-06 | 系统管理员唯一：`/admin/users` 只能创建设计师（USER）账号（经销商主号随工作区创建），且停用/重置/删除只能作用于 USER/DEALER，任何 ADMIN 目标一律拒绝（含自己、含开发模式） | 用户明确要求："系统管理员应该只有一个，只能创建经销商账号和设计师，也只对设计师和经销商进行操作"；目标角色规则替代原「不能停自己/至少保留一位管理员」兜底（唯一管理员不可被操作后原兜底不可达） | AUTH-003 的"角色仅 USER/ADMIN"与"最后管理员保护"被本规则取代；界面 USER 称「设计师」 |
| 2026-11-06 | 普通用户未加入任何项目时对工作区数据「不可见」（而非只读） | 现状即如此（列表按成员过滤、非成员 403），只读需要新增大量只读路径，非最小改动；按任务授权选择并在本文记录 | 二期若需「工作区内浏览」另立任务 |
| 2026-11-06 | 厂家业务员一期不建独立角色：由经销商主号开户为 USER，`office_user.source='FACTORY_SALES'`（经销商员工为 `DEALER_STAFF`），拉进项目即协作者，权限与协作者一致 | 任务明确要求；M06 中「按厂家归属限制可见产品、屏蔽报价/毛利/终端客户信息」属字段级权限 | `source` 只落数据不参与鉴权；字段级权限、竞品屏蔽列二期 |
| 2026-11-06 | 项目唯一归属一个工作区：创建时取当前账号 `workspace_id`；无工作区账号（含系统管理员、历史账号）认证开启时不能创建项目 | 规则要求；管理员不管项目成员，不代经销商建项目 | 旧项目 `workspace_id` 为空，保持 PROJECT-004 成员判定，补录留待后续（同 PROJECT-004 旧项目成员补录策略） |
| 2026-11-06 | 经销商主号对本工作区全部项目有读与成员管理权（用于指定负责人），不自动成为项目成员 | 主号要「指定项目负责人」必须能调成员接口；不写成员表避免与「恰好一位 LEADER」约束纠缠 | 项目列表对主号返回本工作区全部项目 |
| 2026-11-06 | 成员移出即时生效靠按请求实时查库（既有实现即如此，含已登录会话）；新增 `office_audit_log` 记录成员替换（前后集合）、开户、建工作区 | 规则要求「移出即不可访问 + 操作留日志」；会话无需删除是因为鉴权不缓存成员关系 | 审计查询接口与字段级审计列二期 |
| 2026-11-06 | 经销商主号被停用：工作区、项目与区内账号全部保留，成员照常工作，主号会话失效（沿用 AUTH-003）；删除经销商主号被拒绝，须先由系统管理员处理工作区归属（一期不提供转让/解散接口） | 停用可逆、影响面小；删除会破坏「工作区有主号」结构，与负责人账号拒绝删除同一保守策略 | 工作区转让/解散接口列后续任务 |
| 2026-11-06 | 开发模式（认证关闭）：项目/成员接口全部放行且工作区字段不强制（与 PROJECT-004 同一约定）；经销商主号接口因无法解析「我的工作区」返回明确业务错误 | 主号接口语义绑定登录身份，放行会猜错工作区 | 前端入口在开发模式可见但页面显示后端错误；专项联调需开启认证 |
| 2026-11-06 | 不开新「租户」实体，工作区即经销商渠道隔离单位；不建公共商品主档/竞品策略 | M06 建议的完整多租户远超一期；任务明确一期范围 | 确认书所列字段级/竞品/多工作区等确认项全部列二期 |

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/office/admin/workspaces` | 工作区列表（主号、账号数、项目数）；仅系统管理员 |
| POST | `/api/v1/office/admin/workspaces` | 创建工作区 + 经销商主号 `{name, ownerUsername, ownerDisplayName, ownerPassword}`，单事务 |
| GET | `/api/v1/office/workspace/me` | 当前经销商主号的工作区 |
| GET | `/api/v1/office/workspace/users` | 本工作区账号列表（不含哈希） |
| POST | `/api/v1/office/workspace/users` | 开户 `{username, displayName, password, source?}`；仅 USER 角色，落在本工作区 |

既有接口收紧：`PUT /api/v1/office/projects/{projectId}/members` 校验成员同属项目工作区且允许工作区主号调用；`GET /api/v1/office/users/assignable` 认证开启时限定本工作区；`POST /api/v1/office/projects` 认证开启时写入当前账号工作区。

## 验证记录

| 日期 | 版本/环境/样本 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-11-06 | 工作区 | `mvn -f backend/pom.xml test` | 通过：255 运行 / 0 失败 / 3 跳过；新增 `OfficeWorkspaceServiceTest`（建工作区+主号原子性、入参校验、非管理员 403、开户来源校验与列表隔离、非主号/开发模式拒绝）；扩 `OfficeProjectAccessServiceTest`（跨工作区成员拦截、主号隐含访问、历史项目兼容、可访问集过滤）、`OfficeProjectServiceTest`（无工作区拒建、成员工作区校验、assignable 隔离、成员替换审计）、`OfficeUserAdminServiceTest`（主号删除拒绝、停用会话失效） | 单测为 mock 仓储；未跑真实数据库迁移与集成 |
| 2026-11-06 | 工作区 | `pnpm build`（frontend） | 通过 | - |
| 2026-11-06 | 工作区 | `MOCK_API=1 pnpm e2e:smoke`（自建 3001 生产预览） | 通过 | UI 为模拟 API，未覆盖新页面 |
| 2026-11-06 | 工作区 | `pnpm e2e:workflows`（自建 3001 生产预览） | 通过 | 同上 |
| 2026-11-06 | 工作区 | `git diff --check` | 通过 | - |

## 交付与下一步

- 后端：迁移 `20261106_workspace_roles.sql`（SchemaInitializer 已注册）；`OfficeWorkspace`/`OfficeAuditLog` 实体与 Mapper；`OfficeAuditService`（同事务审计）；`OfficeWorkspaceService` + `OfficeWorkspaceAdminController`（`/admin/workspaces`）+ `OfficeWorkspaceController`（`/workspace/me|users`）；`OfficeProjectAccessService` 工作区归属判定；`OfficeProjectService` 创建归属/成员工作区校验/assignable 收缩/替换审计；`OfficeUserAdminService` 主号删除拒绝。
- 前端：`/users` 页经销商工作区创建与列表、DEALER 角色徽标；`/workspace/users` 主号开户页；`AppSidebar` 经销商工作区入口；`AppTopbar` 徽标；`ProjectMemberEditor` 账号池提示；`useOfficeApi` 5 个工作区接口。
- 下一步：认证开启（`rsdp.auth.enabled=true`）下真实链路联调——管理员建工作区 → 主号开户 → 主号/负责人配成员 → 跨工作区 403 与成员移出即时生效实测；真实数据库执行迁移并补录旧项目/旧账号工作区归属；`/users` 与 `/workspace/users` 页专项 e2e；审计日志查询入口。

二期明确不做（依据确认书 M06 与任务说明）：字段级权限（出厂价/毛利/终端客户分权与脱敏）、竞品屏蔽、统一 `visibilityPolicy` 中间层重构、审计查询界面、工作区转让/解散、一个用户属多工作区、旧项目工作区批量补录界面。
