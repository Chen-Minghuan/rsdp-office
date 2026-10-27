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
- `POST /api/v1/office/projects`：原子创建项目与楼层。
- `POST /api/v1/office/projects/{projectId}/floors`：添加楼层。
- `PATCH /api/v1/office/projects/{projectId}`：修改项目名称，保留项目 ID。
- `PATCH /api/v1/office/projects/{projectId}/floors/{floorId}`：修改楼层名称，保留楼层 ID 与设计数据关联。
- `PATCH /api/v1/office/projects/{projectId}/requirements`：更新客户需求并递增修订。

## 验收与验证记录

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
