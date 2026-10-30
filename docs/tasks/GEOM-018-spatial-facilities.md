# GEOM-018 设施 / 点位标注

日期：2026-10-30 新增并实现（此前无任务卡）。状态：已实现（单空间画布 UI、纯函数校验、模拟 API 验证通过）；真实业务验收未执行。

## 背景

在既有障碍体系（GEOM-011 柱/门洞/窗/禁放区）之上补充设施标注：消防设施、电箱、踢脚线、电源点位、弱电点位。这些对象语义各异，不能全部塞进矩形障碍模型。

## 目标（定稿）

- 三类形态分开建模：点位型（电源/弱电，shape=point）、线型（踢脚线，shape=polyline）、占用型（消防/电箱，shape=polygon）。
- 默认语义全部为标注与提示：点位只提示位置；踢脚线是靠墙安装提醒；占用型可人工标注操作净空（clearanceMm），缺省只提示不阻断。
- 净空数值必须来自人工标注，不得虚构消防规范数值；需要实际禁放时另行创建 NO_PLACEMENT 障碍。
- 全部设施不扣净面积、不进入 zoneLayoutExclusions、不参与排版硬阻断；窗的标注型行为不得回归。
- 显隐只影响渲染；标注随空间旋转/校准/合并同步换算。

## 实现（2026-10-30）

- 契约：`frontend/app/types/spatial.ts` `SpatialFacility` + `SpatialFacilityKind`（FIRE_FACILITY/ELECTRICAL_BOX/SKIRTING/POWER_OUTLET/DATA_OUTLET）；`ZoneObject.facilities` 可选数组。pointsMm 为世界毫米 Y 向上（同障碍约定）。选择独立的 facilities 数组而非新障碍 kind：障碍模型仅 POLYGON + excludesFloorArea 语义，且服务端把所有非 WINDOW 障碍当排除区，混入会破坏「不扣面积、不阻断」语义。
- 纯函数：`frontend/app/utils/spatial/facilities.ts`（标签/形态推导、占地环、净空环=包围盒外扩（诚实标注）、校验、排版提示 facilityLayoutHints 供 C 侧使用、旧数据 sanitize）。
- 画布 UI：工具栏「设施标注」五类入口（点位单击、踢脚线逐点 Enter/双击、消防/电箱拖绘矩形）、设施图层（点型标记/线型粗线/占用型虚线占地+净空点线）、编辑卡（名称/操作净空/显隐/备注/删除）、移动与顶点/端点拖动。
- Store：`saveZoneFacility`（校验通过才落库）、`removeZoneFacility`；提示型对象，不使排版确认失效。
- 服务端无改动：facilities 在 `office_canvas.objects_json` jsonb 透传，不参与确认核验；无数据库迁移。

## 验证（2026-10-30）

- 纯函数：`pnpm test:layout-facilities` 7 组断言（形态推导、点位/线型/占用校验、净空环外扩、提示不阻断、sanitize）。
- UI（模拟 API）：`pnpm e2e:layout-regions` 同脚本覆盖（点位放置、踢脚线逐点、消防拖绘+人工净空/名称/备注、显隐、移动、删除、不进入 obstacles 不扣面积、刷新持久化）。
- 回归：`pnpm build`、e2e:space-obstacles（窗标注行为未回归）等通过；Java 全量 233 项（3 跳过）无改动相关失败。
- 未执行：真实数据库保存回读、真实业务验收；设施提示与排版面板的集成展示属 C 侧联调。

## 依赖

GEOM-011 障碍标注（窗的标注型语义为其先例）；GEOM-016 共用逐点绘制与坐标换算约定。
