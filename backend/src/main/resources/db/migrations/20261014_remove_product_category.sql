-- 商品主档不再按品类码存储；历史库中的字段一并移除。
alter table if exists rspu_master drop column if exists category_code;
alter table if exists rspu_master drop column if exists category_path;
