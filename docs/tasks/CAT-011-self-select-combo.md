# CAT-011 商品库自选组合

## 目标与范围

商品库 `/products` 右侧新增“自选组合”栏：命名创建组合（每账号≤10 个）、拖拽商品卡或组合空位＋弹选品窗加入商品（每组≤5 个）、缩略图网格拼贴背景、展开/收起明细、重命名、删除确认。组合归属个人，持久化到 PostgreSQL；不进入报价/方案/画布流程，不改动商品卡现有项目收藏＋与个人收藏♥。

设计文档：`docs/superpowers/specs/2026-11-23-self-select-combo-design.md`；渲染图确认方案 A（网格拼贴、名称与操作置顶）。

## 实现（2026-11-24 按组长评审意见收敛）

- **单表结构**：`office_combo(combo_id,user_id,name,rspu_ids varchar(128)[],created_at,updated_at)`；`rspu_ids` 默认空数组、顺序即展示顺序，数据库 `cardinality<=5` 约束，`(user_id,name)` 唯一。评审前的 `office_combo_item` 明细表已移除；旧迁移未在共享环境执行，直接修改本 PR 迁移与 `schema.sql`。数组与 `List<String>` 互转使用自定义 `VarcharArrayTypeHandler`。
- **增量保存**：`POST /api/v1/office/combos/{id}/items`（本次新增 ID 集合）在组合锁内重读最新数组后追加、去重、总数>5 拒绝；`DELETE /{id}/items/{rspuId}` 单件移除。废弃整组覆盖 PUT，连续拖入两件商品都保留。
- **失效商品可编辑**：只对本次新加入的商品调用商品可见性校验；已在组内的失效旧条目保留，列表以 `available=false` 标注“已失效”，不影响添加/移除其他商品。
- 其余接口：GET 列表、POST 创建（用户锁内校验每账号≤10）、PATCH 重命名、DELETE 删除组合。
- 前端：`ProductComboPanel.vue`（调宽 280–480px、组合卡、命名/删除/选品弹窗），拖拽/弹窗/移除均走增量接口；`products/index.vue` 左右两栏；`useOfficeApi` 增量方法；`types/combos.ts`。
- 经验：选品弹窗 `<dialog>` 不可挂 `flex` 类（会覆盖未打开时的 display:none）。

## 验证（2026-11-24）

- 后端：`OfficeComboServiceTest` 22 项，含新增去重、超 5 拒绝、**失效旧条目可添加/移除其他商品**、**连续两次增量都保留**；全量 Java 363 项，0 失败、4 跳过。
- 前端：`e2e:product-combos` 覆盖创建命名、弹窗加商品、展开/收起、**连续拖入两件都保留**、刷新持久化、超 5 拦截、重命名、删除、**失效商品组可编辑与移除**、10 组合上限；`pnpm build`、`e2e:workflows` 通过。
- 未执行：真实后端 + 数据库联调与业务验收；未部署。`e2e:smoke` 在既有的生成整体方案步骤失败（上游方案门禁变更，与本需求无关）。
