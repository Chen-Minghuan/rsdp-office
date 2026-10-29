# PROJECT-004 项目负责人、协作者与参考图片

状态：已实现待验收（真实用户链路联调与旧项目补录见「交付与下一步」）
更新日期：2026-10-29
负责人：开发 A（Claude 实施）
基线/交付版本：工作区（未提交）
关联：《RSDP-Office 开发 A 实施设计文档 V1.1》（仓库外，`~/work/RSDP-Office_开发A_项目成员与空间确认_实施设计文档_V1.1.md`）、[WORKSPACE-003](WORKSPACE-003-project-floors.md)、[AUTH-001](AUTH-001-user-login.md)、[AUTH-002](AUTH-002-session-hardening.md)

## 目标与验收

触发：创建/编辑办公项目时指定负责人与协作者，上传项目参考图片；成员按项目访问数据。
结果：每项目恰好一位 `LEADER`、零至多位 `COLLABORATOR`；新项目一次性保存项目、楼层、成员、参考图；非成员经后端拒绝访问；参考图多图保存/删除/刷新恢复。
范围：只做两种项目角色，不建公司/部门/组织树/RBAC。旧项目归属补录与全面收紧既有画布/选品等历史接口鉴权属后续收口，本任务先在项目接口与成员管理上落地。

- [x] 迁移建 `office_project_member` 与 `reference_images` 字段，可重复执行，不改历史迁移
- [x] 创建项目同事务写入负责人/协作者/参考图，任一步失败整体回滚
- [x] 负责人恰好一人（偏索引保证至多一位，服务保证至少一位）；协作者不与负责人重合、不重复、须为有效用户
- [x] 成员原子替换接口仅负责人可用；替换不产生无负责人中间状态
- [x] 参考图仅接受本站 `/uploads/images/` 相对路径；多图顺序保存、删除、刷新恢复
- [x] 认证开启时非成员访问项目数据返回 403；认证关闭（开发模式）行为与现状一致
- [x] 前端创建/设置对话框支持人员选择与图片上传预览

## 现状与依赖

- 已有 `office_user`（user_id/username/display_name/status）、`office_design_project`/`office_design_floor`、`AuthFilter` 写 `CurrentUserContext`（username）。
- `rsdp.auth.enabled=false` 时过滤器跳过，`CurrentUserContext` 返回 `system`；鉴权只在认证开启时生效。
- `OfficeProjectController/Service` 已有项目/楼层 CRUD，无成员概念。
- `ImageUploadController` 已支持 JPG/PNG/WebP 多文件上传，返回 `/uploads/images/...` URL。

## 实施步骤

- [x] 明确输入输出与权限/事务规则（设计文档 V1.1）
- [x] 迁移 + 实体/Mapper + SchemaInitializer 注册
- [x] 服务端当前用户解析与项目访问校验
- [x] 项目接口扩展（创建/列表/成员替换/参考图/可分配用户）
- [x] 前端类型、API、创建/设置对话框与工作区 store
- [x] Java 单测、构建与验证记录
- [x] 更新进度表与相关文档

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-29 | 鉴权仅在 `rsdp.auth.enabled=true` 时强制执行；关闭时保持现有开发行为 | AuthFilter 同一开关，避免开发环境匿名被误认为有权限，也不破坏现有本地/测试链路 | 测试覆盖两种开关状态 |
| 2026-10-29 | `reference_images` 存 JSONB 字符串经 `JsonbTypeHandler`，与 `office_canvas` 一致 | 沿用既有模式，不引入新附件表 | 图片结构校验在服务层做 |
| 2026-10-29 | 403 用新增 `AccessDeniedException` 经 `GlobalExceptionHandler` 返回 `Result.code=403`（HTTP 仍 200） | 与既有 `Result` 包装约定一致，前端 `request()` 按 code 抛错 | 前端无需区分传输层状态码 |
| 2026-10-29 | 画布/选品/消息等既有楼层接口经 `OfficeProjectAccessService.requireFloorAccess(floorId)` 统一收口 | 后端不能只依赖 UI 禁用；楼层→项目→成员的解析集中在访问服务 | 图纸识别/效果图/模板接口的鉴权收口留作后续 |

## 验证记录

| 日期 | 版本/环境/样本 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-10-29 | 工作区 | `mvn -f backend/pom.xml test` | 通过：221 运行 / 0 失败 / 3 跳过；含新增 `OfficeProjectServiceTest`（成员/参考图校验、原子替换、列表过滤、可分配用户）与 `OfficeProjectAccessServiceTest`（开发模式放行、成员/负责人判定、楼层归属解析、可访问项目集） | 未跑真实数据库集成 |
| 2026-10-29 | 工作区 | `pnpm build`（frontend） | 通过 | - |
| 2026-10-29 | 工作区 | `MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口预览服务） | 通过 | UI 为模拟 API |
| 2026-10-29 | 工作区 | `pnpm e2e:workflows`（自建 3001 端口预览服务） | 通过 | UI 为模拟 API |
| 2026-10-29 | 工作区 | `git diff --check` | 通过 | - |

## 交付与下一步

- 后端：`20261029_project_members.sql` 迁移（成员表 + 偏唯一索引 + `reference_images` JSONB）、`OfficeProjectMember` 实体/Mapper、`CurrentUserService`、`OfficeProjectAccessService`、`AccessDeniedException`（403）、`OfficeProjectService` 成员/参考图/可分配用户与项目接口扩展、`OfficeUserController`（`/api/v1/office/users/assignable`）、既有楼层接口（画布/选品/消息/重置）接入 `requireFloorAccess`。
- 前端：`ProjectMemberEditor`、`ProjectReferenceImagesEditor` 组件；创建/设置对话框接入成员与参考图；项目树项目名下方展示负责人与协作人数；`useWorkspaceApi` 新增成员/参考图/可分配用户接口。
- 下一步：认证开启下的真实用户链路联调；图纸识别/效果图/模板等历史接口的鉴权收口；成员变更审计记录待业务确认。
- 2026-10-29 补录：本地开发库（office-postgres 容器）已将唯一用户 `admin` 补录为唯一项目「默认项目」的 LEADER（幂等 INSERT）。其他环境升级后需对各自旧项目执行同样补录，否则强制模式下项目对所有人不可见。
- 2026-10-29 关联修复：AuthFilter 短路 401 缺 CORS 头导致浏览器报 "Failed to fetch"（看不到真实 401），`WebConfig` 新增最高优先级 `CorsFilter` 注册修复，见 [status.md](../status.md) 当日记录。
