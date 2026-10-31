# 开发进度与下一步

2026-10-31 修复线上「登录后仍提示请先登录」：用户报 `https://rooom.vip/office/` 登录后侧栏红字「请先登录」。实测对比两个入口：根路径 `:8081` 匿名请求 `/api/v1/office/projects` 正常 401，而 `/office/` 子路径入口返回 HTTP 200 + `code:403`，且匿名可读全部商品数据。定位为外层 nginx 的 `X-Forwarded-Prefix: /office` 经后端 forwarded headers 处理后并入 `getRequestURI()` 与 `contextPath`，`AuthFilter.shouldNotFilter` 直接 `startsWith("/api/v1/")` 判断不匹配，导致过滤器在子路径入口下对 `/api/v1/**`、`/uploads/**` 整体跳过——登录态 401 拦截失效（匿名可访问 API），且 `CurrentUserContext` 从不设置，PROJECT-004 的 `currentUser()` 对已登录用户也一律抛「请先登录」（10-21 部署验证时 PROJECT-004 尚不存在，无接口读取上下文，问题隐形）。修复：`AuthFilter` 路径判断前先剥离 `contextPath`（根路径入口 contextPath 为空、行为不变），一处改动同时恢复登录态拦截与用户上下文；部署侧 `X-Forwarded-Prefix` 保留（后端生成资源 URL 依赖它）。新增 `AuthFilterTest` 3 例（前缀下过滤/放行、根路径行为不变、鉴权关闭跳过）。验证：`mvn -f backend/pom.xml test` 全量 236 运行/0 失败/0 错误/3 跳过（surefire 汇总口径，43 个测试类）；**修复仅完成代码与本地测试，需重新构建部署后端镜像后线上才生效**，真实登录链路复测留待部署后执行；代码未提交。

2026-10-30 合并 origin/main（5 个提交：扩充房间类型与视觉识别类型集、布局选品底图默认透明度 50%、组合预览应用前手动拖动、进入排版不再默认选中功能模块、椅子与沙发图例朝向约定修正）：快进合并无冲突（GEOM-016/018 提交 7cb22dd 此前已由并行会话推送到远端）；对侧「不默认选中功能模块」漏改 `e2e-single-space.mjs`，本侧按同一模式补齐——进入排版/刷新/切换空间后显式点选「办公工位」品类，切回 ROOM-L 后重新在产品选型目录选中模块（点卡片主体选中不跳转；`open` 是 DOM 属性需读 property 判断展开）。合并后验证：`pnpm build`、`pnpm e2e:layout-regions`/`e2e:space-obstacles`/`e2e:single-space`/`e2e:layout-planning`、`mvn -f backend/pom.xml test`（全量通过）、`git diff --check` 通过；4 个顶栏/工作区初始化文件仍为其他会话未提交改动，未混入。

2026-10-30 GEOM-016 家具布置区 + GEOM-018 设施/点位标注（开发 B）：ZoneObject 新增可选 `layoutRegions`（多布置区：零/一/多个，最多一个主区；矩形拖绘与逐点多边形绘制、改名/设主/启停/显隐、移动/顶点编辑、删主升主、每边长度与距墙只读量测）与 `facilities`（消防/电箱=占用型多边形、踢脚线=线型、电源/弱电=点位；全部提示型，不扣净面积、不阻断排版，操作净空仅接受人工标注数值）。有效可排区 = 启用区并集 ∩ 空间净轮廓（`allowedPlacementAreas`：null=无启用区回退全房间、[]=无可排交集、多块多边形绝不退化为包围盒），手动放置/`planLayoutProposal`/`planMixedLayout` 与服务端 `OfficeLayoutValidationPolicy` 同语义核验（畸形/越界区域服务端跳过不拒存，UI 标记「需人工修复」，不静默裁切）；几何变化只失效本空间排版确认，不触碰开发 A 的 reviewConfirmation（白名单未动）。坐标与障碍同约定：polygonMm/pointsMm 世界毫米 Y 向上。无数据库迁移（objects_json jsonb 透传），GEOM-016 任务卡按多布置区定稿重写、GEOM-018 任务卡本次新建。验证：`pnpm build`、纯函数 `test:layout-regions` 14 组 + `test:layout-facilities` 7 组、全量单测（space-boolean/layout-planning/mixed-layout/layout-references/layout-channels/furniture-snap/adaptive-layout/obstacle-window）、`mvn -f backend/pom.xml test` 233 运行/0 失败/3 跳过（含新增布置区服务端 6 例）、`pnpm e2e:layout-regions`（新，模拟 API）、回归 `pnpm e2e:space-obstacles`/`e2e:single-space`/`e2e:layout-planning`、`git diff --check` 通过；e2e:layout-planning 运行会在 frontend/ 下产生 `C:/Users/...` 截图目录（该脚本既有硬编码 Windows 临时路径），已清理。真实数据库保存回读与真实业务排版验收未执行，详见 [GEOM-016](tasks/GEOM-016-layout-boundary.md)、[GEOM-018](tasks/GEOM-018-spatial-facilities.md)；已提交 7cb22dd（当日随后被并行会话推送到 origin/main）。

2026-10-29 登录后工作区状态修复与顶栏真实角色：用户报「登录后点编辑看不到协作者、右上角显示设计师」，定位为 `workspace.init()` 在项目列表请求失败（后端重启中/旧 401）时落入兜底「默认办公项目」且 `initialized=true` 永久锁定，整会话不再加载真实项目（含成员）。修复：失败不再锁定 initialized，`app.vue` 在路由变化时自动重试，登录恢复后自愈换真数据；顶栏「R/设计师」硬编码改为真实用户——头像取显示名首字符、显示 displayName 与角色徽标（系统管理员/协作者，开发模式仍为设计师），随路由刷新会话。验证：`pnpm build`、`MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows`（自建 3001 端口）、`git diff --check` 通过；真实会话链路待用户刷新页面实测。

2026-10-29 合并 origin/main（4 个提交：组合布局三段式选品、单空间建筑标注与图纸对照、商品图片空列表兼容、座椅净空改提醒）：merge-tree 预检仅 `docs/status.md` 冲突，双方当日记录全部保留（本侧在前）；SpaceReview.vue/stores office.ts/architecture.md 自动合并。合并后状态验证：`mvn test` 228 运行/0 失败/3 跳过、`pnpm build`、`MOCK_API=1 pnpm e2e:smoke`、`git diff --check` 通过；`pnpm e2e:space-review` 在「手动空间 4」排版侧栏改名步骤（`getByRole('button', { name: '手动空间 4' })`）超时——与对侧会话在其记录中声明的既有未过项一致（LAYOUT-013 排版面板改版区域），非本次合并引入，留待对侧跟进。已提交本地两个提交（72a7430 代码、3c58c99 文档）与合并提交 7fab5c1，未推送。

2026-10-29 AUTH-003 系统管理员与用户管理（最小方案，按用户确认）：启用既有 `office_user.role` 字段（仅 ADMIN/USER 两档，不建组织树/RBAC）。新增 `OfficeUserAdminService`/`OfficeUserAdminController`（`/api/v1/office/admin/users` 列表/创建/停用启用/重置密码/删除），逐接口服务端校验 ADMIN（开发模式放行，同 PROJECT-004 约定）；创建校验用户名格式与唯一、显示名必填、初始密码 ≥6 位、角色仅 USER/ADMIN，只存 PBKDF2 哈希；停用不能停自己、至少保留一位在用管理员，停用与重置密码即删该用户全部会话。随后按用户要求补**删除账号**：硬删除，不能删自己、至少保留一位在用管理员、仍是项目负责人的账号拒绝删除（协作者关系与会话一并清除），前端行内两步确认。前端新增 `/users` 用户管理页（身份核验、创建表单、行内重置密码/删除）与侧栏「系统管理」入口（仅管理员可见，服务端仍独立校验）。本地开发库 `admin` 已提升为 ADMIN。项目负责人与全局角色无关，仍在项目内指定。验证：`mvn -f backend/pom.xml test` 228 运行/0 失败/3 跳过（含新增 7 项）、`pnpm build`、`MOCK_API=1 pnpm e2e:smoke`、`pnpm e2e:workflows`、`git diff --check` 通过；真实管理员链路联调与页面 e2e 断言留待后续，详见 [AUTH-003](tasks/AUTH-003-user-admin.md)；代码未提交。

2026-10-29 修复 401 响应缺 CORS 头：用户报「GET /api/v1/office/projects Failed to fetch」，定位为会话失效后 AuthFilter 短路返回 401，而 CORS 只配在 MVC 层（过滤器之后），浏览器拿不到 Access-Control-Allow-Origin 报成网络错误。`WebConfig` 新增最高优先级 `CorsFilter` 注册（与 MVC 映射同一套规则），过滤器短路响应也带 CORS 头。验证：`mvn -f backend/pom.xml test` 221 运行/0 失败/3 跳过、`curl` 复现确认 401 无 CORS 头（修复后行为待后端重启实测）；用户侧需重启后端并重新登录。

2026-10-29 PROJECT-004 项目成员与参考图（按《开发 A 实施设计文档 V1.1》实施）：新增 `office_project_member` 表（每项目恰好一位 LEADER、零至多位 COLLABORATOR，偏唯一索引兜底）与 `office_design_project.reference_images` JSONB；创建项目单事务写入项目/楼层/成员/参考图，成员集合校验（负责人必填且有效、不与协作者重合、协作者不重复）与参考图校验（≤10 张、仅本站 `/uploads/images/` 相对路径）。新增 `CurrentUserService`/`OfficeProjectAccessService`/`AccessDeniedException`（Result.code=403），鉴权仅在 `rsdp.auth.enabled=true` 时强制（关闭保持开发行为）；项目列表按成员过滤，成员原子替换仅负责人可用，既有楼层接口（画布/选品/消息/重置）经 `requireFloorAccess` 收口。前端新增成员编辑器（负责人选择+协作者搜索标签）与参考图多图上传组件，接入创建/设置对话框，项目树项目名下方展示负责人与协作人数。验证：`mvn -f backend/pom.xml test` 221 运行/0 失败/3 跳过（含新增服务与访问校验用例）、`pnpm build`、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`pnpm e2e:workflows`、`git diff --check` 通过；UI 均为模拟 API，真实用户链路联调、旧项目成员补录与图纸/效果图/模板接口鉴权收口留待后续，详见 [PROJECT-004](tasks/PROJECT-004-project-members.md)；代码未提交。

2026-10-29 FLOW-001 office_canvas 空间复核确认（SPACE_REVIEW，同 V1.1 文档）：新增 `POST /api/v1/office/floors/{floorId}/spaces/{spaceId}/review-confirm`——严格 `baseCanvasRevision` 校验、成员访问、空间几何有效（复用抽取的 `OfficeCanvasReviewPolicy.requireZoneValid`）后由服务端签发 `reviewConfirmation` 凭据（状态/确认修订/确认人/时间）并追加新画布修订；已确认且几何未变的重复确认幂等返回不增修订。`saveCanvas` 由服务端按确认时的几何白名单快照（轮廓/尺寸/障碍/单位等，名称备注颜色除外）重建凭据：客户端伪造一律剥离，几何变更转 STALE，改回与快照一致恢复 CONFIRMED（数字按数值等价比较）。前端核对列表展示「已确认复核/复核失效」徽标与确认按钮（仅毫米单位；按用户反馈按钮平时收起、悬浮该行才浮现，与删除 ✕ 同模式，「确认中…」保持可见），确认前先同步保存冲刷防抖队列，保存返回合并服务端凭据。该确认为可选项，不是进入排版门槛，不改变 FLOW-002 提示语义；普通 saveCanvas 严格版本锁留待后续评估。验证：`mvn test` 同上全量通过（含新增 `OfficeSpaceReviewServiceTest` 签发/严格 base/幂等/伪造剥离/STALE 与恢复用例）、`pnpm build`、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke`、`git diff --check` 通过；真实数据库集成未执行，详见 [FLOW-001](tasks/FLOW-001-spatial-confirmation.md)；代码未提交。

2026-10-29 会议长边座椅居中修正：横排坐标由桌面左缘基准改为桌面中心基准，解决上下各 4 把椅子整体偏左的问题；新增排中心对齐和桌面覆盖范围断言。真实会议室三种候选的上下排中心均与桌中心一致，实际组合预览已人工核查，未覆盖正式家具。`test:mixed-layout`、build、layout-planning 通过；UI 回归使用模拟 API，详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-29 组合布局门洞避让修复：移除对 `zoneLayoutExclusions` 输出的二次 Y 轴翻转，解决门洞被映射到另一侧后右上柜漏判的问题。新增玄关柜不得压占门洞净空断言；真实会议室只读复算三种候选均为桌居中、10 椅围桌，柜与门洞碰撞检查全部为 `false`，未覆盖正式家具。`test:mixed-layout`、build、layout-planning 通过；UI 回归使用模拟 API，详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-29 会议区居中与锚点评分优化：会议场景整体居中，玄关/吧台提供不同沿墙位置；排序先比较会议区中心偏移再比较紧凑度，避免主要家具挤在角落。真实会议室三种候选均为桌居中、柜沿不同墙边，算法、构建、layout-planning 和 `git diff --check` 通过；UI 均为模拟 API，详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-29 自动组合功能包改为真实场景结构：会议桌居中，椅子沿长边两侧及短边相向布置；沙发/会客形成交流区，不再主家具与配件逐行拼接。预览、应用和碰撞共用旋转与毫米几何。真实会议室只读复算生成 11 件会议组合，算法、构建、layout-planning 和 `git diff --check` 通过；UI 均为模拟 API，详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-29 自动组合布局改为先选需求、再生成交互：单模块选择不立即出图，多模块需点击「生成组合预览」，随后选择并应用候选。组合求解提供工位集中、会客优先、沿墙收纳三种整体策略，按目标缺口、跳过品类与紧凑度排序，最多 3 个候选；统一检查轮廓、保留家具、建筑约束和通道，预览与应用按索引共用几何。`test:mixed-layout`、build、layout-planning、module-selection、auto-layout 和 `git diff --check` 通过；UI 均为模拟 API，详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-29 离墙与座椅净空改为提醒不拦截：默认 560mm 和椅子活动区属于策略值，不再阻止确认或保存；推荐区域仅提示现场复核。前后端继续拦截实际越界、重叠、禁放区、通道、无效几何和目标人数不足。Java 全量 205 项（202 通过、3 跳过）、前端构建、layout-planning、auto-layout、module-recommendations 和 `git diff --check` 通过；已重启本地后端并实测真实款式推荐加载 52 个兼容规格，旧离墙错误不再出现，详见 [LAYOUT-035](tasks/LAYOUT-035-warning-levels-and-advice.md)。

2026-10-29 布局选品完整移植「图层与对照」面板：样式与空间总览一致，支持原始图纸、默认 75% 透明度、设计空间轮廓、实际尺寸标注和原始识别轮廓独立开关，不再使用简化透明度面板。`pnpm build`、space-obstacles（含全部图层开关）、layout-planning、single-space 和 `git diff --check` 通过；UI 均为模拟 API。

2026-10-29 门洞预览修正：布局选品绘制门洞净空时，拖动过程中即按最终几何显示四分之一圆弧，不再先显示矩形、松手后才变形。`pnpm build`、space-obstacles（含拖动中弧形断言）和 `git diff --check` 通过；UI 使用模拟 API。

2026-10-29 总览返回按钮去重：未放大时保留「放大核对当前空间」，放大后仅显示「显示全部空间」，移除重复的「返回全图」。构建和 `git diff --check` 通过；space-review 已执行，但在既有「手动空间 4」排版侧栏改名用例处超时，未记为全量通过。

2026-10-29 四类建筑标注迁至布局选品：柱、门洞净空、窗和禁放区统一在单空间设计中创建与编辑，空间总览移除对应按钮和选中/拖动能力；保留既有约束显示与排版避让。支持拖框、移动、角点/弧线缩放、数值尺寸、门洞换向、柱/窗贴墙吸附、删除及刷新恢复，保存失效不再强制跳回总览。单空间设计新增原始图纸对照层，复用来源到毫米变换，支持显示开关和透明度调节，不改变几何。`pnpm build`、space-obstacles、layout-planning、single-space 和 `git diff --check` 通过；UI 均为模拟 API，详见 [GEOM-013](tasks/GEOM-013-unify-exclusions-into-obstacles.md)。

2026-10-29 自动布局与产品选型改为并列排版路径：自动布局按功能模块、实际空间、目标人数和约束生成座位排列与候选方案，应用后可进入产品选型手动调整，或确认排版并直接进入款式推荐。产品选型作为手动排版工作台，支持位置、尺寸、旋转、复制、删除、逐件添加和清空；选中家具后点击新图例可直接替换模块，保留 ID、中心和旋转，校验失败不改旧家具，成功后清除旧选品、确认失效并可一次撤销。图例主卡片不再默认跳回自动布局，独立「自动」按钮保留显式跨路径入口。自动布局页已恢复统一「清空排版」，同时取消全部功能模块选择并清除预览；操作区不再显示黄色/说明性策略提示，只保留阻止应用或确认的红色错误。款式推荐继续要求先确认排版。另修复商品详情缺少 images 时的运行时异常。构建、module-recommendations、module-selection、layout-planning、auto-layout、adaptive-layout、furniture-modules、custom-modules、reference-layouts、layout-references、single-space、delete-modules、workflows、MOCK smoke 和 `git diff --check` 通过；UI 均为模拟 API。通道长专项在确认阶段超时，未记为通过；详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-28 核对页改版与多楼层合并整合：空间核对左侧改内嵌紧凑项目树（后按用户要求删除该面板，回归画布+右栏两栏）、删除独立 SpatialInspector、新增 CustomerRequirementsBlock、识别空间卡片恢复并补用途区域。合并冲突解决遗留的声明丢失（workspace/status/dimensions/displayedZones 等）已补回；图层面板滑块遮挡空间手柄，移到画布右上角；canvas.vue 在楼层加载途中不再误剥 zoneId。验证：`pnpm build`、e2e:space-review、drawing-entry、annotations、project-floors、MOCK smoke 通过。遗留：e2e-selected-space-entry 第二轮迭代 advance 未跳转待跟进（enterDesign 门槛排查中）。

2026-10-28 识别空间与客户需求移到空间核对右侧栏（按用户要求）：页面回到三栏——左栏只留「项目与空间」楼层树（+底图/副本说明、加载示例），右栏为「客户需求」+「识别空间」列表（选择/改名/删除/手动画新空间均随迁）。客户需求块从 ProjectFloorTree 抽为 `CustomerRequirementsBlock.vue`（双击就地编辑 + 编辑对话框行为不变），树组件新增 `requirements` 属性（默认 true，仅空间核对页传 false 避免重复）；AppSidebar 等其他页面不受影响。≤1000px 时右栏整行下折（max-height 340px）。随后按用户反馈简化列表操作：**点击空间名直接就地改名**（移除独立「改名」按钮，hover 名字显示下划线提示；描点/校准/画区期间不响应），**删除改为小 ✕ 图标**（hover 显示「删除空间」提示，Aria 标签不变）；再按用户反馈（✕ 太丑、提示不明显）改为**平时收起、悬浮整行才浮现**的描边小方块（悬浮变红），提示改为左侧深绿气泡（`data-tip` 伪元素，替代慢速原生 title）；空间卡片由 button 改为 role=button 的 div 以支持名字内嵌点击。再按反馈（按钮游离在卡片外很别扭）把 ✕ **收进卡片右缘、垂直居中**悬浮浮现，卡片两行右留 26px 避让，点击带 `.stop` 不触发选中。验证：`pnpm build`、`pnpm e2e:space-review`（改名断言改为点击空间名）、`pnpm e2e:project-floors`、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`git diff --check` 通过；代码未提交。

2026-10-28 空间核对右栏属性面板整体移除（按用户要求）：「图层与对照」（原始图纸/底图透明度/设计空间轮廓/实际尺寸标注/原始识别轮廓）移到中间画布右下角浮层，「空间用途」下拉移到画布左上角（带当前空间名）；右栏 SpatialInspector 组件删除。名称/宽深编辑走画布点空间标签的内联表单（既有），两点校准走工具条按钮（既有）；「恢复本次识别轮廓」按钮与精确净面积读数随面板一并移除（面积看左侧列表，1 位小数）——取舍经用户确认「全部移除」。浮层在逐点描/重描/校准/合并剔除/障碍标注等画布采点期间自动收起，且图层浮层仅开关与滑杆本身可点（其余区域指针穿透），避免遮挡画布点击与 se 缩放手柄。e2e 适配：面积断言改读列表选中行（43.2/36.0/24.0/20.0/5.5/7.5 m²）、尺寸编辑改画布标签内联表单、「边界重叠/轮廓不一致」提示断言改列表「需复核」徽标、改名改列表行内表单、新增图层开关定位于画布浮层与左上角用途下拉断言。验证：`pnpm build`、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`git diff --check` 通过；代码未提交。

2026-10-28 方案页需求/批注面板配色柔化：按用户反馈（需求描述亮蓝、客户批注亮黄过于突兀），改为贴合站点暖色调的浅色版——需求描述 `#087dbb` 实蓝 → 浅蓝底 `#eef6fb`+蓝灰文字，滚动条/正文字色同步减淡；客户批注 `#fff36a` 亮黄 → 米黄底 `#fdf9e7`+橄榄文字；均补细边框与 hover 浅一档。验证：`pnpm build`、改版后截图人工核对通过。
2026-10-28 通道设置移至画布工具栏：在禁放区图标后新增“通道”图标和弹出操作区，支持添加纵/横通道、宽度、位置、起终点、两侧净距及仅从二排起；排版参数区旧通道面板移除，画布拖动与确认避让继续共用 `layoutPreferences.channels`。构建通过，通道新增及后续编辑步骤已跑通，截图人工核查；长专项仍在既有“家具越界/离墙不足”确认约束处失败，未记为全量通过。

2026-10-28 禁放区入口移至画布工具栏：按用户反馈移除排版参数区“入口与禁放区”设置，顶部工具栏在“效果预览”后新增门形禁放区图标，点击携带当前空间返回总览并聚焦标注；排版继续读取总览建筑约束，避让语义不变。layout-planning、space-obstacles 和构建通过，跳转截图已人工核查，均为模拟 API。

2026-10-28 多选功能模块组合布局：按用户澄清将功能品类改为可多选；单选继续原排版，两个及以上进入 `MixedAutoLayoutPanel` 和 `mixedLayout` 组合路径，按钮移到组合数据下方。组合包覆盖办公桌椅、沙发茶几、会议、茶桌、餐厅、玄关、娱乐、吧台和会客，办公桌椅跟随目标人数，其他默认一套，不按空间尽量排满；新增茶桌、餐桌、游戏桌、玄关柜、吧台、吧椅六个真实图例（目录 35 个）。`applyMixedLayoutProposal` 保留手动家具、替换旧自动组并维持撤销/确认失效/持久化。`test:mixed-layout`、build、layout-planning（多选组合、4 件非最大化、应用撤销）、module-selection、auto-layout、module-recommendations、furniture-modules、workflows 和 `git diff --check` 通过；组合界面截图已人工核查，UI 均为模拟 API。

2026-10-28 布局选品职责再划分：按用户反馈将完整家具图例、自定义模块和具体产品调整统一留在「产品选型」；「自动布局」改为房间类型、九类推荐功能品类、自动布局及尺寸/旋转/复制/删除调整。品类只决定初始推荐模块，不限制任何空间后续选品。当前模块 ID 在三页间共享，切换/刷新保持选中；新增 `duplicateFurniture` 复用硬校验及撤销失效链。构建及 single-space、auto-layout、layout-planning、module-selection、module-recommendations、custom-modules、furniture-modules、adaptive-layout、reference-layouts、layout-references、delete-modules、workflows 和 `git diff --check` 通过；新版自动布局/产品选型截图已人工核查，UI 均为模拟 API。

2026-10-28 布局选品三段式右侧工作台第一版：按用户参考图将原左侧排版面板迁入右侧，提供绿色选中的「自动布局 / 产品选型 / 款式推荐」三页签。自动布局保留模块、参数、排布、候选和预览，新增识别房间类型修改且不再强制返回总览；产品选型支持已排家具选择、毫米尺寸调整、旋转、删除、逐件添加与确认；款式推荐沿用真实 SKU 匹配。新增 `updateZoneType` 保留设计上下文、`resizeFurniture` 复用硬校验及撤销失效链，右栏可折叠且 900px 高度主操作可见。`pnpm build`、single-space、auto-layout、layout-planning、module-selection、module-recommendations、workflows 和 `git diff --check` 通过，三页截图已人工核查，UI 均为模拟 API。通道长专项在既有“家具越界/离墙不足”确认约束处未通过，未记为通过；详见 [LAYOUT-013](tasks/LAYOUT-013-layout-panel-ux.md)。

2026-10-28 普通办公排版离墙默认值归零：按用户要求配合过道功能，将 `OFFICE_LAYOUT_SPACING.wallMm` 及 general 单件居中/按场地排布预设从 300 mm 改为 0 mm；旧实例仍恢复已保存参数，座位侧继续按 `max(离墙, 组间过道, 座椅伸出)` 计算，会议桌独立的 1200 mm 默认值不变。同步当前 README/架构默认值及自动排版边界断言。`pnpm build`、`test:auto-layout`、`test:arrangement-presets`、`test:layout-planning`、`test:adaptive-layout`、`e2e:auto-layout`、`e2e:layout-planning` 和 `git diff --check` 通过；UI 使用模拟 API。

2026-10-28 总览与排版共用项目空间树：按用户要求将空间核对页独立“识别空间”栏并入最左侧当前楼层下，移除重复项目/楼层树和独立空间栏。总览空间项按后续反馈恢复紧凑单行样式，不显示“可用”和“改名”按钮，双击名称改名并保留删除、展开及示例加载；右侧空间属性通过“编辑”按钮修改名称，尺寸、面积和复核状态集中在属性面板。总览点击通过 `activeZoneId` 联动画布与右侧属性；排版模式继续通过 `designZoneId`/`enterDesign` 切换空间并保留状态、件数和确认汇总。画布选中靠后空间时项目树自动展开，左侧删除后清理失效聚焦，总览改为画布+属性两栏。`pnpm build`、`pnpm e2e:space-review`、`pnpm e2e:drawing-entry`、`node scripts/e2e-selected-space-entry.mjs`、`pnpm e2e:single-space`、`pnpm e2e:project-floors`、`pnpm e2e:workflows` 和 `git diff --check` 通过；UI 均为模拟 API，未执行真实后端集成，详见 [LAYOUT-009](tasks/LAYOUT-009-design-space-switcher.md)。

2026-10-28 方案页效果图占位清理：按用户反馈（无效果图的空间仍显示三个「尚未生成」黑框），预算清单空间详情条的效果图卡改为**只渲染真实生成任务**——没有任何生成任务时整卡隐藏，槽位数量=实际任务数（最多 3），成功显示图片、生成中/失败显示对应状态文字，不再出现占位黑框；移除不再使用的 renderAt 辅助。验证：`pnpm build`；双场景探针核对（无任务→0 卡 0 槽，1 成功+1 生成中→1 卡 2 槽 1 图）；e2e:workflows、MOCK smoke 通过。

2026-10-27 楼层下空间列表：按用户要求将设计页顶部“排版空间”下拉迁移到左侧当前楼层下，空间项保留排版状态/件数、当前高亮与前后切换；各楼层独立折叠并本地持久化，点击空间沿用 `enterDesign` 和 URL 同步，不改后端及几何/家具数据。项目楼层与单空间专项覆盖折叠、刷新恢复和空间隔离；实际执行结果见 [LAYOUT-009](tasks/LAYOUT-009-design-space-switcher.md) 与本次交付记录。

2026-10-27 全局直角与无缝布局：按用户反馈在 `main.css` 对所有元素及伪元素统一取消圆角和阴影，覆盖空间树、排版、画布、选品、商品、方案、表单和弹窗。空间核对三栏、单空间三栏及普通页面容器去除外圈留白和主要栏间 `gap`；上传、商品库、导入、审核、手工录入、导入记录、商品详情和方案页同步展平，保留表单字段及操作组的必要内部间距。设计页标题/副标题及重复返回区已删除；空间核对也已接入同一项目侧栏、顶部工作区栏和步骤导航，移除独立 RSDP 顶栏、介绍区及底部说明条，两种空间工作区切换时框架保持一致。顶部步骤栏左侧集中展示项目/楼层、空间设计、当前图纸和当前空间模板，模板保留查看入口，侧栏和排版面板的重复摘要已移除。`pnpm build`、空间核对、单空间、完整工作流、MOCK 冒烟和 `git diff --check` 通过；上传、两种空间工作区、方案、商品库桌面及 390px 窄屏截图已人工核对，UI 均为模拟 API。

2026-10-28 逐点描 Enter 保存：按用户要求（保存空间应该可以按 Enter）在回退后的点击描点上补回这一项——逐点描 ≥3 点可按 Enter 落库（输入框聚焦时不拦截），提示条显示「Enter 保存」。验证：`pnpm build`、`pnpm e2e:space-review`（新增 Enter 保存/撤销断言）、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`git diff --check` 通过。
2026-10-27 GEOM-017 逐点描交互探索后按用户决策**完全回退**：曲线/划线/draw.io 画法及橡皮筋、Enter 保存等全部移除，SpaceReview/SpatialCanvas 描点链路恢复 `origin/main` 行为（git diff 核对零残留，[任务记录](tasks/GEOM-017-sketch-curves.md) 存档各轮方案与回退原因）；合并/剔除、窗标注、工具条图标等其余改动不受影响。验证：`pnpm build`、`pnpm e2e:space-review`（GEOM-017 段落移除后旧断言全保持）、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`git diff --check` 通过。

2026-10-27 GEOM-015 窗标注：按用户要求（窗与障碍物一样挂在单空间内）实现（[任务记录](tasks/GEOM-015-window-openings.md)）。障碍体系新增 `WINDOW` 类型（前后端契约同步）：单空间焦点内沿墙拖矩形标注，复用障碍移动/角点调整/删除/贴墙吸附/撤销链路；定稿为**标注型约束**——不扣净面积、不进排版避让与通道疏散阻挡、服务端排布校验跳过（仍校验几何有效性），窗台高与窗下矮柜规则待业务确认。标柱/门洞/禁放区按钮同步补图标。验证：`pnpm build`、`pnpm test:obstacle-window`（7 项）、`pnpm e2e:space-review`（新增窗标注断言）、`mvn test` 全量 205 项（新增窗不拦截已确认排版用例）、`MOCK_API=1 pnpm e2e:smoke`、`git diff --check` 通过；真实数据库链路与业务验收未执行。

2026-10-27 GEOM-014 空间合并与剔除：按用户给出的目标画图工具条盘点缺口并立卡——本轮实现合并/剔除（[任务记录](tasks/GEOM-014-space-merge-subtract.md)），窗标注与布局边界分别立卡 [GEOM-015](tasks/GEOM-015-window-openings.md)/[GEOM-016](tasks/GEOM-016-layout-boundary.md) 待启动。空间核对工具条新增「合并空间」「剔除区域」两步点选：布尔引擎用新增依赖 polygon-clipping（`utils/spatial/boolean.ts` 封装，0.01 取整+ringProblem 校验后落库），结果为多块/带孔洞按定稿拒绝并给可操作提示；合并迁移家具/障碍/选品并删除被并入空间，确认与排版失效，单次撤销完整恢复。验证：`pnpm build`、`pnpm test:space-boolean`（10 项）、`pnpm e2e:space-review`（新增合并/剔除/拒绝/撤销断言）、`MOCK_API=1 pnpm e2e:smoke`（自建 3001 端口）、`git diff --check` 通过；随后按用户要求补齐工具条图标（AppIcon 扩展 cursor/scissors/merge/fitScreen，图标在上文字在下，e2e 画布定位改 aria-label），build/space-review/smoke/diff-check 复验通过；真实数据库链路与业务验收未执行。

2026-10-27 服务端确认校验对齐方向性座椅净空：按用户反馈（已确认排版被后端以「已确认家具离墙或通行预留不足」拒存，后续编辑保存不上、刷新后回退，表现为"删除没反应"），定位为 7b3dcd2 前端改方向性规则（桌子只留座椅侧、屏风/桌板侧可靠墙）后服务端 `OfficeLayoutValidationPolicy` 仍四边整体外扩。后端移植 `seatSideRect` 语义：非座椅按 kind（desk 含 560 默认、L/U 卡位转角两边含镜像）只校验座椅侧，椅子四边保留 max（离墙，组间过道）；内建模板 kind 表+自定义模块快照读取。新增 3 个用例并更新 1 个旧语义用例，`mvn test` 全量 204 项通过（0 失败）。

2026-10-27 合并 origin/main 多楼层项目工作区：merge-tree 预检无冲突，stash→merge→pop 落地，重叠文件（office.ts/status.md/architecture.md）自动合并成功；修复远程 `e2e-project-floors.mjs` 缺 `/api/auth/me` mock（本地 VITE_AUTH_ENABLED=1 下跳登录页超时），合并干净构建上 floors/space-review/drawing-entry/aisle-channels/smoke 全通过。用户环境 404 系 IDEA 旧后端进程未重启，重启即加载新接口。

2026-10-26 排版简化四件套：按用户反馈（排版逻辑太复杂、限制太多）——①**阻塞降级为提示**：手动移动/旋转/逐件放置拆出硬校验（`officePlacementHardProblem`：越界/重叠/入口禁放区仍拦截）与软提示（`officePlacementSoftWarning`/`officeFurnitureMoveWarning`：座椅侧 560mm 净空不足只提示「已生效；确认排版时会被拦截」不再拒绝操作），严格确认语义（`officePlacementProblem`/confirmedLayoutProblem/后端核验）不变；②**两档界面**：侧栏新增「高级选项（拉椅预留/通道）」与参数页「高级间距参数」两个默认收起的折叠块，默认只剩选模块→选排布→应用→确认；③**排布推荐化**：排布页标记「推荐」徽标（默认选中项）并提示可直接应用；④通道面板移入高级选项（通道/入口联动提示本就只在有通道时出现，无需改逻辑）。验证：`pnpm build`；`test:layout-planning/layout-channels/arrangement-presets/adaptive-layout/reference-layouts`、e2e-aisle-channels（高级选项嵌套适配+既有断言）、e2e:layout-planning/meeting-layout/adaptive-layout/workflows、MOCK smoke 通过；阻塞拆分经 Node 单测级探针逐条断言；推荐徽标与折叠面板截图人工核对。注意：`test:auto-layout` 的「面对面默认留出座位侧净距」旧断言（900）已按 LAYOUT-038 定稿值（自动加宽至 1300）更新并通过；e2e-custom-modules/multi-furniture/furniture-modules/reference-layouts 启动段失败为基线既有问题。

2026-10-26 职员桌旋转被拒原因明示：按用户反馈（职员桌点旋转无反应），定位为 10-22（7b3dcd2）引入的方向性座椅净空校验——桌子旋转 90° 后座椅侧朝向改变，若该侧离墙不足 560mm（如贴墙工位转成面向墙）即被 `officePlacementProblem` 拒绝，属既有设计而非回归；但原提示只笼统显示「此位置超出空间或离墙距离不足」。`rotateFurnitureGroup` 现将该场景翻译为可操作文案「旋转后座椅一侧需要 560mm 净空：当前朝向会贴近墙体，请先把家具拖离墙面或换个位置再旋转」，显示在画布底部提示条。验证：`pnpm build`、e2e:layout-planning 通过；复现脚本实测被拒桌子返回新文案；e2e-multi-furniture 在 stash 基线同样启动即失败，为既有问题与本次无关。

2026-10-26 确认排版阻塞原因明示：按用户反馈（已排版但确认失败且无原因说明），`store.layoutConfirmProblem` 返回具体阻塞原因——越界/重叠/禁放区冲突（细分到「家具压占通道」「椅子活动区被占用」等）、目标人数不足（「目标 N 人，当前仅 M 座」）；排版侧栏在「确认当前排版」按钮上方实时显示「暂不能确认：<原因>」（`data-testid="confirm-problem"`），点击确认时弹同一具体文案，替代原笼统提示。复现结论：矩形 4043×9142 + L 型屏风卡位单排 + 通道的几何本身可通过确认，用户场景大概率为通道压占或目标人数不足，现可直接看到原因。验证：`pnpm build`、e2e-aisle-channels（新增步骤 12：目标不足/压占通道文案与解除后确认；步骤 6 更新为具体文案）、test:layout-planning、test:layout-channels、e2e:layout-planning、MOCK smoke 通过；侧栏提示截图人工核对通过。

2026-10-26 LAYOUT-038 椅背对背通行净宽：净宽口径定稿为 2×拉椅预留+500mm 通行（默认 1300mm，策略值）；planOfficeFurniture 配对模式自动加宽（faceToFace 组内间隔、backToBack 组间排距），扁空间放不下自然减排；样式参考排布（adaptive 预设）间距按素材固定不加宽、低于净宽只给黄级提示（035 分级体系，`chair-back-gap` 条目说明原因与数值）；手动调小「图例组间净距」触发同级提示；确认校验不纳入。`pnpm build`、`test:layout-planning`（新增加宽/减排/提示用例）、`e2e:layout-planning`（新增手动调距提示断言）、五个排版相关 e2e 回归、smoke、workflows、`git diff --check` 通过；后端零改动、真实集成验收未执行，见[任务记录](tasks/LAYOUT-038-chair-back-passage.md)。

2026-10-26 LAYOUT-035 校核预警分级与修改建议：分级口径定稿（红=会被确认/应用拒绝的硬冲突，含方案无法排出与座位不足；黄=低于策略阈值，含通道宽度<900mm、入口未衔接/截断、人均面积<2.0㎡/座、边缘补位；info=未核验与现场核验说明），黄/红附修改建议；023 通道宽度与 029 入口联动提示迁入统一预警块（保留旧 testid），座位摘要「尚缺」迁入红级条目，proposal.warnings 首次在面板可见；新增人均面积校核（净面积/座位，策略值）。顺带修复侧栏统计区溢出遮盖点击（候选/预警块加 max-h 内部滚动，smoke 复现）。`pnpm build`、`test:layout-planning`（新增分级用例）、`e2e:layout-planning`/`e2e:aisle-channels`、auto-layout/meeting/adaptive/proposal-layout 回归、smoke、workflows、`git diff --check` 通过；后端零改动、真实集成验收未执行，见[任务记录](tasks/LAYOUT-035-warning-levels-and-advice.md)。

2026-10-26 LAYOUT-034 多方案候选评分对比：排版引擎返回 Top 3 候选（课桌式网格暴露 auto 内部朝向/对齐变体、会议沿用尺寸×朝向×位置枚举，签名去重），评分口径定稿为目标达成单因子（1000 分/座封顶于目标、超出 1 分/座，并列保持生成顺序，默认方案不漂移），利用率/人均余量仅展示；面板候选卡片点击切换预览（不落库）、按家具签名跟随选中、应用携带候选索引走既有失效链路。`pnpm build`、`test:layout-planning`（新增评分/去重/排序/指标用例）、`e2e:layout-planning`（新增卡片切换/应用断言）及 auto-layout/meeting/adaptive/aisle-channels/proposal-layout 回归、smoke、workflows、`git diff --check` 通过；顺带修复 e2e-proposal-layout 缺 auth 模拟的既有缺口；后端零改动、真实集成验收未执行，见[任务记录](tasks/LAYOUT-034-candidate-scheme-comparison.md)。

2026-10-26 外部参考盘点与新任务卡：按用户提供的 Kimi 分享链接（培训空间自动布局原型 V1–V6 会话）逐项比对 RSDP 现状，产出[差距分析](../docs/references/kimi-training-layout-tool-gap-analysis.md)（标注外部参考、非目标契约，其数值均为对方策略值）。已覆盖：通道系统（021–029 语义超出原型）、门洞避让、椅子占位、目标人数校核。据此立卡：LAYOUT-032 U 型/回字型、LAYOUT-033 剧院式纯椅排、LAYOUT-034 多方案候选评分对比、LAYOUT-035 校核预警分级与修改建议（含人均面积）、LAYOUT-036 求解模式扩展、LAYOUT-037 宽桌双侧坐人、LAYOUT-038 椅背通行净宽自动加宽；弧形/阶梯直排并入 LAYOUT-031（暂缓）；讲台朝向、主席台产品类型、组合会议桌、线条图导出需业务确认后再立卡。纯文档变更，无代码改动。

2026-10-24 GEOM-013 入口与禁放区统一为建筑约束：按用户确认方案，排版面板「入口与禁放区」改为只读摘要（名称+毫米尺寸）+「返回空间核对标注」跳转（自动选中并聚焦当前空间）；旧 `layoutPreferences.exclusions` 加载时迁移为 NO_PLACEMENT obstacle（保留稳定 ID 与命名，新增 `SpatialObstacle.label`），`zoneLayoutExclusions` 去掉 legacy 分支，预览层 exclusions 渲染移除；设计页常显约束红/蓝虚线，修复“看不到效果”。`pnpm build`、`test:layout-planning`、`e2e:space-obstacles`/`e2e:layout-planning`/`e2e:space-review`/`e2e:annotations`、mvn 191 项（188 通过/3 跳过）与 `git diff --check` 通过；截图人工核对通过；后端零改动，真实集成验收未执行，见[任务记录](tasks/GEOM-013-unify-exclusions-into-obstacles.md)。

2026-10-24 GEOM-012 画布标注可读性与编辑：按用户确认方案，便签位置仍使用世界坐标，显示按缩放反向补偿，保持约 220px 宽和 14px 屏幕字号；接入双击/选中操作栏编辑、完成/取消、删除及撤销，保留空间归属和刷新恢复。`pnpm build`、`pnpm e2e:annotations`（API 全模拟）及 `git diff --check` 通过，专项截图已人工核对；未执行真实后端集成或完整业务验收，见[任务记录](tasks/GEOM-012-readable-annotation-editing.md)。

2026-10-23 通道编辑卡跟随通道位置：按用户反馈，LAYOUT-024 的通道浮动编辑卡原固定在画布顶部居中，改为跟随选中通道条带——世界坐标经相机/旋转换算屏幕位置（含位置/宽度拖动预览），优先放条带右侧（横向放下侧），不足换边或选余量大侧贴边，钳制在画布窗口内。e2e-aisle-channels 步骤 5.7 增补相邻与钳制断言并通过；build 通过；宽屏截图人工核对条带旁 12px 相邻。另补记：4b29a6a 遗漏的侧边栏「手动布局」删除已在 1945def 补提交（并行会话还原导致空暂存）。

2026-10-23 LAYOUT-029 通道与入口/疏散联动提示：入口数据盘点结论——识别层（视觉/CAD）不产出门/开口、openings 契约层恒空，唯一入口代理为人工标注的门洞净空障碍。新增纯函数 `channelEgressHints`（utils/channelEgress.ts）：通道端头 600mm 缓冲内未接到门洞净空提示"未衔接入口"；端头到门洞的衔接走廊被家具/禁放区挤至净宽不足 600mm 提示截断；未标注门洞的空间只提示未核验。严格级别按用户定稿为**提示不拦截**（排版面板通道区 amber 文案），后端核验挂点不接入（无 warnings 通道，偏差记录于任务卡）；阈值均为策略值非规范。验证：`pnpm build`、`test:layout-channels` 新增 029 用例、通道专项 e2e 步骤 12、三组排版回归与 smoke（自建 3013 端口）通过，见[任务记录](tasks/LAYOUT-029-channel-egress-linkage.md)。



2026-10-23 页脚按钮阻塞弹出提示：按用户反馈（未校准时「生成空间模板」「进入家具排版」只禁用无说明；且要求弹出提示而非旁侧文字），两按钮改为 `aria-disabled` 灰化但可点击（`data-testid="template-workbench"/"advance-layout"`），点击时在画布中央弹出 toast 告知原因与下一步——未校准提示「图纸比例待校准，请先用工具条『两点校准』确认图纸尺寸比例」，校准中/描边中/约束编辑中分别给出对应引导；无阻塞时正常进入。e2e:space-review 增补灰化、点击弹 toast、不跳转/不开模板断言并通过；build、smoke、workflows 通过；toast 截图人工核对通过。

2026-10-23 LAYOUT-028 通道端点留断：通道新增 `startMm`/`endMm`（世界毫米，缺省贯通、旧数据无需迁移），与「仅从二排起」按交集收窄走向跨度；渲染、压占确认（前端 confirmedLayoutProblem + 服务端 channelBands 同步语义与测试）按留断范围生效，编辑卡与排版面板均可数值编辑（空=贯通）。定稿：fromSecondRow 保留独立动态字段不迁移；横向不做对称布尔（列轴无"首块"语义，用端点数值表达）；自动排版切段仍按未留断全轴禁放带（绕过端点需二维装箱，留作后续评估）。验证：`pnpm build`、`test:layout-channels`/`test:layout-planning`、mvn 191 通过（新增端点用例）、通道专项 e2e 步骤 11、smoke（自建 3012 端口）通过，见[任务记录](tasks/LAYOUT-028-channel-endpoint-breaks.md)。真实提交链路集成验证未执行（同 LAYOUT-022 记录）。

2026-10-23 LAYOUT-027 通道多边形裁剪：`clipRectToRingRects`（扫描线分段纯函数）把通道禁放带按房间多边形裁剪——凹形空间条带不再画出缺角，一条通道可输出多段矩形参与渲染/压占校验/避障；矩形空间输出与现状逐值一致。排版切段仍按未裁剪全轴禁放带（桌列不跨通道线）；服务端核验无需改动（确认家具先强制在轮廓内，交集判定等价）。验证：`pnpm build`、新增 `test:layout-channels` 单测、通道专项 e2e 步骤 10（U 形截断/断开断言）、排版回归与 smoke 通过，见[任务记录](tasks/LAYOUT-027-channel-polygon-clipping.md)。顺带修复既有损坏：`test:auto-layout`/`test:reference-layouts` 加载器未别名 layoutChannels 传递依赖（自 a3f5278 起无法运行）；`test:design-corrections` 仍因 `./obstacles` 未别名失败（与本任务无关，未修）。

2026-10-23 一键旋转 90°：按用户要求，选择工具条旋转表单新增「旋转 90°」按钮（`data-testid="rotate-90"`）——显式动作点击即应用（绕质心顺时针 90°，丢弃未提交的角度预览，可撤销），与角度输入的 Enter/应用语义并存。e2e:space-review 增补一键 90° 逐顶点比对与撤销断言并通过；build、smoke 通过；工具条与 U 形旋转结果截图人工核对通过。

2026-10-23 旋转交互定稿：按用户明确，**仅 Enter 或「应用」按钮提交旋转**；调整角度时画布即时预览（不落库），点击空白处、选中其他空间、拖动或输入框失焦只放弃预览、不应用，Esc 同样放弃。预览放弃后角度值保留，点「应用」仍可按当前角度提交。e2e:space-review 增补点空白不应用、放弃后「应用」正常提交、Esc 后点击不应用、撤销断言并通过；build、smoke 通过。

2026-10-23 空间旋转实时预览与 Enter 应用：按用户要求，旋转角度输入时画布即时预览旋转结果（渲染层覆盖，复用拖动预览同一 zones 注入通道；dirty 门控避免选中即预览），未应用前存盘几何不变；Enter 或「应用」提交落库，Esc/失焦/切换选中取消预览。e2e:space-review 增补「输入未应用存盘不变」与 Enter 提交断言并通过；build、smoke 通过；U 形 45° 预览截图人工核对且确认 localStorage 未变、Enter 后才写入。

2026-10-23 已画空间支持旋转：按用户要求，总览选择工具条新增「旋转」角度输入（默认 90°，屏幕顺时针为正，±360°），选中带轮廓空间后应用——`store.rotateZone` 绕多边形面积质心旋转（质心为不动点，连续旋转不漂移；存储环 Y 向下，正角即屏幕顺时针，与画区模块旋转约定一致），柱/门洞等建筑约束随轮廓同步旋转，`zoneLayoutProblem` 校验失败不落库；走 `updateObject` 既有失效链路（确认/排版/方案失效、可撤销）。`utils/spatial/geometry.ts` 新增纯函数 `rotateRing`。旋转后矩形不再轴对齐时宽/深数值编辑自动停用（保持异形轮廓）。e2e:space-review 增补六顶点 L 形 30° 逐顶点核对、-30° 回退与撤销断言并通过；smoke 通过；U 形 30°/-30° 截图人工核对通过。

2026-10-23 LAYOUT-026 通道拖动吸附：通道位置拖动复用家具吸附模块（禁放带为吸附矩形，目标=墙边+家具占地边缘+其他通道带边缘），阈值与开关与家具吸附一致（50mm、工具栏"吸附"），参考线走既有 snap-guide 覆盖层；吸附量叠加后再经包围盒钳制。取舍记录：未做"间隙中线"吸附与缩放换算阈值（与家具吸附保持同一手感）。验证：`pnpm build`、通道专项（新增吸附断言）、四组排版回归通过，见[任务记录](tasks/LAYOUT-026-channel-drag-snapping.md)。至此通道补全第二期（选中态/拖柄/吸附）完成。

2026-10-23 LAYOUT-025 通道宽度拖柄：选中通道条带后两端显示恒定屏幕尺寸拖柄，拖 max 边只改宽度、拖 min 边位置与宽度联动（远端固定），净距不变、禁放带不出包围盒（预览即钳制、松手一次提交走 updateZoneChannels 撤销/失效路径）。验证：`pnpm build`、通道专项（新增拖柄换算/远端固定/越界钳制断言）、四组排版回归通过，见[任务记录](tasks/LAYOUT-025-channel-width-handles.md)。

2026-10-23 两点校准取消按钮移至画布右上角：按用户反馈，原居中提示条内的「取消」链接拆出为画布右上角独立「取消校准」按钮（`data-testid="cancel-calibration"`），居中提示条只保留操作指引；校准全程（含已选点待输入时）都可一键取消。验证：`pnpm build`、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke` 通过；校准态截图人工核对按钮位置与点击取消恢复正常。

2026-10-23 空间卡片尺寸溢出适配：按用户反馈，总览左侧空间卡片在超大空间（如 81,500 × 59,800 mm）下尺寸行 nowrap 溢出、挤压改名/删除按钮。卡片两行改为允许换行：尺寸文本 `overflow-wrap:anywhere` 自动折行并加 title 悬浮全文，面积固定 nowrap 不挤掉，空间名超长省略加 title，徽标不收缩。验证：`pnpm build`、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke` 通过；超大+普通双空间卡片截图人工核对通过。

2026-10-23 LAYOUT-024 通道选中态与画布内编辑：设计模式点选通道条带即选中（高亮实线边框+加深斜纹），画布顶部弹出浮动编辑卡（宽度/两侧净距/位置/「仅从二排起」/移除，含 900mm 阈值提示），编辑走 `updateZoneChannels` 同一入口（撤销/失效语义一致）；选中家具或对象、点空白、切换工具、Esc 均取消通道选中，Delete 直接移除选中通道。注：design 模式无属性检查器（右侧为选品面板），按任务卡记录的偏差改用浮动编辑卡。验证：`pnpm build`、通道专项（新增选中断言）、自动排版/规划/自适应/会议回归、`MOCK_API=1 pnpm e2e:smoke`（默认 3001 被旧构建残留实例占用，改用 BASE_URL=3011 对当前构建通过，残留进程未动）通过，见[任务记录](tasks/LAYOUT-024-channel-selection-inspector.md)。

2026-10-23 手动画布常驻返回按钮：按用户反馈，空间核对总览工具条新增「← 返回上传图纸」（`data-testid="back-to-upload"`，在画区 inert 区外，画到一半也可返回），解决手动画图后突然有图纸要上传时无明确回退入口的问题；返回不清空手绘画布，已有手绘空间保留，重新上传识别仍走既有换图/快照保护。验证：`pnpm build`、`pnpm e2e:drawing-entry`（新增返回断言）、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke` 通过；空白态与已画空间态截图人工核对通过。

2026-10-22 LAYOUT-022/023 通道第一期：服务端核验与宽度阈值提示。后端 `OfficeLayoutValidationPolicy.requireConfirmedValid`（saveCanvas 与复核链路既有挂点）新增通道禁放带校验——语义对齐前端 `channelRects`（宽度+2×净距、包围盒裁剪、「仅从二排起」按 firstBlockDepthMm 豁免首排），已确认家具旋转占地压占即拒绝；前端通道宽度低于 900mm 给策略提示（非规范强制，不拦截）。`mvn -f backend/pom.xml test` 190 通过（含新增 4 例通道用例）；前端构建、通道专项与四组排版回归通过；真实数据库集成未测，见[服务端核验](tasks/LAYOUT-022-channel-server-verification.md)、[阈值提示](tasks/LAYOUT-023-channel-width-threshold-hints.md)。

2026-10-22 LAYOUT-021 通道系统与补全路线：横/纵向通道成为排版约束兼画布对象——存于 `layoutPreferences.channels` 随画布 JSON 持久化，画布拖动定位（钳制在空间包围盒内、预览与提交同步不越界）、面板编辑宽度与「两侧净距」（clearanceMm，禁放带按 宽度+2×净距），纵向通道可「仅从二排起」保持首排完整（首排深度随实例保存供确认豁免）；课桌式网格原生切段分列/分排，其他排布把通道作硬阻挡；确认校验家具（旋转占地）不得压占通道，通道增删改使本空间确认失效。构建、通道专项 UI mock（分列/二排起/净距/钳制/拖动提交/确认拦截/横向/撤销/刷新）与自动排版、规划、自适应、会议四组回归通过，详见[任务记录](tasks/LAYOUT-021-canvas-aisle-channels.md)。注：本任务原编号 LAYOUT-020，因与边缘补位任务撞号改记为 LAYOUT-021。通道补全路线已立卡：LAYOUT-022 服务端核验、LAYOUT-023 宽度阈值提示（本期已完成，见上条）；待启动：LAYOUT-024 选中态与检查器、LAYOUT-025 宽度拖柄、LAYOUT-026 拖动吸附、LAYOUT-027 多边形裁剪、LAYOUT-028 端点留断、LAYOUT-029 入口疏散联动、LAYOUT-030 效果图呈现、LAYOUT-031 弧形布局与斜向通道（暂缓）。

2026-10-22 删除手动布局沙盒、新增手动画图入口：按用户要求移除侧边栏「手动布局」与 `/manual-canvas` 沙盒页（页面、ManualCanvas 组件、manualCanvas store、专用相机及 `sampleManualLayout` 删除；共享家具模板/图例类型保留），上传页新增「手动画图」——无图纸从空白毫米画布开始（空状态另有「没有图纸？手动画图」），已有内容先确认，确认后 `store.startHandDrawing` 清空图纸/识别/空间（几何可撤销）并进入空间总览，沿用「画新空间」工具绘制。验证：`pnpm build`、`MOCK_API=1 pnpm e2e:smoke`（独立端口）、`pnpm e2e:space-review`、`pnpm e2e:drawing-entry`（新增手动画图断言）通过；`pnpm e2e:furniture-modules` 在基线同样超时为既有问题，详见[任务记录](tasks/WORKSPACE-002-hand-drawing-entry.md)。

2026-10-22 画区旋转：按用户要求，形状模块（矩形/L 形/U 形）新增旋转角度输入——拖出包围盒后绕中心按角度旋转，预览实时跟随；端点先取整到 100mm 再旋转以保持直角，角度在屏幕上顺时针为正（毫米场景 Y 向上自动取反）。逐点描不受影响。e2e:space-review 增补 90° 旋转宽深互换断言（用同相机未旋转 L 形推导像素比例，含取整容差）并通过；smoke、drawing-entry 复跑通过。

2026-10-22 画布名称标签快捷编辑：按用户要求，空间总览中选中空间的名称标签可直接点击，弹出就地编辑（名称/用途/矩形空间的宽与进深），保存走 `updateObject`/`resizeZoneByMm` 既有校验与联动；相机平移缩放、切换工具、切换选中、Esc、点击画布均自动关闭编辑器。仅选中且选择工具下的标签可点（`SpatialCanvas` 新增 spaceLabel 事件并阻止冒泡，避免误触发拖动），其余标签保持只读不挡交互。e2e:space-review 增补标签编辑断言（改名/改用途/改宽度联动轮廓）并通过；smoke、drawing-entry 复跑通过。

2026-10-22 空间改名全覆盖：按用户要求，所有空间在任意环节均可重命名——空间总览右侧属性面板（受控草稿输入）、总览左侧空间列表（卡片「改名」按钮行内编辑，Enter/失焦保存、Esc 取消）、家具排版侧栏（当前空间名点击即改）。改名只更新名称，不触发轮廓/确认失效。e2e:space-review 增补三处改名断言并通过；smoke、drawing-entry 复跑通过。

2026-10-22 画区形状模块追加：按用户反馈，「画新空间」不再只能逐点描——工具条提供矩形/L 形/U 形模块，拖出包围盒一次生成轮廓（毫米场景自动取整 100mm），逐点描保留给沿墙精描；生成后可编辑轮廓微调。`sketchShapePolygon` 纯函数落在 `utils/spatial/geometry.ts`；修复联调发现的拖框坐标类型错误（元组误作对象）。e2e:space-review 增补模块拖拽/逐点描/取整断言并通过；smoke、drawing-entry 复跑通过。

2026-10-22 空间设计内联手动画区：按用户要求把手动布局并入空间设计——`SpaceReview` 新增「画新空间」工具（形状模块拖拽/逐点描、校验、可撤销），无图纸空白画布直接毫米建空间（默认 12m×8m 视野）、未校准底图按源单位记录随校准换算、已校准场景按世界毫米存储（Y 轴约定与描边一致）；空状态与缺底图遮罩改为画区入口；设计页头部跳转独立沙盒页的「手动布局」链接已移除（`/manual-canvas` 页面与侧边栏入口保留）。store 新增 `addDrawnZone`，来源标记 MANUAL/user_annotation/manual_geometry。验证：`pnpm build`、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke`（补 `/api/auth/me` mock 后通过）、`pnpm e2e:drawing-entry` 通过；`pnpm e2e:workflows` 商品页超时经 stash 基线复测为既有环境问题，与本次改动无关。

2026-10-22 本地登录 401 追修：用户反馈登录成功却被弹回 /login、`/api/auth/me` 恒 401。定位为 IDEA 直接启动的后端不带启动脚本中的 `RSDP_AUTH_ENABLED` 等环境变量，`rsdp.auth.enabled` 回落 false，`checkSession` 直接返回匿名（登录接口不校验该开关，故出现"登录写库成功但 me 永远 401"）。已将 `RSDP_AUTH_ENABLED/SECRET/BOOTSTRAP_*` 补入根目录 `.env`（spring.config.import 自动读取，与启动方式解耦），并用无环境变量的临时实例实测：无 Cookie 401、带会话 Cookie 200。

2026-10-22 本地识别配置追修与预览图修复：用户本地图片/PDF 识别报"请求或模型地址无效"（百炼 HTTP 404），实测根目录 `.env` 密钥对 `qwen3-vl-max` 返回 404 `model_not_found`、`qwen3-vl-plus` 最小对话探测 200；`.env` 的 `RSDP_VISION_MODEL` 已改为 `qwen3-vl-plus` 并重启本地后端，用户图纸的业务级重新识别待用户重试，不据此扩大识别精度验收。同会话修复上传页/画布入口预览图裂图：库存绝对 `previewUrl` 跨入口不可达，改经 `sameOriginAssetUrl` 同源化（已随 `327233c` 提交）。

2026-10-22 Jenkins 拉取失败修复：连续构建在 git fetch 阶段失败（服务器直连 GitHub 不稳定；root 早有 ghfast.top 镜像改写而 jenkins 用户没有）。已为 jenkins 用户配置同样规则并实测通过；另完成商品/预览图片 HTTPS 混合内容修复（`abb309e`）与图纸预览修复（`327233c`），待 DEPLOY 发布上线。

2026-10-22 LAYOUT-020 边缘补位：针对"空间排不下时不会像参考方案灵活调整"的反馈，自适应排版改为两阶段填充——主参考单元网格不变，边缘余量沿墙单件补位（座位朝内），完整单元放不下时降级为沿墙补位起步方案；规则版本 `2026-10-edge-fill-v7`，旧布局需显式重新应用。构建、自适应/规划等六组纯函数测试、参考图例/自适应/自动排版/规划/模块选择/流程 UI mock 通过，已人工核查补位截图，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)。

2026-10-22 排版细节修正：直线桌/一字屏风卡位座席线稿靠背改为远离桌子一侧；修复删除选中模块/清空排版后画布仍以半透明虚框显示待应用预览的问题——手动删除/清空会暂停当前空间的待应用预览（可重新勾选恢复），并修复清空排版需两次撤销的冗余快照。构建与十二组 UI mock/纯函数回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记。

2026-10-22 待应用预览追修：进入单空间设计不再自动显示预览（选择模块/排布或勾选才出现），删除/清空/移动/逐件添加即暂停当前空间预览（可重新勾选恢复），切换模块预览时隐藏将被替换的自动组避免重叠的预览机制沿用同一入口。构建与全部排版相关 UI mock/纯函数/冒烟回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记三、四。

2026-10-22 预览幽灵修复：应用方案后手动移动家具，原位置不再留下待应用预览虚框——手动编辑标记（lastManualFurnitureEdit）扩展到单件/整组移动与逐件添加，触发即暂停当前空间预览，可重新勾选。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记四。

2026-10-22 排版正确性追修：补位新增座椅区约束（一字类椅子伸出 560mm，过浅余量不再补位、椅子不再重叠），主网格与补位均校验座椅区不出房间轮廓，补位座位前方保留 max(座椅伸出, 组间净距) 净空——修复一字单排椅子画出左墙、L 型单排底部 152mm 假过道。构建与全部排版相关回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记五。

2026-10-22 方向性离墙：屏风/桌板侧可靠墙、离墙距离只保留座位一侧（max(离墙, 座椅伸出)），手动放置/确认核验与方案复核同步放开，同总数优先主网格更完整的候选；侧栏标注"离墙距离（座位侧）"。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记六。

2026-10-22 座位朝墙留过道：L/U 卡位座位区按转角两边（含镜像）校验，座位朝墙净空提升为 max(离墙, 组间过道 900, 座椅伸出)；手动放置/确认保持宽松，严格过道仅自动规划执行。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记七。

2026-10-22 手工排布吸附：拖动与逐件添加 50mm 内吸附墙面和其他模块（贴合/对齐取最近），工具栏"吸附"开关默认开，吸附引导线、逐次撤销；新 test:furniture-snap 专项。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记八。

2026-10-22 选中家具操作补齐：画布选中工具栏新增旋转 90°/水平镜像（单选多选均生效，R 键旋转），旋转按新占地重新校验越界/重叠/座椅区，操作暂停预览、使确认失效、各一次撤销。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记九。

2026-10-22 切换模块预览不再重叠：待应用预览显示期间隐藏将被替换的自动组家具（数据不删、应用时才真正替换），画布直接呈现"先清空再展示"的效果。构建与全部排版回归通过，见[任务记录](tasks/LAYOUT-020-edge-fill-layout.md)补记十。
2026-10-21 HTTPS 入口启用：用户提供的阿里云 DV 证书（覆盖 rooom.vip/www.rooom.vip，至 2027-01-16）已部署到 443；原静态站保留，`https://rooom.vip/office/` 代理应用，Cookie 恢复 Secure，真实 DNS 全流程复测通过。HTTP 80/8081 入口按用户要求保留，未做 80→443 跳转；证书到期需手动续换。



2026-10-21 域名 DNS 切换完成：`rooom.vip` 旧 A 记录 `120.77.15.115` 已删除（此前双 A 轮询导致旧服务器 301 跳 `https://www.rooom.vip`，静态资源跨源失败）。权威与主流递归实测只剩本实例，`http://rooom.vip/office/` 真实 DNS 全流程复测通过；`www` 仍指旧服务器按需处理，个别递归 TTL 残留为过渡现象。



2026-10-21 测试产物清理：按用户要求 e2e 脚本不再生成截图（space-review/space-template/dimension-calibration/workflows/render-preview/drawing-entry 及两个公网复测脚本），`output/` 与 `frontend/screenshots/` 已删除并加入 .gitignore。顺带修复三个被合并变更遗留下来的 UI 回归：space-review/dimension-calibration/workflows 补 auth mock，workflows 适配商品页 20 条分页、详情改路由页、导入改预览/校验/确认三段接口；import.vue 结果到达后清除离开守卫残留提示。build、drawing-entry、templates、render-preview、dimensions、space-review、workflows 及两个公网复测全部通过。



2026-10-21 Jenkins 改造按用户决定从简：曾完成 CI/CD 双任务拆分方案（本地验证后已还原），最终保留现有单一 `rsdp-office` 任务与 `DEPLOY=false` 默认只构建、手动勾选才发布的流程，Jenkinsfile 与 deploy.sh 均不改动；仅补 `deploy/.env.example` 子路径端口示例。现有流程已满足：SCM 轮询自动测试构建、测试失败不进入部署、部署仅限当前 origin/main、健康检查失败即任务失败。



2026-10-20 域名子路径入口：[DEPLOY-004](tasks/DEPLOY-004-domain-subpath.md) 已实现 `http://rooom.vip/office/`。新增 `frontend-office` 双基座容器与 80 站点 `/office/` 代理，IP 8081 与原站点不受影响；识别绝对资源地址跨入口 401 由前端同源化修复，新识别经 `X-Forwarded-Prefix` 落子路径。强制解析公网复测（登录/文件选择/云端画布/底图/退出）与 8081 回归通过；DNS 旧 A 记录 `120.77.15.115` 待用户删除后域名正式生效。

2026-10-20 公网识别配置追修：用户公网识别 9.png 失败，定位为服务器百炼密钥未开通 `qwen3-vl-max`（404 model_not_found）且 `RSDP_RENDER_API_KEY` 迁移值无效（401）。服务器 `.env` 已改 `qwen3-vl-plus` 并回退生图密钥，仅重建后端容器；plus 对话探测、`qwen-image-3.0` 提交/轮询实测通过（见 [DEPLOY-002](tasks/DEPLOY-002-public-http.md) 追修记录与[模型配置](vision-configuration.md)）。本地密钥继续用 `qwen3-vl-max`。用户图纸的公网重新识别待用户重试验证。

2026-10-20 HTTP 上传追修：[DEPLOY-003](tasks/DEPLOY-003-http-uuid.md) 已复现公网 HTTP 缺少 `crypto.randomUUID` 导致文件选择失败。五处 UUID 调用统一增加 getRandomValues 回退；单元检查、前端构建及禁用 randomUUID 的图纸/模板/效果预览三项 UI 回归通过。已仅构建并发布前端镜像 `956c41117698-httpuuid`（其余三服务不动），公网真实登录复测文件选择生成 v4 UUID 通过，未触发识别；401 与此错误独立核对，用户最新登录日志为 200。

2026-10-20 公网部署追加：[DEPLOY-002](tasks/DEPLOY-002-public-http.md) 已以 `956c41117698` 镜像运行真实 PostgreSQL/CAD/Java/前端，备份、迁移、四服务健康检查通过。按用户选择使用 HTTP 8081，安全组放行后，公网 IP 的真实浏览器登录、商品页、画布和退出失效通过。入口为 `http://101.200.81.123:8081/`；`rooom.vip` 仍有旧 IP `120.77.15.115`，域名稳定性待删除旧 A 记录后复核。未配置 `/office/` 或 HTTPS，未迁移本地业务数据，未扩大真实识别/排版业务验收。

2026-10-20 Jenkins 启动追修：[DEPLOY-001](tasks/DEPLOY-001-jenkins-server-env.md) 已定位 `No such DSL method 'pipeline'` 为声明式插件缺失 `joda-time-api` 依赖。补装官方插件、校验 SHA256 并重启后，实际第 3 次 Jenkins 构建（main `956c411`）为 SUCCESS：预检、180 项测试（177 通过、3 跳过）、JUnit 报告发布及三个镜像构建通过。`DEPLOY=false`，部署阶段 skipped；SCM 轮询和默认参数保留。未修改流水线代码或正式应用，正式发布与 HTTPS 仍待验证。

2026-10-20 追加：[DEPLOY-001](tasks/DEPLOY-001-jenkins-server-env.md) 的 Jenkinsfile 与云服务器 `.env` 配置已交付。独立生产 Compose、前端容器及持久目录/数据库备份/显式 Spatial 迁移入口已编码；服务器 `.env` 为 `root:jenkins 0640`，Jenkins 用户预检通过。前端 build/同源静态生成、本地 clean Java 与 Linux 镜像 Java（各 163 通过、3 跳过）、三个服务器镜像构建、隔离 mock 后端的容器代理核对及文档/差异检查通过。Docker Hub 超时通过导入官方基础镜像缓存完成本轮构建，未更改全局网络配置；尚未推送、运行实际 SCM 流水线、部署正式服务或切换公网 HTTPS。
2026-10-26：[WORKSPACE-003](tasks/WORKSPACE-003-project-floors.md) 已实现创建项目、楼层规划与添加、项目/楼层改名、项目/楼层折叠切换、客户需求备注及分层上传/草稿/识别隔离；顶部项目创建直接打开弹窗。前端构建、项目楼层专项、上传入口、空间核对、MOCK 冒烟及完整 UI 工作流通过；Java 161 项中 158 通过、3 项可选数据库集成跳过。真实数据库迁移和真实两层上传集成尚未执行，详见任务记录。

2026-10-17 提交前复核：当前识别/上传/空间核对版本的 `pnpm build`、`pnpm test:design-corrections`、`pnpm e2e:recognition-progress`、`pnpm e2e:drawing-entry`、`pnpm e2e:space-review` 全部通过；三项 UI 使用隔离预览与模拟 API。后端沿用本轮 RECOG-015 的 clean test 149 通过/3 跳过记录。未新增真实模型调用，不扩大识别边界精度或业务验收结论。

2026-10-17 追加：[RECOG-015](tasks/RECOG-015-remove-unused-segformer.md) 清理完成。已删除未启用的 SegFormer 服务及 Java 接入、容器和环境配置，保留 Qwen 候选与 Java 栅格墙线核查；Java clean test 149 通过/3 跳过，Compose、文档及差异检查通过。本地后端已重启且健康检查 UP，历史分析可读取。

2026-10-17 追加：[RECOG-014](tasks/RECOG-014-space-completeness-recovery.md) 完整性回退已修复。8.png 两次真实响应回放均恢复 22 命名候选，补齐主请求 polygon 与两种错误坐标编码；保守墙线核查防止茶室吞并楼梯。151 Java 通过、3 跳过，真实响应 UI 回放及 mock 冒烟通过。8082 已重启，真实上传 API 新任务 FPA-6D94153D 已完成，21 命名候选、无轮廓格式过滤；21 项真实 API 画布回放通过，旧画布保留；候选边界仍偏差，精度未验收。RECOG-013 的其他图验证不能覆盖本图验收。

更新日期：2026-10-19。本轮将远程主分支 `4003ece` 快进合入当前 `develop`，保留本地空间、识别、上传和排版改动；状态按下表和各任务文件记录，旧任务各自保留历史基线。
本轮新增 [AUTH-001](tasks/AUTH-001-user-login.md)：数据库用户登录已编码，密码保存为 PBKDF2 加盐哈希，前端登录页使用 HttpOnly 会话，后端拦截 `/api/v1/**`。首个用户由部署环境变量引导创建。2026-10-20 补齐 `/api/auth/**` CORS 映射并把前端登录跳转改为全局路由中间件后，本地真实环境核对通过：构建、Java 154 项、匿名 401/错误密码/禁用用户拒绝、会话保持与退出失效、Playwright 真实接口端到端 8 项；正式环境数据库迁移、公网 HTTPS 和业务验收尚待执行。
2026-10-20 追加：[AUTH-002](tasks/AUTH-002-session-hardening.md) 登录加固已完成并本地验证。失败限流（2 次后验证码、5 次锁 15 分钟）、图形验证码、记住我（30 天）、滑动续期、退出即吊销；会话落库 `office_user_session` 支持多实例共享。Java 160 通过/3 跳过、构建及真实环境 Playwright 验证通过；限流计数为单实例内存、公网 HTTPS 待部署。
既有功能状态主要依据源码审阅；CAT-002 已运行 Java 测试、真实工作簿解析测试及前端构建。用户正式导入与补入后，当前数据库有 211 个商品、220 个规格；`16FMT001` 的图片说明不符问题已编码处理，用户补图及完整业务验收仍待验证。
功能状态以各任务记录为准。Qwen 接入已运行 Java 测试、前端构建及上传页模拟接口验证；本轮 RECOG-003 对一张真实 PNG 调用百炼并持久化尺寸候选，尚未完成跨图纸识别质量或真实 CAD 验收。
本轮 [GEOM-003 空间核对工作台](tasks/GEOM-003-space-review-workbench.md) 已接入实际 `/canvas` 路径，构建、专项/冒烟模拟回归与 Java 24 项测试通过；既有全工作流测试在商品详情的过时选择器处失败，未声称全部 UI 检查通过。
本轮 [FLOW-002](tasks/FLOW-002-advisory-space-review.md) 按用户最新要求取消逐空间确认门槛，复核只提示；后续任务不再沿用旧文档的全空间确认前置。其他已有代码按各任务记录标注部分实现或已编码待验证，文档验收单独记录。

## 1. 状态含义

| 状态 | 含义 |
| --- | --- |
| 待开始 | 需求已记录，尚未启动该任务 |
| 进行中 | 正在实现或检查，有明确下一步 |
| 部分实现 | 已有相关代码，但目标范围仍有缺口 |
| 已编码待验证 | 任务范围已编码，实际验证尚不完整 |
| 已验收 | 任务标准实际通过，并附环境、版本与证据 |
| 阻塞 | 明确缺少必要条件，说明解除条件 |

演示是数据/实现来源，不是完成状态。项目总体不填无统计依据的完成百分比。

## 2. 功能与工作项

| ID | 工作项 | 当前状态与证据 | 剩余工作/验收 | 优先级 |
| --- | --- | --- | --- | --- |
| UPLOAD-001 | 单张图纸上传与更换 | 已实现并通过专项验证；构建、单图上传/空间回归/冒烟 mock、Java 115 项通过；补充旧 store 实例下的真实 Nuxt 热更新上传回归通过，见[任务记录](tasks/UPLOAD-001-single-drawing.md) | 后端校验待用户启动新版 Java 生效；真实上传识别完整集成待验收 | P1 |
| UPLOAD-002 | 当前图纸进入对应空间 | 已实现；上传 ID/分析关联、未识别/后台/失败入口隔离、新图模板与家具隔离、编辑/空稿恢复，构建及专项 UI 模拟通过，见[任务记录](tasks/UPLOAD-002-drawing-canvas-handoff.md) | 当前 7.png 的真实识别与用户业务复核待验证；无新表，本次未重启服务或修改真实稿 | P0 |
| WORKSPACE-001 | 删除项目信息及专用存储 | 历史任务已编码并通过专项验证；2026-10-26 最新需求由 WORKSPACE-003 重新引入面向流程的项目/楼层，见[历史记录](tasks/WORKSPACE-001-remove-project-info.md) | 保留历史删表迁移，不恢复旧 office_project；当前模型见 WORKSPACE-003 | — |
| WORKSPACE-003 | 创建项目与多楼层工作区 | 已实现项目/需求/楼层规划、添加楼层、项目与楼层改名、折叠切换、分层上传及草稿/识别/文件隔离；构建、专项与相关 UI mock、Java 161 项（3 跳过）通过，见[任务记录](tasks/WORKSPACE-003-project-floors.md) | 真实 PostgreSQL 迁移、真实两层上传识别与业务复核待验证 | P0 |
| DOC-001 | 需求、规则、规范与进度基线 | 已验收（文档）；本轮新增文档及 README 同步 | 文档检查通过；后续业务任务按对应标准另行验收 | P0 |
| DOC-002 | PDF/PNG 空间编辑首期文档调整 | 已验收（文档）；三份 Spatial 目标文档及项目基线已调整，见 [任务记录](tasks/DOC-002-spatial-canvas-first-phase.md) | 本地链接、JSON 示例、契约一致性及差异检查通过；不代表功能实现 | P0 |
| DOC-003 | 几何、接口/存储、确认失效和真实样本附件 | 已验收（文档/契约结构）；四份附件、API schema/报文及待填样本清单，见 [任务记录](tasks/DOC-003-spatial-contract-attachments.md) | 文档链接、schema 正反用例、一致性检查通过；真实识别/SQL/业务代码未实施 | P0 |
| CAT-001 | 商品入库与可恢复上下架 | 部分实现；已有导入、预览、手工录入、审核、编辑和删除代码；商品库支持 10/20/50/100 条分页、首页/末页跳转及带清单确认的批量永久删除，模拟接口跨页操作已验证（见 [CAT-004](tasks/CAT-004-product-pagination-hard-delete.md)）；供应商工作簿可转换为预览行，见 [CAT-002](tasks/CAT-002-workbook-image-ingest.md) | 真实数据库导入、独立上下架流程、刷新持久化、推荐过滤及旧方案处理；补充批量删除真实服务验收 | P0 |
| CAT-002 | 系统导入时自动提取内嵌图片 | 已编码待补图验收；普通图、组合图、WPS 图片和合并图片区解析；`16FMT001` 锚定图的说明编码错误已纳入候选，默认不选、需人工勾选；预览可逐行排除图片并选择主图，详见 [CAT-002](tasks/CAT-002-workbook-image-ingest.md) | 用户在原端口复核并补入 `16FMT001` 两张图，核对商品详情、其他冲突图片、重复/失败路径及部署持久存储 | P0 |
| CAT-003 | 供应商商品尺寸与预览适配 | 已编码待完整验收；F 列合并说明、浮点尾数归一、包装字段、图片预览和品类码清理已完成；双宽度拆分形成 220 条规格；用户补入单行失败商品后数据库已有 211 商品、220 规格，详见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 核对缺图商品补图、价格、规格和正式导入结果；完整尺寸推荐另按 RECOMMEND-001 实施 | P0 |
| CAT-005 | 商品大图与悬停放大查看 | 已编码并完成真实商品页面浏览器检查；详情页主图局部放大镜、列表缩略图统一外框内按原图比例完整展示、悬停预览按原图比例展示整图、详情点击全屏完整预览；详情布局与白色图片背景已调整，见 [任务记录](tasks/CAT-005-product-image-gallery.md) | 用户在常用浏览器复核不同宽高比和移动端显示 | P1 |
| CAT-006 | 商品批量审核与一键全部通过 | 已编码；审核页勾选批量通过/驳回、一键全部通过（弹窗确认），后端批量接口只处理仍为待审核的商品并返回处理/跳过数量；构建、Java 184 项（含新增 4 项）、差异检查通过，见 [任务记录](tasks/CAT-006-product-batch-audit.md) | 真实数据库与运行后端上的批量审核验证，并发下跳过统计核对 | P1 |
| RECOG-003 | 图中尺寸与自动比例候选 | 后端候选/确认链路已实现并通过专项验证（48 项 Java、构建、专项 UI 模拟、一次性 DB 确认链路、真实 PNG 候选），见[任务记录](tasks/RECOG-003-dimension-calibration.md)；2026-10-21 按需求移除前端“图中尺寸自动校准”面板，空间核对统一使用两点校准，后端接口保留 | 真实 PNG 候选待用户确认；更多 PNG/PDF 的质量验收，非标准/模糊尺寸仍需人工校准 | P0 |
| RECOG-001 | CAD 真实识别链路 | 已编码待验证；上传页 → Java `/api/v1/cad/parse` → CAD 服务 | 真实办公 DWG/DXF、目标选择、单位、未命名空间和失败路径验收 | P0 |
| RECOG-002 | PDF/PNG 真实识别 | 已接入 Qwen-VL，当前默认切换为 `qwen3-vl-max`，后端环境密钥、PDF 选页渲染、候选/预览适配及明确错误已编码；[接入任务](tasks/RECOG-002-qwen-vl-integration.md) | 真实百炼调用与附件 D 原始自动质量待验收；像素比例校准及人工空稿按 Spatial 任务继续推进 | P0 |
| RECOG-011 | PNG 墙网拓扑识别 | 第一阶段已实现：Qwen 主请求只保留房名/功能语义，封闭空间由墙体候选、房名锚点、门洞有限闭合和连通区域多边形化生成；语义-only 回归通过，见[任务记录](tasks/RECOG-011-png-wall-topology-pipeline.md) | 当前使用 Qwen 初始候选 + Java 栅格墙线核查；斜墙/开放分区和跨图纸真实质量待验收 | P0 |
| RECOG-013 | 语义切换空间丢失修复 | 部分实现；同名部分匹配、主请求文字补位、未命名墙网候选及女卫碎片修复；150 项 Java（147 通过/3 跳过）、构建/UI mock、一次真实模型与修复回放通过，见[任务记录](tasks/RECOG-013-recognition-pipeline-recovery.md) | 新后端重新识别；茶水/洗手边界及跨图纸完整精度验收 | P0 |
| RECOG-012 | SegFormer-B1 墙体分割服务 | 已按用户要求撤销并移除，见[历史记录](tasks/RECOG-012-segformer-wall-segmentation.md)及[清理任务](tasks/RECOG-015-remove-unused-segformer.md) | 不再作为运行依赖或待接入任务 | — |
| RECOG-007 | 底图先行与识别提速 | 已实现后台任务、预览提前发布、两路模型并行、真实阶段/耗时、同图同页同参数标准图缓存；Java 与前端专项模拟回归通过，见[任务记录](tasks/RECOG-007-progressive-recognition.md) | 未重启用户服务；真实模型耗时改善及跨图纸质量待实测；队列/缓存限单 Java 进程 | P0 |
| RECOG-008 | 细双线隔墙与同名房间纠偏 | 部分实现；7.png 真实缓存重放从 0 个增至 9 个封闭轮廓，6 间主管室一一归属；保留 5.png 既有四处恢复，未证实轮廓逐空间降置信，见[任务记录](tasks/RECOG-008-thin-partitions-and-repeated-labels.md) | 茶水/洗手/女卫及开放分区仍未解决；正式上传、完整边界质量及跨图纸验收未完成；旧稿不自动覆盖 | P0 |
| RECOG-009 | 旧稿覆盖与隔间碎片修复 | 部分实现；保护会议室完整来源不被截短旧框覆盖，提供可撤销的来源恢复；两份 7.png 缓存恢复女卫/电梯厅，共 11 个轮廓；构建、纯函数和专项 UI mock 通过，见[任务记录](tasks/RECOG-009-contour-history-and-partitions.md) | 茶水区、洗手间、开放分区/未命名仍未可靠恢复，全图准确要求未完成；新后端、新识别及真实上传验收待执行 | P0 |
| RECOG-010 | 越界输出导致中央办公区丢失 | 已实现失败响应回归；FPA-5C32CDA8 原始中央候选 X=1175 被过滤，V7 恢复中央办公区及六处主管室，保留其他既有墙线恢复，见[任务记录](tasks/RECOG-010-missing-open-office.md) | 正式新识别待新版后端启动；中央邻接功能分界仍为估计，茶水/洗手/未命名与全图准确性未验收 | P0 |
| GEOM-001 | 识别场景与画布准确对应 | 部分实现；新增 `SpatialGeometryService`/`SpatialCalibrationService`、DesignScene 存储与 API，修复 `confirmRooms` 保留 polygon 并按多边形算面积；既有 PNG 记录使用 mock，不能算真实识别验收 | 统一相机/底图尺度、来源/设计副本；后端数据库集成测试；真实样本验证 | P0 |
| GEOM-002 | 无限画布与空间尺寸双向编辑 | 部分实现；新增 `SpatialCanvas.vue`、前端 spatial store、矩形宽深/顶点编辑与面积联动；旧 `CanvasBoard.vue` 仍保留 | 无界世界坐标、L 形/多房间编辑、撤销/重做与恢复、前端 409 冲突合并 | P0 |
| GEOM-003 | 工装平面图空间核对工作台 | 部分实现；实际 `/canvas` 三栏核对、底图/轮廓同变换、毫米编辑/顶点拖动/撤销、图片校准、复核仅提示与前后端排版条件；[专项与模拟证据](tasks/GEOM-003-space-review-workbench.md) | 真实图纸原始质量与人工核对验收、数据库集成、新 DesignScene 确认快照/409 队列；不能以示例当真实验收 | P0 |
| GEOM-004 | 空间模板与布局复用 | 已实现并通过专项验证；57 项 Java、构建、模板/空间/尺寸 UI mock、冒烟、真实 PostgreSQL 创建/读取/幂等通过，见[任务记录](tasks/GEOM-004-space-template.md) | 当前以空间轮廓模板为基础，建筑层/嵌套关系的兼容排版仍按原任务推进；真实识别质量独立验收 | P0 |
| GEOM-005 | 单空间设计视图隔离 | 已实现并通过专项验证；构建、单空间/空间核对/模板 UI mock 及冒烟通过，见[任务记录](tasks/GEOM-005-single-space-view.md) | 只隔离视图，不改变几何/家具归属；真实识别质量与复杂约束排版仍独立推进 | P0 |
| FLOW-002 | 空间复核改为提示 | 已实现并通过专项验证；50 项 Java、构建、空间/尺寸 UI mock 与冒烟通过，真实 DB 一次性稿保存/恢复通过；[任务记录](tasks/FLOW-002-advisory-space-review.md) | 当前工作台无逐空间确认门槛；完整 DesignScene 历史确认 API 未重写，真实识别质量独立验收 | P0 |
| FLOW-003 | 方案确认工作台 | 已编码首版；参考 PDF 完成楼层→空间类别→具体空间分层、类别折叠、空间切换、真实商品图片/户型图/家具布局/效果预览映射，详情板按截图采用蓝色需求、排版平面效果、合并重复商品的 4 列网格、3 个效果图槽位和黄色客户批注五列布局；移除重复顶部统计卡，详情改为居中弹窗，排版详情复用前一步家具坐标、尺寸、旋转和图例，支持单空间、区域和整体方案确认及页面滚动；`pnpm build` 通过，见[任务记录](tasks/FLOW-003-proposal-confirmation-workbench.md) | 正式方案快照、批注保存和后端核验待接入 | P0 |
| FLOW-001 | 空间/排版/方案确认及版本失效 | 进行中；新增 5 个 DesignScene 路由、保存/校准/确认版本冲突与确认失效，见 [任务记录](tasks/FLOW-001-spatial-confirmation.md) | 幂等回执完整实现、数据库集成测试、端到端闭环用例、全稿删除/关系/质量处理 | P0 |
| LAYOUT-001 | 独立家具排版 | 部分实现；新单位模块复用手动家具图例并使用毫米规格，可不绑定商品；旧商品排版保留兼容 | 完整布局需求模型、异形边界/碰撞/通道校验、人工调整与版本确认 | P1 |
| RECOG-004 | 原图墙线约束 | 已实现并完成当前底图专项核对；封闭区域墙线校正、家具/门洞补线隔离与异常保留，见[任务记录](tasks/RECOG-004-local-wall-contours.md) | 用户更新后端并新识别核对；不覆盖旧稿；待整图/跨样本与正式上传链路验收 | P1 |
| RECOG-005 | 房名定位与同图轮廓继承 | 部分实现；新真实请求下棋牌室自动落到右侧；同项目/同图人工轮廓可在新批次导入继承，Java 108 项、构建、纯函数/UI mock 和真实历史只读查询通过，见[任务记录](tasks/RECOG-005-label-grounding-and-correction-reuse.md) | 开放分区纯模型仍不准确；未完成跨图纸和新后端正式上传完整集成验收，不能将人工继承当自动识别准确率 | P0 |
| RECOG-006 | 玻璃立面办公图轮廓修正 | 部分实现；5.png 九处人工修正已保存至 revision 489，财务室/现有比例/原始识别保留；V4 增补顶部玻璃、短柱及可见门扇闭合，真实缓存重放沿墙接受由 1 个增至 4 个，Java 109 项通过，见[任务记录](tasks/RECOG-006-glazed-office-contours.md) | 开放分区与洗手间仍不能可靠自动闭合；新后端正式上传、跨图纸及用户业务验收未完成；当前人工稿不计自动准确率 | P0 |
| GEOM-008 | 按用户标注修正开放分区 | 本批次棋牌室与开放分区/邻室共十处已修正；校准后保存至 revision 458，保留现有比例 12.098729 mm/px，真实 API 回读及 15 空间几何/无重叠检查通过，见[任务记录](tasks/GEOM-008-annotated-open-zones.md) | 用户刷新后重新生成模板；本次为人工修正，新识别继承逻辑见 RECOG-005 | P1 |
| GEOM-007 | 空间轮廓自由变换 | 已实现，构建、空间核对及自动尺寸专项 UI mock 通过；八点拉伸、等比缩放、平移、单边推拉及取消/撤销，见[任务记录](tasks/GEOM-007-free-contour-transform.md) | UI mock 与真实图纸/数据库验收分别记录 | P1 |
| GEOM-006 | 沿墙轮廓修正 | 已实现，构建、Java 97 项及空间核对 UI mock 通过；完整墙线提示、补转角及重描/退点/取消/撤销见[任务记录](tasks/GEOM-006-wall-contour-correction.md) | 截图中真实房间边界未代替用户修改，真实识别质量待验收 | P1 |
| GEOM-010 | 删除不参与排版的空间 | 已实现并通过构建、专项 UI mock；删除关联内容、撤销、末空间删除后刷新保持空稿及保存状态，见[任务记录](tasks/GEOM-010-delete-spaces.md) | 真实数据库删除链路与用户业务验收待补充 | P1 |
| GEOM-011 | 单空间建筑约束标注与避障 | 已实现人工几何标注首版；单空间聚焦、柱/门洞扇形净空/禁放区、净面积、模板、前后端实际轮廓避障及专项 UI mock 通过，见[任务记录](tasks/GEOM-011-space-obstacles.md) | 不含自动识别和任意多边形；真实数据库与用户原图业务验收待补充 | P1 |
| GEOM-014 | 空间合并与剔除（轮廓布尔） | 已实现并通过构建、布尔纯函数测试与专项 UI mock（合并/剔除/拒绝/撤销），见[任务记录](tasks/GEOM-014-space-merge-subtract.md) | 结果为孔洞/多块按定稿拒绝；真实数据库链路与用户业务验收待补充 | P1 |
| GEOM-015 | 窗与开口标注 | 已实现并通过构建、纯函数测试、专项 UI mock 与后端校验用例，见[任务记录](tasks/GEOM-015-window-openings.md) | 定稿为标注型约束（不扣面积/不避让）；窗台高与窗下品类规则待业务确认；真实数据库链路与用户业务验收待补充 | P1 |
| GEOM-017 | 逐点描交互探索（已完全回退） | 全部改动按用户决策回退至 origin/main 描点行为，见[任务记录](tasks/GEOM-017-sketch-curves.md) | 曲线/橡皮筋需求若重提可参考卡内方案存档 | P3 |
| GEOM-016 | 家具布置区（多布置区） | 已实现（2026-10-30 定稿为多布置区）；绘制/设主/启停/显隐/移动/顶点/删主升主/量测、前后端等价校验、纯函数 14 组与专项 UI mock 通过，见[任务记录](tasks/GEOM-016-layout-boundary.md) | 真实数据库保存回读与真实业务排版验收待补充；C 侧布局引擎联调 | P1 |
| GEOM-018 | 设施/点位标注 | 已实现（2026-10-30 新增）；点/线/占用三型标注、人工净空、不扣净面积不阻断，纯函数 7 组与专项 UI mock 通过，见[任务记录](tasks/GEOM-018-spatial-facilities.md) | 真实数据库链路与业务验收待补充；设施提示接入排版面板属 C 侧 | P1 |
| LAYOUT-002 | 单位模块复用办公家具图例 | 已实现并通过专项验证；28 个图例/9 类同源、毫米规格、画布线稿、构建及专项/模板/单空间 UI mock 和冒烟通过，见[任务记录](tasks/LAYOUT-002-shared-furniture-legend.md) | 图例布局可不绑定商品；复杂约束排版和正式商品匹配独立推进 | P1 |
| LAYOUT-003 | 一键多排与空间利用 | 已实现并通过专项验证；默认横纵多排、可调间距、轮廓/防重叠与参数恢复；构建/算法/专项及模拟回归通过，见[任务记录](tasks/LAYOUT-003-auto-multi-row.md) | 启发式排布不保证全局最优；门窗/通达性与完整服务端方案核验独立推进 | P1 |
| LAYOUT-004 | 自动排版入口与结果反馈 | 已实现并通过专项验证；旧 row/3件恢复后主按钮直接自动多排，当前/预估/实际反馈；构建、专项/图例与 MOCK 冒烟通过，见[任务记录](tasks/LAYOUT-004-apply-auto-layout.md) | 本轮入口与反馈修正；复杂方案与真实业务验收仍独立推进 | P1 |
| LAYOUT-005 | 自定义模块与逐件添加 | 已实现并通过专项验证；本机模块目录、毫米规格/独立快照、点击放置/旋转/拦截/撤销；构建、专项/既有 UI mock 和冒烟通过，见[任务记录](tasks/LAYOUT-005-custom-and-manual-modules.md) | 团队共享目录、自由家具图形和完整建筑约束另行推进；真实服务集成未扩大验收 | P1 |
| LAYOUT-006 | 单位模块删除 | 已实现并通过专项验证；选中按钮/快捷键、确认失效、撤销/刷新与目录独立删除恢复；构建、专项/自定义回归及 MOCK 冒烟通过，见[任务记录](tasks/LAYOUT-006-delete-modules.md) | 只删除当前可见实例，目录删除保留旧家具；真实服务集成不扩大验收 | P1 |
| LAYOUT-007 | 常用排布与间距初值 | 已实现并通过专项验证；默认 0 mm 列间距、左右留缝、面对面/错位排/中间通道及对排中缝，见[任务记录](tasks/LAYOUT-007-arrangement-presets-and-spacing.md) | 真实建筑通道、门窗和服务端方案核验仍按 LAYOUT-001 推进 | P1 |
| LAYOUT-008 | 家具多选与整组操作 | 已实现并通过专项 UI 模拟；Shift/Ctrl 点选、框选、组移/批删、碰撞/越界拒绝及一次撤销，见[任务记录](tasks/LAYOUT-008-multi-furniture-selection.md) | 建筑门窗/通行区域及真实服务业务验收仍按 LAYOUT-001 推进 | P1 |
| LAYOUT-009 | 排版页直接切换空间 | 已实现并通过专项验证；下拉/前后选择、状态计数、URL/刷新、家具与确认保留、逐件放置取消；构建、单空间/自动排版/模板/效果预览 UI mock 通过，见[任务记录](tasks/LAYOUT-009-design-space-switcher.md) | 继续保持单空间隔离；本轮未扩大真实数据库集成或复杂建筑约束验收 | P1 |
| LAYOUT-010 | 产品样式与专用排布规则 | 已编码待验收；图例组合固定列数，L 型对坐为 3×4/12 件、两组间隔 600mm，上排镜像转角并显示座席线稿；新应用写入 v3 规则版本，见[任务记录](tasks/LAYOUT-010-product-style-arrangement-rules.md) | 按用户要求未启动前端，实际界面视觉回归未运行；屏风共享线段、座席活动区和后端确认/推荐样式版本仍待接入 | P1 |
| LAYOUT-011 | 按空间用途分流排版 | 已实现并通过专项验证；会议室默认会议桌和单桌居中，外围 1200mm 预留，开放办公区继续使用职员桌多排，见[任务记录](tasks/LAYOUT-011-space-layout-profiles.md) | 会议桌座席/椅子联动、门口通道和服务端确认仍待实现 | P1 |
| LAYOUT-012 | 排版画布尺寸标注 | 已编码待验证；单空间画布显示空间外接尺寸、模块实际宽深、每排总长及非零排间净距，标注与毫米世界坐标同步，见[任务记录](tasks/LAYOUT-012-layout-dimensions.md) | 缺少 Playwright 浏览器时无法完成视觉回归；墙段逐边和活动区尺寸待后续几何任务 | P1 |
| LAYOUT-013 | 排版面板操作体验 | 已编码待验证；侧栏调整为模块/参数/排布三步导航，尺寸和间距先于排布选择，参数变化会让已应用方案回到待重新应用状态，见[任务记录](tasks/LAYOUT-013-layout-panel-ux.md) | 缺少 Playwright 浏览器时无法完成不同窗口宽度的视觉回归 | P1 |
| LAYOUT-014 | 用途匹配与分组排版 | 已实现并通过构建、算法、专项 UI mock；会议桌椅/目标人数、自动推荐、保留手动家具、入口禁放区、预览与应用一致及撤销，见[任务记录](tasks/LAYOUT-014-layout-planning-review.md) | 连续通道、真实建筑约束与真实服务验收待补；启发式不保证全局最优 | P1 |
| LAYOUT-015 | 严格按四张参考图排布 | 已实现，构建、全部 10 预设坐标/镜像/间距与专项 UI mock 通过；T 型 12 件、C 型 8 件、十字 16 件，座席朝向与整组保留见[任务记录](tasks/LAYOUT-015-exact-reference-presets.md) | 用户实际空间待核对；旧布局需显式重新应用，不静默改写 | P1 |
| LAYOUT-016 | 按场地自适应参考样式 | 已实现，构建、算法与专项 UI mock 通过；样式不变，数量/方向按轮廓与障碍计算，同尺寸矩形测试 6 件上限修正为 36 件，见[任务记录](tasks/LAYOUT-016-space-adaptive-reference-layout.md) | 用户纠正覆盖 LAYOUT-015 固定示例件数；真实建筑与全局最优不扩大验收 | P1 |
| RECOMMEND-002 | 单位模块向下兼容尺寸推荐 | 已实现并通过专项验证；精确毫米/三档/旋转、真实 SKU、同规格批量选择与独立快照；Java 97 项、构建、专项及相关 UI mock 通过，见[任务记录](tasks/RECOMMEND-002-module-size-matching.md) | 当前本地库商品均待审核；真实服务集成、品类/结构与整体方案另行验收 | P1 |
| RECOMMEND-001 | 已确认排版后的真实商品推荐 | 部分实现；模块宽深匹配及真实变体选择已按 RECOMMEND-002 落地，旧候选入口仍兼容 | 完整品类/结构与使用约束、方案确认、旧清单失效/复核及真实服务集成 | P1 |
| RENDER-001 | 已确认方案效果图 | 正式已确认商品方案生成仍待开始；独立概念预览见 RENDER-002 | 正式方案条件核验、商品外观资源与精确对应验收 | P2 |
| RENDER-002 | 单空间二维概念效果预览 | 已实现，Java 97 项、构建、专项模拟 UI/冒烟及两次真实百炼生图通过；独立 DB 验证去重、图片保存与修改后历史标记，见[任务记录](tasks/RENDER-002-single-space-preview.md) | 合成布局仅验证真实链路；模型仍可能补充家具/门窗，概念图不替代精确对应验收；完整工作流仍在旧商品详情选择器失败 | P1 |
| QA-001 | 分层验证与持续检查 | 部分实现；已有构建、UI E2E、Java/CAD 测试入口 | 增加几何/流程/商品测试和真实集成样本；统一脚本与 CI；处理金样本未执行判定 | 随各项实施 |

P0 是后续设计流程的数据与业务基础；P1 依赖空间与确认基础；P2 依赖确认方案。
优先级是实施顺序，不表示 P2 不在目标范围。

## 3. 关键源码证据

- 商品：[接口](../backend/src/main/java/com/rsdp/officeproduct/importer/controller/ProductImportController.java)、[审核服务](../backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductAuditService.java)、[商品页](../frontend/app/pages/products/index.vue)。
- 识别与导入：[上传页](../frontend/app/pages/index.vue)、[API 客户端](../frontend/app/composables/useOfficeApi.ts)、[vision 客户端](../backend/src/main/java/com/rsdp/officeproduct/importer/service/VisionRecognitionClient.java)。
- 当前画布模型：[类型](../frontend/app/types/office.ts)、[空间核对](../frontend/app/components/OfficeCanvas/SpaceReview.vue)、[统一渲染](../frontend/app/components/OfficeCanvas/SpatialCanvas.vue)、[坐标/几何适配](../frontend/app/utils/spatial/review.ts)；单空间排版保留 [CanvasBoard](../frontend/app/components/OfficeCanvas/CanvasBoard.vue)。
- 排版：[算法](../frontend/app/utils/layout.ts)、[排版侧栏](../frontend/app/components/OfficeCanvas/DesignLayoutSidebar.vue)、[商品候选侧栏](../frontend/app/components/OfficeCanvas/DesignSidebar.vue)。
- 测试：[UI 工作流](../frontend/scripts/e2e-workflows.mjs)、[CAD 金样本](../backend/cad-parser/tests/RsdpCadParser.Tests/GoldenFileTests.cs)。

## 4. 当前最需要解决的差距

1. 新增 Spatial Scene 模块已实现统一坐标变换与多边形面积联动，但旧 `CanvasBoard.vue` 与 `pixelToMeter` 仍保留；需逐步迁移或下线旧入口。
2. 后端 DesignScene API 与表已落地，但幂等回执、数据库集成测试、全稿删除/关系/质量处理尚未完成。
3. `/canvas` 已支持多空间/L 形顶点编辑、撤销与本地刷新恢复的模拟回归；仍使用兼容 Office Canvas 保存，完整 DesignScene 写队列、整体确认版本快照和 409 冲突合并 UI 待补齐。
4. 单位模块已可使用未绑定商品的共享家具图例；完整的布局需求、约束校验与商品匹配/版本约束仍需推进。
5. 商品 status 与 auditStatus 分开存在，但审核不是上下架；不能凭已有 approve/reject 接口认为上下架完成。
6. 图片/PDF 已接入 Qwen，未配置密钥明确报错；旧演示记录仍为演示。新候选使用来源像素坐标；自动尺寸校验后仍待用户确认比例，一张真实 PNG 已获得一致参考，跨样本质量未验收。

## 5. 建议执行顺序

用户当前首期重点是 PDF/PNG 解析到可调整的无限画布，见 [Spatial 实施方案](../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)。
按四份附件和 schema，先补齐 [GEOM-001](tasks/GEOM-001-scene-canvas-contract.md) 来源/设计与存储，贯通 FLOW-001 的单房间保存/确认闭环；同期启动 RECOG-002 的少量真实样本能力验证，再扩展 GEOM-002 多房间/异形编辑。
空间编辑、校准、撤销/重做与保存恢复均属于首期闭环；fixture 可支持开发，真实 PDF/PNG 识别必须另行验收。
CAT-001 可独立开发；CAD 继续复用已有服务，真实 CAD 统一验收在首期之后推进。
随后完成完整 FLOW-001、LAYOUT-001、RECOMMEND-001，最后 RENDER-001。
每项业务任务同步补 QA-001 对应验证，不把所有验证留到最后。

未排定日期和工期；按任务实际完成与依赖更新，而非提前承诺进度。

## 6. 验收记录

| 任务 | 代码/文档版本 | 环境与验证 | 结论 |
| --- | --- | --- | --- |
| DOC-001 | 基于 `3f8f1eb` 工作区新增规则与开发文档 | 10 个规则/文档文件、60 个本地链接、代码块/换行/空白、业务工作项与 `git diff --check` 检查通过，见任务记录 | 已验收（文档） |
| CAT-002 | 基于 `2413a93` 的本地工作区，见 [任务记录](tasks/CAT-002-workbook-image-ingest.md) | Java 测试、指定真实工作簿解析测试、前端构建与 `git diff --check` 通过；真实服务预览 211 行有效、205 张图，全部跳过确认 0 写入 | 已编码待集成验收；图片目录已由用户清空，旧映射 JSON 仍在 |
| CAT-003 | 本地工作区，见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 2026-10-15 双宽度拆分后 Java 18 项测试（含真实工作簿只读解析）、前端构建和 `git diff --check` 通过；此前 8083 预览和旧 UI 工作流记录见任务文件；本轮未启动服务或运行数据库迁移 | 已编码待正式导入验收；未替用户写入商品 |
| CAT-003 单行失败修复 | 本地工作区，见 [任务记录](tasks/CAT-003-supplier-size-import.md) | 2026-10-15 只读核对数据库日志与商品计数；Java 19 项测试通过，含无图回归测试；未启动服务或代替用户导入 | 已修复空图片更新；待用户重启原服务后补入 `16FMT001` 验证 |
| CAT-002 图片说明不符修复 | 本地工作区，见 [任务记录](tasks/CAT-002-workbook-image-ingest.md) | 2026-10-15 只读核对真实工作簿 A15 图片锚点与说明、商品图片资产；Java 20 项测试通过，含真实工作簿解析；前端构建通过；未启动服务或代替用户补图 | 待用户在原端口勾选两张候选图并仅更新该商品，核对详情 |
| DOC-002 | 2026-10-13 工作区；三份 Spatial 文档及基线同步 | 10 个 Markdown 文件的本地链接/锚点、4 个 JSON 示例、校准示例算术、代码块/空白与 `git diff --check` 通过；业务测试未运行 | 已验收（文档）；功能仍待实现/验证 |
| DOC-003 | 2026-10-14 `2413a93` 工作区；四份开发附件及 schema | 13 个 Markdown/113 个链接，JSON Schema 自检与 20 个正反报文、示例算术、样本 NOT_RUN 标记及差异检查通过；未运行真实识别/迁移/业务测试 | 已验收（文档/结构）；功能尚未实施 |
| GEOM-001/FLOW-001 | 当前工作区；新增 spatial 存储/API、前端画布 | `mvn -f backend/pom.xml test` 通过；`pnpm build`（frontend）通过；数据库集成与端到端闭环用例未运行 | 已编码待验证；未声称已验收 |
| RECOG-002 Qwen 接入 | 2026-10-14 工作区 | Java 20 项测试通过（含新增 10 项 Qwen/图片/PDF 测试）；前端构建、`pnpm e2e:vision` 通过，接口全部模拟 | 请求与适配验证通过；真实百炼调用和识别质量待验收 |
| GEOM-003 空间核对 | 2026-10-14 工作区 | 构建、`e2e:space-review`、`e2e:vision`、MOCK 冒烟、Java 24 项通过；全工作流因商品详情旧选择器失败；见任务记录 | UI/几何策略模拟验证通过；真实识别与数据库闭环未验收 |
| RECOG-003 自动尺寸 | 2026-10-14～15 工作区 | Java 48 项、构建、尺寸/空间/上传专项 UI mock 通过；真实 PNG 获两条一致参考，最大偏差约 0.10%；一次性 DB 副本确认持久化/幂等/过期拒绝通过并清理 | 已实现并完成专项验证；用户真实图纸未确认，跨样本质量待验收 |

| FLOW-002 复核提示 | 2026-10-15 工作区 | 构建、Java 50 项、空间/尺寸专项及 MOCK 冒烟通过；真实 DB 副本未逐个确认/重叠可保存并恢复 design，未知比例/自交拒绝，测试记录已清理 | 本轮范围验证通过；复核不拦截，无人工确认伪造；原始识别质量未扩大验收 |

| GEOM-004 空间模板 | 2026-10-15 工作区 | Java 57 项、构建、模板/空间/尺寸专项、MOCK 冒烟通过；真实 PostgreSQL 临时模板的异形/派生面积/来源版本/幂等/项目作用域检查通过并清理 | 本轮模板保存与复用范围验证通过；未声称完整建筑识别/约束排版或全工作流通过 |

| GEOM-005 单空间视图 | 2026-10-15 工作区 | 前端构建、单空间/空间核对/模板专项和 MOCK 冒烟通过；人工查看负坐标 L 形单空间截图 | 本轮视图隔离范围验证通过；整层数据保留；未扩大真实识别/建筑约束排版验收 |

| LAYOUT-002 单位模块图例 | 2026-10-15 工作区 | 构建、家具模块/单空间/模板专项及 MOCK 冒烟通过，人工查看同源线稿截图 | 本轮共享目录/毫米规格/图例渲染与恢复验证通过；复杂排版约束与正式推荐另行推进 |

| LAYOUT-003 一键多排 | 2026-10-15 工作区 | 构建、算法独立检查、自动多排/图例/单空间 UI mock、MOCK 冒烟及截图检查通过；同尺寸模拟房间为 6 件/3 排 | 本轮多排与间距/轮廓保护通过；未替换真实用户布局，不代表建筑层通达性或完整方案验收 |

| LAYOUT-004 自动应用入口 | 2026-10-15 工作区 | 构建、旧 row/3件恢复专项、图例 UI mock、MOCK 冒烟及截图检查通过；同数量移动和视口操作可见性检查 | 主按钮不再受旧单排限制，当前/预估/实际反馈区分；未替换用户真实布局 |

| LAYOUT-005 自定义与逐件添加 | 2026-10-15 工作区 | 构建、自定义/手动放置专项、自动排版/图例/单空间回归、算法检查及 MOCK 冒烟通过；已查看模拟截图 | 本机目录/实例快照及逐件点击范围通过；云端 JSON 恢复仅 mock 证据，未改用户真实布局 |

| LAYOUT-006 模块删除 | 2026-10-15 工作区 | 构建、删除专项、自定义 UI mock、MOCK 冒烟、选中删除截图与文档检查通过 | 画布/目录删除及各自撤销通过，其他空间/实例快照保留；真实服务未新增验收 |

| 远程主分支合并 | 2026-10-15，`2413a93` → `5cd00f8`，快进合入 4 个商品相关提交 | 本地未提交工作已备份并恢复，处理 4 处冲突；250 个原本地改动路径逐项核对，差异仅在远程重叠文件；前端构建、Java 75 项、删除/自定义专项 UI mock 与差异/文档链接检查通过；完整工作流仍在商品详情旧 button 选择器超时 | 合并完成；保留合并前 stash 备份与未提交状态，未推送、未运行数据库迁移或重启业务服务；不扩大真实业务验收 |

| 本地分批提交 | 2026-10-15，基于 `5cd00f8` 按依赖拆分空间后端、识别校准、模板后端、手动布局、画布集成、文档和截图 | 前三批后端快照分别通过 Java 34 项、68 项及模板专项 7 项；手动布局与画布集成各自构建通过；画布快照的自动排版算法、自定义/删除模块 UI mock 通过；契约 schema 自检及 15 个正例/5 个反例、38 份 Markdown 的 225 个相对链接检查通过 | 仅本地提交；未运行数据库迁移、未重启用户服务或执行真实商品/识别操作；完整工作流旧商品详情选择器问题仍保留 |

RECOG-004（2026-10-16）：Java 全量 102 项通过；同一真实 PNG 加缓存的真实 Qwen 初次输出执行墙线校正，三间总经办叠加专项核对通过（7 个目标候选采用 6 个，茶水间归属冲突保留）；配置、本地链接及差异检查通过。未重跑正式上传/数据库链路或跨图纸验收，见[任务记录](tasks/RECOG-004-local-wall-contours.md)。

业务验收记录尚未建立，本表没有将源码存在标为已验收。

GEOM-008（2026-10-16）：真实画布 API 保存/回读 revision 452；五处无自交和邻室重叠，无关空间、来源几何与比例证据保留；Java 102 项通过。未完成用户浏览器刷新核对，不扩大为新识别质量验收。

GEOM-008 休闲区追修：当前来源已换为 `FPA-38D532FB`；仅按新红框修正休闲区并保存画布 revision 453，真实 API 回读确认无关空间/对象与来源单位保留，实际前端几何模块校验无自交、无邻室重叠。人工修正与自动识别质量分别记录。

RECOG-005 / GEOM-008（2026-10-16）：新的真实百炼请求验证棋牌室独立定位 + 沿墙轮廓；开放分区仍未通过纯自动质量验收。Java 108 项、前端构建、轮廓继承纯函数与空间 UI mock 通过；实际 MyBatis/生产服务只读查询同图历史校正。当前人工修正最终保留用户新校准比例保存 revision 458，全体 15 空间有效且无两两重叠；未重启用户后端，详见任务记录。

远程主分支合并（2026-10-17）：本地 `b5e35a1` 与 `origin/main` 的 `953dc49` 合并为 `23eebaa`，保留空间删除、轮廓识别、移除项目资料和单图上传修复，并引入常用排布/间距及家具多选。处理 store 与进度文档冲突；两项 UI 测试改为允许空工作区初始化，不恢复自动示例填充。前端构建、排版算法、自动排版/多选/删除/空间核对 UI mock 通过；Java 共 115 项，112 通过、3 项数据库集成用例未配置而跳过。恢复未提交空间切换改动后再次构建，单空间/多选/自动排版 UI mock 通过；相关链接及差异检查通过。空间切换任务编号调整为 LAYOUT-009，避免与远程 LAYOUT-007 重复。保留备份分支与 stash；未推送、未重启业务服务、未执行真实数据库迁移或识别验收。

FLOW-003 排版平面预览（2026-10-20）：缩略图与大图共用实际空间/家具坐标和排版页家具线稿，修正简化色块及不同窗口下错位，放大查看区域。前端构建与专项 UI mock（异形、负坐标、旋转/镜像、三种窗口宽度）通过；真实业务验收待用户核对，见[任务记录](tasks/FLOW-003-proposal-confirmation-workbench.md)。

LAYOUT-009 核对页进入排版修复（2026-10-20）：入口按当前选中空间 ID 进入，不再固定优先第一个办公空间。构建、独立入口 UI mock 与空间核对回归通过；单空间旧全量脚本存在断言/按钮不匹配且未通过，详见[任务记录](tasks/LAYOUT-009-design-space-switcher.md)。未扩大真实业务验收。

LAYOUT-014（2026-10-20）：用途/模块匹配、会议桌椅与真实座位缺口、分组保留手动家具、自动推荐、入口禁放矩形、拉椅区与不落盘预览已实现。构建、三组算法、排版/空间入口/方案平面 UI mock 通过；Java 全量 157 项（154 通过、3 跳过），169 个相关本地文档链接及差异检查通过。正式业务服务未重启，真实布局保存与现场连续通路仍待验收，见[任务记录](tasks/LAYOUT-014-layout-planning-review.md)。

LAYOUT-015（2026-10-20）：按用户四张参考图固定一字/L 型职员台、一字/L 型屏风的 10 种完整组合；T 型 12 件、C 型 8 件、十字型 16 件，修正方向/镜像/座席，组内紧贴、净距默认 1000/1600/600/900mm。职员预设不再走通用推荐或按目标人数截断；新规则 `2026-10-reference-v4`，旧布局需显式重新应用。构建、全部参考坐标/图例契约/排版算法/规划函数，以及参考图例、桌椅流程、方案平面 UI mock 通过；175 个本地链接和差异检查通过，详见[任务记录](tasks/LAYOUT-015-exact-reference-presets.md)。真实用户空间与建筑约束不扩大验收。

LAYOUT-016（2026-10-20）：用户明确图例只规定样式，实际数量按场地排布。已使用 `2026-10-adaptive-reference-v5` 按最小单元重复、横纵比较与整单元避障，修正固定 6 件等上限；同截图尺寸的矩形模拟为 36 件（实际仍按用户轮廓/障碍计算）。构建、自适应/规划/样式算法、全部 10 样式与专项 UI mock、方案平面回归及 179 本地链接/差异检查通过，详见[任务记录](tasks/LAYOUT-016-space-adaptive-reference-layout.md)。

LAYOUT-017（2026-10-20）：取消空间用途对家具排版的硬限制，会议室只作建议；去除重复产品样式入口，新增独立屏风模块，按模块派生兼容排布，选择不自动切页。其他家具支持单件居中/按场地与目标件数，前后端确认按实际主模块计算。构建、四组算法与四组 UI mock 专项通过；真实服务保存待验证。详见[任务记录](tasks/LAYOUT-017-module-selection-and-space-advice.md)，替代 LAYOUT-014 的会议室模块限制。

LAYOUT-017 验证补记：Java 158 项（155 通过、3 项数据库环境未配置跳过），187 个文档链接及差异检查通过；最终构建/规划算法/家具选择 UI 回归通过。

LAYOUT-018（2026-10-20）：增加实际四边离墙和横纵组间净距，预览/应用/拖动共享毫米测量；补齐 L/圆角 L/屏风及主管高管桌的座席示意；支持每次 90° 旋转视图、复位、旋转后拖动及尺寸显隐。构建、测量/相机与规划算法、参考尺寸/10样式/家具选择/自适应/桌椅规划/方案平面专项 UI mock 通过；已人工核查截图，修正密集标签重叠和旋转文字裁切。后端未改、未操作真实业务布局，详见[任务记录](tasks/LAYOUT-018-reference-dimensions-and-view-rotation.md)。

LAYOUT-019（2026-10-20）：职员圆角 L 桌并入 L 型屏风卡位。`desk-l-rounded` 转为目录隐藏（旧实例保留），L 型单排/对坐开放给 L 型屏风卡位，共六种排布、默认 L 型单排；职员 L 桌与既有布局规则版本不变。构建、六组算法测试与家具目录/自动排版/模块选择/规划/参考/自适应/会议/自定义/删除/多选 UI mock 及 MOCK 冒烟通过；一并修复四个脚本的既有过时断言；e2e:single-space 仍为既有已知失败，详见[任务记录](tasks/LAYOUT-019-merge-rounded-l-desk-into-l-screen.md)。

RENDER-003（2026-10-20）：修复选品后生图仅提交占地图的问题。新增后端商品可用性/图片核验、去重商品参考板、P编号到家具清单映射、多图供应商请求；记录和界面明确实际商品参考与旧任务。构建/效果预览 UI mock 通过，Java 验证见[任务记录](tasks/RENDER-003-selected-product-image-references.md)。未重启后端或调用真实收费生图，视觉对应待验收。

RENDER-004（2026-10-20）：生图从图库补充主图及当前变体/公共辅助图，每款最多 4 个不同角度，去重并记录实际张数；主图失效时尝试其他可用图。生成输入改为无文字立体空间引导，平面图仅留页面核对，正/负提示限制完整场景、禁止中央图板/清单/拼贴。构建、效果预览 UI mock、Java 全量 169 项（166 通过/3 跳过）及最终相关 21 项通过；本地引导图人工查看、191 个链接和差异检查通过。实际模型视觉效果需重启后端重新生成验证，见[任务记录](tasks/RENDER-004-multiview-products-and-scene-guide.md)。

RENDER-005（2026-10-20）：将真实多边形、主模块行列、朝向/镜像、实际离墙和组间净距、禁放区及原户型来源关联加入生成约束，移除逐件原始坐标长清单，立体引导补充 L 型占地；页面可查看本次测量。用户当前几何复算为 8×3、纵向净距 600 mm，左右 926/上 498/下 1626 mm。构建、效果预览 UI mock、Java 全量 172 项（169 通过/3 跳过）及相关 24 项通过；本地引导人工核对通过。未重启后端、未重新收费生图，实际视觉待验收，见[任务记录](tasks/RENDER-005-physical-layout-context.md)。

GEOM-011（2026-10-21）：按用户柱与门洞示例完成人工建筑约束首版。空间核对可聚焦单个空间并拖拽标柱、门洞净空和禁放区，支持尺寸编辑、撤销、刷新和模板保留；柱扣净面积，门洞净空不扣面积，自动/手动排版及后端确认共同避障。构建、三组排版算法、专项/空间/模板/规划 UI mock、全工作流、MOCK 冒烟和 Java 185 项（182 通过、3 跳过）通过；两张专项截图已人工查看。仅覆盖矩形人工标注，未执行自动识别、真实数据库或用户原图业务验收，见[任务记录](tasks/GEOM-011-space-obstacles.md)。

GEOM-011 门洞圆弧修正（2026-10-21）：按用户复核将门洞净空从外接矩形改为以铰链为圆心的四分之一圆扇形，支持半径和四个开启方向；前后端排版碰撞均使用扇形实际轮廓，不再禁放整个外接矩形。旧矩形门洞在草稿加载时升级，保留 ID 并仅使对应空间排版失效。构建、排版算法、专项/空间/模板/规划 UI mock 和 Java 全量 186 项（183 通过、3 跳过）通过，最新专项截图已人工查看；仍不包含自动门洞识别。

GEOM-011 约束移动编辑修复（2026-10-21）：已放置柱、门洞净空和禁放区支持选中后拖动移动；矩形使用四角手柄，门洞使用弧线半径手柄，保留数值编辑、换向、删除和撤销。操作草稿与房间轮廓分离，保存原位更新稳定 ID，失败/取消不改变旧几何；专项 UI 验证柱/门洞移动和手柄编辑、刷新恢复及房间轮廓不变。

GEOM-011 贴墙柱吸附（2026-10-21）：柱/井创建、移动、手柄和数值编辑增加可开关的贴墙吸附，按缩放计算阈值且最大 20mm，对齐所属空间水平/垂直边；门洞和普通禁放区不参与。算法覆盖阈值内外与上下/左右边，UI 覆盖墙内 10mm 创建吸附、已放置柱移动吸附及关闭开关保留偏移；斜墙吸附未纳入本版。
