-- 楼层级参考图：与项目级参考图同一 JSON 契约（[{url,name}]），按楼层区分。
alter table office_design_floor add column if not exists reference_images text;
