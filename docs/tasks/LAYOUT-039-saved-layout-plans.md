# LAYOUT-039 真实 A/B/C 排版方案

状态：**已按用户要求移除（2026-11-02）**——用户明确「流程太重，只需要展示 3 个方案、最终选一个、再基于它调整」，A/B/C 保存/确认/切换/归档整层前后端代码已在工作区删除（未提交，删除后不可恢复）；轻流程由 LAYOUT-034 Top3 候选 + LAYOUT-036 差异化/评分承担。本卡保留作实现记录与决策依据，如下游（效果图/预算）确需方案快照契约可据此重启。
更新日期：2026-11-02
负责人：开发 C（Claude）
基线/交付版本：工作区（本地 3a15e3c 之后，未提交）
关联：[LAYOUT-034](LAYOUT-034-candidate-scheme-comparison.md)（临时候选，不落库）、[LAYOUT-013](LAYOUT-013-layout-panel-ux.md)（三段式工作台）、[FLOW-001](FLOW-001-spatial-confirmation.md)（空间复核确认与几何白名单快照）、[RECOMMEND-002](RECOMMEND-002-module-size-matching.md)（productSelection/office_cart 写入链路）、[PROJECT-004](PROJECT-004-project-members.md)（requireFloorAccess 成员访问）

## 目标与验收

触发：设计师在单空间设计中管理多套排版方案。
结果：A/B/C 三个可独立保存、切换、编辑、恢复、确认的真实排版方案，按 楼层/空间/方案位 持久化到服务端；临时候选（preview）不落库，确认版（confirmed）由服务端校验签发。
范围：方案存储 API 与服务、office.ts 布局保存/激活部分、方案管理 UI；不改动 B 的 layoutRegions/建筑标注实现（只消费其纯函数）、不改动 D 的商品可售性逻辑、不重复实现 LAYOUT-040 已有复制/图层功能。

- [ ] 验收 1：一空间保存 A=20 件、B=25 件、C=18 件家具，三套不同位置及 SKU，反复切换/刷新/重新登录不串方案
- [ ] 验收 2：修改 B，A 和 C 的 planRevision 与快照保持不变；仅 B 确认失效；归档 B 不影响已被正式预算快照引用的历史（预算侧为 E 的后续任务，本卡冻结其引用契约）
- [ ] 验收 3：1F 切到 2F 再回来方案归属正确；其他房间/项目不被覆写
- [ ] 验收 4：两个浏览器同时编辑 B，旧 basePlanRevision 被 409 拒绝；重试不重复生成方案或覆盖选择（clientChangeId 幂等）
- [ ] 验收 5：切换激活方案后，家具坐标/旋转/图例/商品引用与 office_cart 数量明细一致；过期（STALE）方案不当作当前确认版
- [ ] 验收 6：Top 3 候选、组合布局、复制、手工排版、禁放/通道、底图透明度均不退化

## 现状与依赖

### 已有能力（不重复实现）

- LAYOUT-034 临时候选：`planLayoutProposal` 返回 Top 3 `candidates`，面板签名跟随切换预览，**不落库**；`applyLayoutProposal(zoneId, request, sourceKey, candidateIndex)` 应用时重算并按索引取用，家具 ID 全部重新生成（office.ts:1570）。
- LAYOUT-013 三段式工作台：`DesignWorkflowSidebar`（自动布局/产品选型/款式推荐）；`ProductLayoutEditor` 已有 `duplicateFurniture`（新 ID、+200mm 偏移、硬校验、一次撤销、清除 productSelection 与确认）。
- 单空间状态：`office.ts` 单一 `objects` 数组承载空间/家具；`productSelection` 写在家具对象上（服务端 module-products 写入）；`layoutPreferences`（含通道）在 ZoneObject 上；排版确认为家具级 `layoutConfirmed` 布尔。
- 持久化：`schedulePersist` 防抖 800ms 全量 PUT saveCanvas，本地修订 `canvasRevision` 递增；保存响应仅合并服务端 `reviewConfirmation` 凭据。
- 楼层隔离：`activateFloor` 整层重置并重新加载，localStorage 按 `rsdp-office-v01:<floorId>` 隔离。
- 底图透明度：设计模式 `CanvasBoard` 的 `underlayOpacity` defineModel（默认 0.5，滑杆 testid `design-underlay-opacity`），会话内有效不落库。

### 空缺（本卡实现）

- 无方案实体：前端类型/状态、画布 DTO、后端表均无 plan/scheme 概念。
- 画布保存无 clientChangeId 幂等；前端无 409 冲突处理（错误仅进 syncError）。
- 方案切换需要服务端原子替换目标空间家具 + 清单，并防止旧防抖保存覆盖新方案。

## C→E 契约（2026-10-31 冻结，同步 architecture.md）

### SavedLayoutPlan DTO

```jsonc
{
  "planId": "OLPL-XXXXXXXX",        // 服务端生成稳定业务 ID，不复用 candidateIndex
  "projectId": "OPRJ-...",           // 真实 office_design_project ID，服务端从 floorId 解析，客户端不可指定
  "floorId": "OFLR-...",             // office_design_floor ID（既有画布 API 的 projectId 路径参数实际承载它）
  "spaceId": "空间稳定 ID",
  "schemeCode": "A",                 // A | B | C，(floorId, spaceId, schemeCode) 在未归档方案中唯一
  "planRevision": 3,                 // 方案内容修订：快照内容变化才递增；PUT 乐观锁基准
  "sourceCanvasRevision": 41,        // 快照来源画布修订，仅表示输入依赖，与 planRevision 不混用
  "sourceGeometryFingerprint": "sha256",  // 空间几何白名单快照哈希（复用 FLOW-001 字段集，名称/备注/颜色/图层显隐不参与）
  "status": "DRAFT",                 // DRAFT | CONFIRMED | STALE | ARCHIVED；STALE 为读取时推导（持久 CONFIRMED + 指纹不一致）
  "active": true,                    // 是否该空间当前工作方案；每空间至多一个
  "snapshot": {
    "furniture": ["FurnitureObject（含 productSelection，类型同 types/office.ts）"],
    "layoutPreferences": "LayoutPreferences | null",
    "cartItems": [{ "lineId": "module-...", "productId": "RSPU-...", "zoneId": "...", "zoneName": "...", "qty": 1 }]
  },
  "summary": { "furnitureCount": 20, "seatCount": 16, "selectedProductCount": 3 },
  "confirmedAt": "ISO 时间，仅 CONFIRMED", "confirmedByUserId": "认证关闭时为空",
  "createdAt": "...", "updatedAt": "..."
}
```

### 接口（语义固定，路径按 /floors/{floorId} 新约定）

| 方法与路径 | 语义 |
| --- | --- |
| `GET /api/v1/office/floors/{floorId}/spaces/{spaceId}/layout-plans` | 该空间未归档方案列表（含快照与推导状态） |
| `GET /api/v1/office/layout-plans/{planId}` | 单个方案完整快照与版本 |
| `POST /api/v1/office/floors/{floorId}/spaces/{spaceId}/layout-plans` | 初始化（从服务端最新已保存画布取快照）或复制（`sourcePlanId`，须同空间）；`clientChangeId` 幂等 |
| `PUT /api/v1/office/layout-plans/{planId}` | `basePlanRevision` 乐观锁保存当前画布该空间状态，冲突返回 **HTTP 409** + 当前修订；内容不变保持原状态，内容变化使 CONFIRMED 回落 DRAFT |
| `POST /api/v1/office/layout-plans/{planId}/activate` | 原子恢复该空间家具与 `office_cart` 明细为新画布修订并置为当前方案；`baseCanvasRevision` 不符返回 409；其他空间不受影响 |
| `POST /api/v1/office/layout-plans/{planId}/confirm` | 按真实布局校验（与 saveCanvas 同一 OfficeLayoutValidationPolicy 红色硬阻塞口径）后确认该版本；几何指纹已变化拒绝确认 |
| `DELETE /api/v1/office/layout-plans/{planId}` | 逻辑归档（ARCHIVED），释放方案位；已被正式预算引用的方案不可物理删除（E 落地预算表后加外键约束，本卡先以逻辑归档履约） |

### 错误码

- 400：参数/状态错误（方案位已占用、几何已变化不能确认、幂等重放内容不一致、方案不存在或已归档）
- 403：非项目成员（复用 `OfficeProjectAccessService.requireFloorAccess`，认证关闭放行）
- 409：版本冲突（`basePlanRevision`/`baseCanvasRevision` 过期），响应 message 携带当前修订号

### 对 E 的承诺

- `planId + planRevision` 引用一个不可变内容点：C 侧任何内容修改都会递增 planRevision；E 的预算快照必须自行复制方案快照，C 不保证旧修订内容可再取回。
- 修改方案 B 不改 A/C 的 planRevision 与快照；确认失效只影响被修改的方案。
- E 只读接口：方案列表/单个方案（含快照、状态、来源画布修订、几何指纹、确认时间）；过期条件 = 状态 STALE 或 DRAFT。

## 决策与过程

| 日期 | 发现/决定 | 原因或证据 | 对下一步的影响 |
| --- | --- | --- | --- |
| 2026-10-31 | 立卡，完成文档与前端现状摸底 | 必读文档与 office.ts/useOfficeApi/三面板源码 | 契约对齐现有 FurnitureObject/productSelection，禁止 unknown 进正式 DTO |
| 2026-10-31 | 后端落地：`office_layout_plan` 表（方案位/幂等/每空间唯一 active 三个部分唯一索引）+ 7 个接口 + `OfficeLayoutPlanService`；快照只由服务端从最新画布 + `office_cart` 构建 | 快照若接受客户端提交则无法保证与持久画布一致 | E 可按冻结契约引用 `planId + planRevision` |
| 2026-10-31 | 从当前画布创建的方案即当前工作方案（服务端原子 clearActive + 置位）；复制（sourcePlanId）不抢占 active | 创建时画布与快照一致，不给用户额外一次「切换」 | 前端 createLayoutPlan 本地同步清除同空间其他 active 标记（e2e 发现缺失后补齐） |
| 2026-10-31 | office API 首次引入真实 HTTP 409：`VersionConflictException`（携带 currentRevision）+ `@ResponseStatus(CONFLICT)`；前端 `useOfficeApi.request` 捕获 `$fetch` HTTP 错误并透传服务端 message 与 status | 此前业务错误一律 HTTP 200 + code，前端无法区分版本冲突 | 后续其他实体的乐观锁可复用同一异常与处理 |
| 2026-10-31 | 方案条放在画布列顶部横条（设计模式），不放侧边栏 | 初版放在 `DesignWorkflowSidebar` 头部，900px 视口下挤压 DesignLayoutSidebar 滚动区，e2e-layout-planning「自动推荐」点击被面板头部拦截 | 侧边栏纵向预算紧张，新增固定区块优先横向条 |
| 2026-10-31 | 刷新恢复路径（store.init 从 localStorage/服务端画布恢复设计模式）补载方案列表 | 该路径不经过 enterDesign，方案列表不落 localStorage（服务端权威），刷新后方案条曾为空 | 凡不进 saveLocal 的服务端状态都要检查 init 恢复路径 |

## 验证记录

| 日期 | 版本/环境/样本 | 命令或操作 | 结果及证据 | 未覆盖项 |
| --- | --- | --- | --- | --- |
| 2026-10-31 | 工作区 | 文档与前端源码摸底 | 完成 | 后端摸底进行中 |
| 2026-10-31 | 工作区，真实 PostgreSQL（`RSDP_TEST_DATABASE_URL=jdbc:postgresql://localhost:5434/office`） | `mvn -f backend/pom.xml test` | 249 测试 0 失败 0 跳过，BUILD SUCCESS；含 `OfficeLayoutPlanServiceTest` 12/12（创建快照与 active、幂等重放、方案位守卫、跨空间复制拒绝、409、确认失效/保持、切换原子恢复、STALE 推导、归档释放方案位）与 `OfficeLayoutPlanDatabaseTest` 1/1（迁移两遍幂等、ON CONFLICT 重放、方案位/active 唯一约束、jsonb 回读，独立 test_ schema 建删） | 未跑真实多浏览器并发（409 语义由单测+e2e mock 覆盖） |
| 2026-10-31 | 工作区 | `cd frontend && pnpm build` | 通过（4.2s） | — |
| 2026-10-31 | 工作区 | `pnpm test:layout-planning && pnpm test:mixed-layout` | 全部 PASS | — |
| 2026-10-31 | 工作区，全部 API 模拟 | `pnpm e2e:layout-plans`（新增） | 通过：空方案位提示→保存 A（创建即当前方案）→改动出现「未保存改动」→保存消除→保存 B 并切换→画布家具随方案恢复→未保存改动三选对话框（取消/放弃）→确认 B（家具同步已确认）→修改后确认失效回草稿→只读对比面板（指标+硬校验结论）→过期 basePlanRevision 收到 HTTP 409 与当前修订文案→归档释放方案位→刷新后列表从服务端重载归属不变 | mock 仅作 UI 回归，不代替真实持久化验收 |
| 2026-10-31 | 工作区，全部 API 模拟 | `pnpm e2e:layout-planning && pnpm e2e:module-recommendations && pnpm e2e:space-obstacles` | 通过（Top 3 候选、组合布局、模块推荐、禁放/通道、底图透明度均不退化）；e2e-space-obstacles:71-75 覆盖 `design-underlay-opacity` | — |
| 2026-10-31 | 工作区，预览服务 3001 + mock 拦截 | `pnpm e2e:workflows` | 通过 | — |
| 2026-10-31 | 工作区 | LAYOUT-040 核验：`duplicateFurniture` 读码 + e2e-layout-plans 尾部 store 级回归（新 ID、+200 偏移、清除 productSelection/layoutConfirmed、同空间确认失效、方案快照不受影响）；底图透明度见上 | 两项既有能力均达标，未发现缺口，未做修改 | — |
| 2026-10-31 | 工作区 | `git diff --check` | 通过 | — |

## 交付与下一步

变更文件（工作区，未提交——任务约束禁止 commit/push）：

- 后端：`db/schema.sql` + `db/migrations/20261031_office_layout_plan.sql`（`SchemaInitializer` 登记）、`office/entity/OfficeLayoutPlan.java`、`office/mapper/OfficeLayoutPlanMapper.java`、`office/service/OfficeLayoutPlanService.java`、`office/controller/OfficeLayoutPlanController.java`、`office/dto/LayoutPlanDto.java`、`office/dto/LayoutPlanRequests.java`、`common/VersionConflictException.java`、`common/GlobalExceptionHandler.java`（409 处理）
- 后端测试：`OfficeLayoutPlanServiceTest`（12 例）、`OfficeLayoutPlanDatabaseTest`（真实 PostgreSQL）
- 前端：`app/types/layoutPlans.ts`、`app/components/OfficeCanvas/LayoutPlanBar.vue`（画布列顶部横条）、`app/pages/canvas.vue`（挂载横条）、`app/stores/office.ts`（方案状态与动作、init 恢复补载、创建本地同步 active）、`app/composables/useOfficeApi.ts`（7 个接口 + HTTP 错误透传）、`frontend/scripts/e2e-layout-plans.mjs` + `package.json` 脚本

行为变化：设计模式画布上方出现「排版方案」横条，A/B/C 方案位可保存当前排版、切换（服务端原子恢复家具与清单）、确认（服务端硬校验签发）、归档、复制到空位、只读对比；从画布新建的方案即当前方案；确认后修改并保存自动回落草稿；空间几何变化使已确认方案显示「已过期」并禁止确认；过期版本保存/切换收到 HTTP 409。

验收结论：实现与上述验证完成；六项验收的语义均由单测/真实数据库测试/mock e2e 覆盖，但**尚未在真实部署环境做多方案、多楼层、多浏览器并发的综合验收**，验收框保持未勾选，待真实环境回归后勾选。

剩余限制：STALE 推导依赖 FLOW-001 几何指纹白名单；方案快照不含效果图引用（E 后续任务）；归档为逻辑归档，物理删除待预算外键落地后再评估。
