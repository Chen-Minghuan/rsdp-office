# GEOM-016 家具布置区（多布置区）

日期：2026-10-27 起草，2026-10-30 定稿并实现。状态：已实现（单空间画布 UI、前后端校验、模拟 API 验证通过）；真实业务验收未执行。

> 定稿说明：旧草案（2026-10-27）设想「一个可选布局边界」。按 2026-10-30 执行要求改为**多布置区**：每个空间可有零/一/多个正向布置区，最多一个主布置区；不是单一边界对象。

## 目标（定稿）

- 每个空间可挂零/一/多个 `LayoutRegion` 正向布置区；没有启用区时维持旧的全房间可排布行为。
- 绘制：矩形拖绘与逐点多边形（Enter/双击结束，Esc 取消）；提交时完整校验，自交/退化/越界拒绝落库。
- 管理：改名、设主（同空间最多一个）、启用/停用、显示/隐藏（显隐绝不参与几何计算）、整体移动、顶点编辑、删除（删除主区时首个剩余区自动升主）。
- 量测：每条边长度与距墙读数（只读展示，外法线射线量测，不用于几何内缩）。
- 有效可排区 = 启用区并集 ∩ 空间净轮廓（多边形，可为多块/带孔，绝不退化为矩形包围盒）；硬禁放区/门洞净空/通道仍由既有机制另行扣除。
- 凹形、负坐标、相互重叠的区域必须正常工作；旧画布没有该字段一律按空数组，严禁自动创建矩形。
- 空间几何变化后越界/畸形区域保留并由 UI 标记「需人工修复」，不静默裁切或删除。
- 前后端校验等价：前端 `allowedPlacementAreas` 与服务端 `OfficeLayoutValidationPolicy.allowedPlacementArea` 同语义。

## 实现（2026-10-30）

- 契约：`frontend/app/types/spatial.ts` `LayoutRegion`（regionId/spaceId/name/polygonMm/isPrimary/visible/enabled/wallOffsetsMm）；`ZoneObject.layoutRegions` 可选数组。polygonMm 为世界毫米 Y 向上（与障碍同一约定），画布换算用 `layoutRegionRing`/`layoutRegionWorldRing`。
- 纯函数：`frontend/app/utils/spatial/layoutRegions.ts`（有效可排区、校验、量测、设主/删除/移动/顶点、旧数据 sanitize）。
- 排布接入：`officeManualPlacement.officePlacementHardProblem`（手动放置/确认）、`layoutPlanning.planLayoutProposal`、`mixedLayout.planMixedLayout` 均改为按有效可排区判定（null=旧全房间；[]=无可排交集；其他=并集∩空间，逐多边形判定而非包围盒）。
- 画布 UI：工具栏「布置区」（矩形/多边形）、画布左侧面板（列表/改名/设主/启停/显隐/删除/边长与距墙读数/需修复标记）、区域渲染（主区高亮、停用虚线、选中顶点手柄、边量测标注）、移动与顶点拖动提交。
- Store：`saveZoneLayoutRegion`（绘制/移动/顶点提交，完整校验+量测刷新）、`updateZoneLayoutRegions`（列表操作；启用集合或多边形实际变化才使排版确认失效）；几何变化只失效本空间排版确认（家具 layoutConfirmed=false、submitted=null），不触碰开发 A 的空间复核确认（reviewConfirmation 白名单不含 layoutRegions）。旋转/校准/合并空间时同步换算区域几何。
- 服务端：`OfficeLayoutValidationPolicy` 对 layoutConfirmed 家具核验「启用区并集 ∩ 空间」（java.awt.geom.Area，多边形非包围盒）；畸形/越界/归属不符的区域跳过不拒存（标注数据不阻断整稿保存，与前端「需修复」语义一致）。无数据库迁移：`office_canvas.objects_json` jsonb 透传。

## 验证（2026-10-30）

- 纯函数：`pnpm test:layout-regions` 14 组断言（含多区域并集非包围盒、凹形缺口不可排、null/[] 语义、量测、设主/删主升主、sanitize 不自动创建）。
- 服务端：`OfficeLayoutValidationPolicyTest` 新增 6 例（越区拒绝、停用回退全房间、并集非包围盒、凹形缺口、畸形跳过），`mvn -f backend/pom.xml test` 全量 233 项（3 项数据库环境未配置跳过）。
- UI（模拟 API）：`pnpm e2e:layout-regions`（矩形/多边形绘制、设主、改名、启停、显隐不改几何、量测读数、移动、顶点编辑、删主升主、撤销、刷新持久化、空间轮廓不变）。
- 回归：`pnpm build`、`test:space-boolean`、e2e:space-obstacles / e2e:single-space / e2e:layout-planning 通过。
- 未执行：真实数据库保存回读、真实业务排版验收；坐标换算与 C 侧布局引擎集成属后续联调。

## 依赖

GEOM-011 障碍标注交互；LAYOUT-021 通道体系；GEOM-014 布尔运算（polygon-clipping 求并/交/差）。交接 C（布局引擎）：`LayoutRegion` 契约、`allowedPlacementAreas` 语义（null=[]=多块的三分支）、坐标换算约定、失效触发点（saveZoneLayoutRegion / updateZoneLayoutRegions 签名变化）、测试夹具（test-layout-regions.mjs 与服务端 6 例）。
