# CAT-007 新商品确认后直接上架

状态：已编码待验证
更新日期：2026-11-17
基线/交付版本：当前工作区
关联：[商品入库与上下架](../product-requirements.md#2-商品入库与上下架)、[商品主档统一](INTEG-001-product-master-integration.md)

手工录入保存或批量导入确认后，Office 把新商品转发给 RSDP 的 `factory-entry`，主库将商品和默认规格创建为 `active`。预览阶段只解析和校验资料。同外部编码更新已有商品时，主档沿用当前上下架状态。

删除导入结果及选品空状态中的审核入口、商品详情审核状态、接口中的审核字段和遗留审核类型。已保存的旧审核页地址转到商品库，防止前端把 `audit` 当商品 ID 查询。旧方案的商品失效提示使用真实商品和规格可用性。修复手工录入的参数覆盖请求函数，保存时正确发出创建请求。此前的实施记录见 [CAT-006](CAT-006-product-batch-audit.md)。

## 验证

- 前端 `pnpm build` 通过。模拟 `pnpm e2e:workflows`、`pnpm e2e:module-recommendations` 和 `MOCK_API=1 pnpm e2e:smoke` 通过，覆盖手工保存、详情显示在售、导入确认和全部跳过、旧地址转到商品库及推荐空结果。
- `mvn -f backend/pom.xml -Djava.awt.headless=true package` 通过，278 项测试中 275 项通过、3 项跳过。
- 重启本地 Office 后用开发账号核对实际商品接口，列表返回 34 个商品，抽查详情与列表在售状态一致，均无审核字段。新商品实写和已下架商品更新回读待业务验证。公网测试站未发布。
