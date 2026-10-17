# Spatial 附件 B：接口、保存时序与存储契约 V1.0

更新日期：2026-10-14。目标契约，接口、表和迁移均尚未实现。
关联：[实施方案](../../Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)、[确认规则](confirmation-rules.md)。
机器可读定义：[JSON Schema](../contracts/spatial-api.schema.json)；可校验报文：[接口示例](../contracts/spatial-api.examples.json)。
本文固定设计稿核心接口；schema 覆盖本附件列出的设计稿请求/响应，文档上传/页面处理/识别 DTO 在 RECOG-002 中另行落实。schema 检查结构，几何有效性、引用、版本和事务语义由后端核验，不能只靠 schema。

## 1. 路由与空稿初始化

所有相对路由位于 `/api/v1/floor-plan`。目标成功响应统一 HTTP 200 与现有 `Result {code,message,data}` 风格，错误使用真实 HTTP 状态及同值 code。

| 操作 | 路由 | Schema 的 $defs | 行为 |
| --- | --- | --- | --- |
| 创建设计稿 | POST `/design-scenes` | CreateSceneRequest / MutationResponse | projectId+pageId，不强制依赖 analysisId |
| 读取当前稿 | GET `/design-scenes/{sceneId}` | ReadResponse | 返回当前完整 SceneSnapshot |
| 草稿保存 | PUT `/design-scenes/{sceneId}/draft` | DraftRequest / MutationResponse | 全部可编辑图层和显式删除，一次原子保存 |
| 校准应用 | POST `/design-scenes/{sceneId}/calibrations` | CalibrationRequest / MutationResponse | 当前已保存稿转换，原子写校准/几何/失效 |
| 空间/整体确认 | POST `/design-scenes/{sceneId}/confirmations` | ConfirmationRequest / MutationResponse | scope=SPACES 或 SCENE，按附件 C 核验 |
| 查询确认快照 | GET `/design-scenes/{sceneId}/confirmations/{confirmationId}` | ConfirmationReadResponse | 返回不可变空间/整体快照，不以当前稿替换 |

创建模式：

- `FROM_RECOGNITION`：pageId、sourceSceneId、完整可查询来源版本；复制原始候选到新稿，产生稳定空间/顶点 ID。
- `EMPTY_PAGE`：sourceSceneId=null，可附失败 runId；标准底图处理成功即可创建，识别失败/无候选/未配置服务均可走此路径。
- 新稿 `revision=1`、`scopeRevision=1`、未校准且无确认；pageId 与项目访问权限由后端核验。
- 用户从演示创建的稿仍保留演示来源，不能通过复制绕过正式确认门槛。
- 原方案中的 `POST /{analysisId}/design-scenes` 如实现，只作为 FROM_RECOGNITION 的兼容适配，不作为空稿前置条件。

## 2. 全稿保存：缺失、空数组与删除

DraftRequest 必须包含 `spaces/walls/openings/obstacles/annotations/boundaryRelations/issueDispositions/deletions`，这些是完整工作内容，不是 PATCH。
底图、原始来源、校准、服务端质量检查/确认和派生值是服务器管理字段，不通过草稿请求修改。

| 输入 | 确定语义 |
| --- | --- |
| 缺少必需图层或 null | 400 SCHEMA_INVALID，整个请求不写入；不是保留旧值 |
| 某图层 [] 且服务器原来也为空 | 合法空图层；layerAvailability 仍由服务器维护，不因此变为已识别 |
| 原有对象从完整数组中消失，但无 deletion | 400 IMPLICIT_DELETE_FORBIDDEN；不静默删除或自动补回 |
| deletion 指定已有对象，且完整数组不再包含它 | 显式删除/墓碑；保留历史、核验引用并按附件 C 失效 |
| 同一对象同时存在于数组与 deletion | 400 DELETE_PRESENT_OBJECT |
| deletion 指向不存在、另一场景或已经删除的对象 | 400 UNKNOWN_OBJECT_ID；同请求的网络重放先查回执，不再执行删除 |
| 原始识别空间从数组消失或被删除 | 拒绝；应保留对象并 includedInDesign=false、给 exclusionReason |
| 人工空间删除 | deletions 中 layer=spaces、objectId、非空 reason；数组移除它 |
| 删除墙/障碍等仍被其他对象引用 | 同请求须更新/删除对应引用；悬空引用拒绝，不自动级联删除 |
| 重复 ID、顶点 ID 不对应坐标、非法来源字段 | 400；服务器不接受客户端重写来源 |

识别图层的设计副本可以显式删除，原始 SourceScene 内容始终不变；删除会记录来源对象的设计处理。
恢复已删除人工对象须显式新建新 ID 并引用历史，不直接复用墓碑 ID。来源空间排除/恢复沿用稳定空间 ID。
新空间由服务器赋予 MANUAL 来源；已存在空间保留来源绑定。客户端提交的来源真实性、派生面积和 confirmed 状态均不接受。
新增非法引用/非有限坐标直接拒绝；可修复的既有候选几何问题可作为有问题草稿保存并阻止确认。

## 3. 请求与响应字段

schema 的封闭对象拒绝额外字段；坐标和顶点 ID、rectangleFrame、boundaryBasis 见附件 A。
DraftRequest 带 schemaVersion=1.2、baseRevision、clientChangeId、工作坐标、校准版本，以及上述完整数组。

MutationResponse.data 固定包含：

| 字段 | 含义 |
| --- | --- |
| clientChangeId | 本次请求的幂等 ID |
| sceneId / previousRevision / revision | 目标场景、本次操作前后版本；无变化则相等 |
| affectedSpaceIds / invalidatedSpaceIds | 后端计算的影响/失效集合 |
| confirmationIds / confirmedSceneId | 本次确认产生/复用的快照 ID，其他操作为空/为 null |
| scene | 完整服务器 SceneSnapshot，包含新版本、派生值、问题、状态与来源 |

ErrorResponse 仍是 `{code,message,data}`；data 固定包含 errorCode、sceneId/clientChangeId（无目标时 null）、currentRevision（未知时 null）和 issues 数组。
409 `VERSION_CONFLICT` 只提供当前版本，不直接携带用户解决方案；客户端 GET 当前稿后比较并保留本地修改。
422 `CONFIRMATION_BLOCKED` 包含空间/对象 ID、位置（如适用）及阻止原因；不存在部分成功确认。
400 `IDEMPOTENCY_KEY_REUSED` 表示同幂等 ID 被用于不同请求，不能修改请求体继续沿用该 ID。
权限/不存在为 403/404，页面或外部服务不可用为 503；错误包遵守同一结构。具体完整报文见接口示例 JSON，不以零散伪字段替代完整响应。

校准请求的两个参考点须不同且位于标准底图内。首期世界原点取第一个参考点，X 向右、Y 向上；令 s=referenceLengthMm/两点像素距离，矩阵为 [s,0,0,-s,-s×x1,s×y1]。不把参考线方向自动旋成世界 X 轴；方向来自标准底图预处理。已有稿重新校准使用新矩阵×旧逆矩阵作用于当前稿。

## 4. 幂等与事务顺序

所有写操作含 clientChangeId。幂等键为 `(actorId, scopeKey, clientChangeId)`：已有场景 scopeKey 为 `SCENE:{sceneId}`；创建场景为 `PROJECT:{projectId}:CREATE_SCENE`。
请求 hash 包含方法、路径动作、规范 JSON 及 baseRevision；规范化仅固定对象字段顺序，数组顺序保持原样。
同键同 hash 在检查 baseRevision **之前** 查回执并重放原 HTTP 状态和响应体；可用响应头 `Idempotent-Replay: true` 表示重放，不修改原响应体。
同键不同 hash 返回 400。终态成功和确定的 400/409/422 保存回执；瞬时服务错误和事务失败不保存成功回执。

数据库事务：验证鉴权 → 占用/读取唯一回执键 → 锁 scene 头 → 比较 baseRevision → 核验并计算 diff → 写不可变修订/确认/校准 → 更新当前指针 → 写响应回执 → 一起提交。
重复请求竞争同键时等待唯一键所属事务完成；进程崩溃则事务回滚，可按同 ID 原包重试。
对 version/schema 拒绝的回执只提交错误回执，不提交业务状态；客户端修正后必须使用新的 ID。
回执首期保留到场景归档期，不在客户端重试窗口内清除。读取历史回执可能比当前场景旧，不能把回执快照当最新读取结果。

## 5. 客户端时序与队列

每个场景一条写队列，保存、校准、确认串行；GET/相机交互可独立进行。使用三份状态：serverBaseline、workingDraft、inFlightSnapshot，并维护单调 localEditSeq。
准备发送时才冻结 baseRevision、clientChangeId 与请求体；已发送请求不可换 body/baseRevision 重用 ID。
待发送普通草稿可合并为最新完整工作稿；校准和确认是不能跨越的队列屏障，先保存前面的编辑。

| 情况 | 必须执行的规则 |
| --- | --- |
| 服务端已保存 r4，但响应丢失 | 按原 r3 base、原 ID、原 body 重试，重放 r4；不生成第二次修改 |
| 保存 A 在途，用户继续编辑 B | 保留 B 工作稿；A 成功只推进 baseline 到 r4。若 localEditSeq 已增加，不以 A.scene 覆盖 B |
| A 成功后发送 B | 按 baseline r4 和当前完整 B 冻结新请求；B 保存后更新到 r5 |
| A 回包的派生值/确认状态 | 只属于 A 的 snapshot；待提交 B 自己重算预览/标未确认，不能套用 A 的确认 |
| 第三方已更新至 r5，A 重试重放 r4 | 接受 A 已落库的事实；B 用 r4 发送时 409，停止队列，GET r5 并显式比较/合并，不猜测覆盖 |
| 409 冲突 | 保留本地 B 与旧 baseline，暂停后续写操作；用户应用合并结果后用新 ID/base 提交 |
| 400/422 | 无业务写入，保留工作稿；修复请求/确认问题后创建新请求，不无限重放已缓存拒绝 |
| 超时/可重试 5xx | 状态为“结果未知”，先原包重试；不能盲目发送后续依赖请求 |
| 校准应用 | 先刷完草稿、按 baseline 做影响预览；应用期间冻结几何输入直到成功/失败，相机仍可操作 |
| 提交确认 | 先刷完草稿并核对服务器条件；请求期间冻结几何输入；异常并发产生的新编辑仍保持未确认 |
| 更换场景/组件卸载后旧回包 | 可记录其对应场景已保存回执，但不得覆盖当前打开的其他场景 |

失去响应后刷新：本地保留冻结请求与待提交编辑，先重放/查询当前稿判定是否已保存，再恢复待提交工作；不能因 localStorage 存在就显示服务器已保存。

## 6. 固定首期存储模型

沿用 PostgreSQL 和 image_assets 资产引用；首期新增设计表，不把 floor_plan_room 的可变确认记录当作原始来源。
以下主键/FK/快照职责已固定，实际 DDL 和迁移需在 GEOM-001/FLOW-001 中落地，不在本轮执行建表。

| 表 | 主键/唯一约束 | 固定内容与关系 |
| --- | --- | --- |
| floor_plan_document | asset_id varchar(64) PK/FK image_assets.image_id | project_id 为设计隔离键（不再外键关联 office_project，见 [WORKSPACE-001](../tasks/WORKSPACE-001-remove-project-info.md)）、sha256、媒体类型；原文件资产不因编辑变化 |
| floor_plan_page | page_id varchar(64) PK；unique(asset_id,page_number,processing_revision) | 文档 FK、标准底图资产 FK、尺寸、页/裁剪变换；处理版本不可覆盖 |
| floor_plan_source_scene | source_scene_id varchar(64) PK；unique(analysis_id,analysis_revision) | page FK；可空的识别复合 FK 指向 floor_plan_scene_revision；不可变标准来源 JSONB、原始回包 hash、runId |
| floor_plan_design_scene | scene_id varchar(64) PK | project/page FK，初始 source_scene_id 可空，current_revision bigint、创建信息 |
| floor_plan_design_revision | PK(scene_id,revision) | 完整 SceneSnapshot JSONB、parent_revision、clientChangeId、动作、操作者/时间；不可变，revision 从 1 单调增加 |
| floor_plan_calibration | PK(scene_id,calibration_revision) | 页、参考点/距离、sourceToWorld、依据/操作者；不可变，未校准 revision=0 不建记录 |
| floor_plan_confirmation | confirmation_id varchar(64) PK | scene FK，kind SPACE/SCENE、可空 space_id、设计修订复合 FK、条件版本、完整不可变快照 JSONB、操作者/时间 |
| floor_plan_operation_receipt | PK(actor_id,scope_key,client_change_id) | action、request_hash、HTTP status、完整 Result JSONB、可空目标 scene/revision FK、时间 |

`design_scene.current_revision` 用延迟检查的复合 FK 指向同场景 revision；创建时在同一事务写头表和 revision=1。
source_scene 的识别 FK 两列同时为空或同时非空；首期真实/演示识别有该引用，EMPTY_PAGE 设计可直接没有 source_scene。
顶点/空间/墙/障碍 ID 的存在、唯一性、来源绑定、墓碑和确认条件存于完整快照并由服务核验；不增加另一套可独立更新的几何事实表。
revision 快照包含所有图层、变换/校准引用、来源绑定、版本、质量处理、失效确认引用和墓碑；确认快照包含当时几何、约束、来源、派生值及条件，不能只存可变对象 ID。
索引至少覆盖 design_scene(project_id,page_id)、source_scene(page_id)、confirmation(scene_id,space_id) 和操作回执键。
快照历史不更新；当前确认是否有效由当前 revision 的条件/引用决定，旧确认行继续保留。

## 7. 迁移与最小闭环

迁移实施固定为显式版本脚本（例如 backend/src/main/resources/db/migrations 下的编号 SQL），部署流程记录运行顺序/版本；当前项目尚未接入该迁移入口，本附件不声称已存在自动迁移工具。
先新增表/约束，回填已存在来源资产与页，冻结可追溯来源副本，创建可校验的旧稿设计快照，再切换读写。
旧 raw_result 无法证明真实来源或坐标时标记未知/演示、待校准；不能通过 legacy CONFIRMED 自动生成新正式确认。
旧 rooms 确认入口在新版 scene 上须禁止旧写法或严格适配新事务；不得继续删房间导致新来源副本变化。
回填幂等按稳定旧 ID 映射，报告成功/跳过/失败；无可靠单位的数据保留旧稿，不猜测毫米。备份恢复与迁移回滚方案随实际 SQL 验证。

最小竖向闭环先于复杂编辑：

1. 已处理页导入一个矩形，或 EMPTY_PAGE 手工创建一个矩形；校准可靠。
2. 修改宽度，保存 r2，刷新读回相同几何/ID/来源。
3. 逐空间确认并整体确认，获得不可变快照。
4. 再修改，保存新修订，相关空间及整体确认失效，旧快照和原始来源仍可查询。
5. 验证响应丢失重试、保存中继续编辑、版本冲突及无候选空稿。

上述通过后扩展 L 形/多房间/顶点编辑；真实识别能力验证在第 1 步之前启动，并行记录结果，不用 fixture 代替识别质量。

## 8. 契约文件验证

结构用例验证命令：`python3 docs/contracts/validate_spatial_contract.py`，需要文档检查环境中的 jsonschema 4.25.1（不增加业务依赖）。验证包含 schema 自检、全部正反报文和 HTTP/code 一致性；不宣称检查了数据库事务、实际几何或在线接口。
