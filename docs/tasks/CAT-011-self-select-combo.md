# CAT-011 商品库自选组合

## 目标与范围

商品库 `/products` 右侧新增“自选组合”栏：命名创建组合（每账号≤10 个）、拖拽商品卡或组合空位＋弹选品窗加入商品（每组≤5 个）、缩略图网格拼贴背景、展开/收起明细、改名、删除确认。组合归属个人，持久化到 PostgreSQL；不进入报价/方案/画布流程，不改动商品卡现有项目收藏＋与个人收藏♥。

设计文档：`docs/superpowers/specs/2026-11-23-self-select-combo-design.md`；渲染图确认方案 A（网格拼贴）。

## 实现

- 数据库：`20261124_office_self_select_combo.sql` + `schema.sql` + `SchemaInitializer` 注册。`office_combo(user_id,name)` 唯一索引；`office_combo_item(combo_id,rspu_id)` 联合主键 + `sort_order`，删组合级联清明细；商品仅存 `rspu_id` 弱关联。
- 后端：`OfficeComboController` `/api/v1/office/combos`：GET 列表（含商品名称/图/价格与 available）、POST 创建、PATCH 改名、PUT `/{id}/items` 整体替换（去重、>5 拒绝、事务内 advisory lock）、DELETE 删除。归属与名称校验在 Service；`available=false` 表示商品下架或不可见，前端标注“已失效”不静默移除。认证关闭的本地开发沿用固定 LOCAL-DEV 用户。
- 前端：`ProductComboPanel.vue`（调宽拖拽柄 280–480px、组合卡、命名/删除/选品三个弹窗），`products/index.vue` 改为左右两栏，商品卡 `draggable` 携带 `rspuId`；`useOfficeApi` 新增 5 个方法；`types/combos.ts`。
- 修复：选品弹窗 `<dialog>` 不可挂 `flex` 类（覆盖未打开时的 display:none，导致弹窗常驻遮挡页面）。
- 复核优化：选品弹窗“已在组内”判断提取 `pickerExistingIds`；创建组合按用户 advisory 锁防止并发突破 10 个上限；卡片头调整为组合名+重命名/删除+计数置顶，拼贴网格在下（渲染图 `方案A-名称置顶-展开态.png` 确认后实施）。调整后组合 e2e 与构建复测通过。

## 验证（2026-11-24）

- 后端 TDD：`OfficeComboServiceTest` 17 项先行失败（编译红灯）后全绿；全量 `mvn -f backend/pom.xml test`：358 项，0 失败、4 跳过。
- 前端：新增 `e2e:product-combos`（创建命名、弹窗加 3 商品、展开/收起、HTML5 拖拽加入、刷新持久化、超 5 后端拒绝且内容不变、改名预填与保存、删除确认、10 组合上限禁用创建）通过；`pnpm build`、`e2e:workflows`、`e2e:favorites-page` 通过。
- 未执行：真实后端 + 数据库联调与业务验收；未部署、未提交推送。`e2e:smoke` 在既有的生成整体方案步骤失败（上游方案门禁变更，与本需求无关）。
