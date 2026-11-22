# WORKSPACE-003 项目创建与多楼层工作区

更新日期：2026-10-26。

## 目标

在 WORKSPACE-001 移除全局项目资料之后，按用户最新需求重新引入面向实际设计流程的项目与楼层：

- 创建项目时填写项目名称、客户需求和楼层规划，不上传图纸。
- 支持单层、多层、连续楼层和后续添加楼层。
- 左侧项目/楼层树可折叠，项目和楼层可切换；每层独立上传、识别、编辑、排版和选品。
- 客户需求作为全流程备注，可修改并持久保存；不自动改变几何、布局、推荐或方案。

## 数据与兼容策略

新增 `office_design_project` 和 `office_design_floor`，不复用会被历史清理迁移删除的 `office_project` 名称。项目 ID 使用 `OPRJ-`，楼层 ID 使用 `OFLR-`。

楼层的 `floorId` 作为既有 Office API 路径中的 `projectId` 工作区隔离键，复用画布、识别、模板、选品和预览存储，不批量增加业务表字段。默认 `OFFICE-PROJECT-001` 映射到默认项目/楼层，保留旧草稿和旧业务记录入口。画布保存和图纸上传核验楼层存在，拒绝未知隔离键。

客户需求带 `requirementsRevision`，修改只产生复核提醒，不伪造下游自动更新。

## 前端实现

- 新增 workspace 类型、API 与 Pinia store；当前楼层同步 URL 和本地选择。
- 新增创建项目、客户需求和共享项目/楼层树组件，接入普通工作台和空间核对左栏；项目/楼层弹窗保存成功后自动关闭；树内项目名和楼层名支持双击原地编辑，Enter/失焦保存、Esc 取消；客户需求卡片支持双击填写多行备注，Ctrl+Enter/失焦保存、Esc 取消。
- 2026-10-27：当前楼层的空间列表嵌入楼层树下，可独立展开/折叠并持久保存折叠状态；点击空间进入单空间设计，替代原设计页顶部下拉选择器。空间状态与家具数量继续由当前楼层 Office 草稿派生。顶部步骤栏左侧连续展示项目/楼层、空间设计、当前图纸和当前空间模板，模板可点击查看；侧栏与排版面板不再重复展示这些信息。
- Office 本地草稿、识别任务和 IndexedDB 文件按楼层隔离；切层先保存旧层，清理运行时状态，再加载目标层。
- IndexedDB 新记录键为 `floorId + drawingId/name`，不再全局清空；旧文件存储保留只读兼容。
- 上传、识别、模板及画布 API 动态读取当前楼层；异步响应核对任务和楼层归属。
- 项目/需求编辑使用 PATCH；`WebConfig` 明确允许 PATCH，避免浏览器预检以 403 Invalid CORS request 阻断保存。旧 Java 进程需重启后生效。

## 接口

- `GET /api/v1/office/projects`：项目及楼层树。
- `POST /api/v1/office/projects`：经销商主号在所属工作区原子创建项目、楼层和成员。
- `POST /api/v1/office/projects/{projectId}/floors`：添加楼层。
- `PATCH /api/v1/office/projects/{projectId}`：修改项目名称，保留项目 ID。
- `PATCH /api/v1/office/projects/{projectId}/floors/{floorId}`：修改楼层名称，保留楼层 ID 与设计数据关联。
- `PATCH /api/v1/office/projects/{projectId}/requirements`：更新客户需求并递增修订。

## 验收与验证记录

### 2026-11-22 项目步骤栏并入顶栏

项目与楼层、当前图纸、空间模板和四个步骤共用工作区顶栏。桌面空间总览和布局选品的画布与右侧面板从顶栏下方直接开始，窄窗口下步骤可换行。切层期间继续隐藏旧楼层步骤和上下文。

前端构建、`git diff --check`、工作流、项目楼层和模块推荐模拟回归通过。工作流实测 1440px 和 1280px 下顶栏单行，空间总览画布区域与右侧面板均紧接顶栏，390px 时四个入口都在顶栏可视宽度内。已查看模拟空间总览截图。

### 2026-11-22 切换项目的设计入口与清单

空间总览、布局选品和预算清单的入口均绑定当前 `floorId`。用户主动切换楼层时只沿用当前适用的阶段，旧空间、图纸分析和预算演示参数随之清除。跨层直达链接保留目标层参数。切换期间显示楼层加载状态，完成后再挂载页面和空间树。加载草稿时楼层归属以当前选择为准，并重新请求所选楼层的画布和清单。预算页使用项目和楼层的实际名称。

前端构建和 `git diff --check` 通过。`pnpm e2e:project-floors` 验证两个项目的空间、设计入口与预算件数，加载延迟时隐藏上一项目数据，切换后使用目标楼层接口返回的清单。`pnpm e2e:workflows`、`pnpm e2e:workspace-isolation`、`pnpm e2e:drawing-entry`、`pnpm e2e:module-recommendations` 通过，均为模拟接口。冒烟仍在既有的 `desk-straight` 选择步骤超时，真实接口未复测。

### 2026-11-22 项目工作台入口合并

项目创建和图纸识别统一放在「项目工作台」。顶部首项和侧栏同名入口均打开项目列表，经销商主号可创建项目。创建后直接进入首层，已有项目从列表进入，楼层图纸使用同一路由的 `floorId` 参数。上传界面可返回项目列表，旧地址 `/` 继续进入上传界面。顶部步骤栏依次为「项目工作台 → 空间总览 → 布局选品 → 预算清单」。侧栏「空间设计」按当前楼层恢复设计页面。

模拟浏览器验证项目创建后进入首层、项目列表往返、切层图纸隔离、后台识别继续查询及账号隔离。识别、画布和权限仍使用现有接口。

- `pnpm build` 与 `git diff --check` 通过。
- `pnpm e2e:project-floors`、`pnpm e2e:workspace-isolation`、`pnpm e2e:drawing-entry`、`pnpm e2e:recognition-progress`、`pnpm e2e:workflows` 通过，均拦截 API。
- `MOCK_API=1 pnpm e2e:smoke` 在选择 `desk-straight` 时超时。以 `d435b28` 对照复现同一超时。真实接口和部署子路径未复测。

### 2026-11-18 从商品资源返回楼层

侧栏点击楼层先加载该楼层，再根据空间状态跳转。空楼层进入上传图纸页，从商品库等资源页面进入已有空间的楼层时打开空间总览。重复点击当前楼层也执行页面跳转。跨楼层进入上传页时，URL 仅保留目标楼层 ID。

前端构建与 `pnpm e2e:project-floors`、`pnpm e2e:workspace-isolation`、`pnpm e2e:workflows`、`MOCK_API=1 pnpm e2e:smoke` 通过。楼层模拟 UI 覆盖商品库点击当前空楼层、点击其他空楼层、从导入记录返回当前空楼层、清理旧空间和分析参数、返回已有空间的楼层及原图记录保留。本地 3001 前端已重新构建并重启。使用真实 `lead` 会话验证商品库点击空楼层及重复点击当前空楼层均回到上传页，业务写请求拦截，仅核验读取和导航。

### 已执行

- `mvn -f backend/pom.xml test`：161 项，158 通过、3 项既有可选 PostgreSQL 集成测试跳过，BUILD SUCCESS。
- `mvn -f backend/pom.xml -Dtest=WebConfigTest,OfficeProjectServiceTest,OfficeProjectControllerTest test`：PATCH CORS、项目/楼层编辑 8 项通过；实际旧 8082 预检复现为 403，未擅自重启用户服务。
- `pnpm build`：通过。
- `pnpm e2e:project-floors`：通过；覆盖顶部创建按钮、创建两层项目、项目/楼层改名且原图关联不变、分层上传与刷新恢复、两层 IndexedDB 原图并存、折叠、需求修改、添加楼层和保存隔离，使用模拟 API。
- `pnpm e2e:drawing-entry`：通过；保持单图更换、识别进入、迟到响应与空稿恢复行为。
- `pnpm e2e:space-review`：通过；项目资料断言更新为允许新项目树、继续禁止旧资料详情接口，其余几何/空间回归保持。
- `MOCK_API=1 pnpm e2e:smoke`：通过。
- `pnpm e2e:workflows`：通过；同步当前每页 20 条、商品详情链接、五步项目导航及导入预览/重新校验协议的旧测试断言，使用模拟 API。

### 验收边界

本任务验证项目、楼层和备注的管理与数据隔离，不扩大真实图纸识别质量、商品推荐或效果图验收结论。真实数据库迁移和真实上传集成仍需在明确启动的新后端上单独验证；UI mock 不作为真实持久化验收。
