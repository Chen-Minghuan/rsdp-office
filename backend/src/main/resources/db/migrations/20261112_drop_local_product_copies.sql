-- SHARED-002：商品数据统一由 RSDP 主项目维护，删除 office 本地商品拷贝表
-- 迁移前已对 office 库做 pg_dump 备份（output/office-backup-before-catalog-cleanup-*.sql）。
-- image_assets 保留：floor_plan 等 office 自有图纸文件仍在该表登记，仅商品图片来源切换为主项目；
-- 其指向商品表的外键同步摘除（历史 rspu_id/variant_id 弱关联保留值不校验）。

ALTER TABLE image_assets DROP CONSTRAINT IF EXISTS image_assets_rspu_id_fkey;
ALTER TABLE image_assets DROP CONSTRAINT IF EXISTS image_assets_variant_id_fkey;

DROP TABLE IF EXISTS factory_product_price;
DROP TABLE IF EXISTS factory_master;
DROP TABLE IF EXISTS rspu_style;
DROP TABLE IF EXISTS rspu_scene;
DROP TABLE IF EXISTS rspu_variant;
DROP TABLE IF EXISTS rspu_master;
DROP TABLE IF EXISTS six_dim_schema;
DROP TABLE IF EXISTS category_dict;
