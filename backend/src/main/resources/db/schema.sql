-- 办公产品导入模块数据库初始化脚本
-- 先创建数据库：CREATE DATABASE office OWNER postgres;

-- Independent concept previews. Snapshots never confirm or overwrite a design scheme.
create table if not exists office_render_preview (
    preview_id varchar(64) primary key,
    project_id varchar(128) not null,
    zone_id varchar(128) not null,
    zone_name text not null,
    canvas_revision integer not null,
    purpose varchar(32) not null default 'CONCEPT_PREVIEW' check (purpose = 'CONCEPT_PREVIEW'),
    model varchar(64) not null,
    style varchar(100) not null,
    view varchar(32) not null,
    instructions text not null,
    status varchar(32) not null,
    client_change_id varchar(128) not null,
    content_hash varchar(64) not null,
    snapshot_json jsonb not null,
    prompt text not null,
    provider_base_url text not null,
    provider_task_id varchar(128),
    provider_request_id varchar(128),
    image_url text,
    reference_url text,
    error_message text,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique(project_id, client_change_id)
);
create index if not exists idx_render_preview_history on office_render_preview(project_id, zone_id, created_at desc);

-- 品类字典
create table if not exists category_dict (
    dict_type varchar(50) not null,
    dict_code varchar(50) not null,
    dict_name varchar(100) not null,
    dict_name_en varchar(100),
    parent_code varchar(50),
    sort_order int default 0,
    status varchar(20) default 'active',
    aliases text,
    primary key (dict_type, dict_code)
);

-- 六维定义表保留已有资料；新商品录入不使用。
create table if not exists six_dim_schema (
    id bigserial primary key,
    category_code varchar(50) not null,
    dim_code char(1) not null,
    dim_name varchar(50) not null,
    allowed_values text,
    unique (category_code, dim_code)
);

-- 产品主档
create table if not exists rspu_master (
    rspu_id varchar(64) primary key,
    rspu_code varchar(50) unique,
    external_code varchar(100),
    product_name varchar(255),
    color_primary_name varchar(64),
    description text,
    retail_price decimal(12,2),
    primary_image_url varchar(500),
    detail_image_urls jsonb,
    status varchar(20) default 'active',
    audit_status varchar(20) default 'pending',
    audit_remark text,
    created_by varchar(64),
    created_at timestamp default now(),
    updated_at timestamp default now(),
    deleted_at timestamp
);

-- 兼容已创建的表：新增审核字段
alter table if exists rspu_master add column if not exists audit_status varchar(20) default 'pending';
alter table if exists rspu_master add column if not exists audit_remark text;

-- 默认变体
create table if not exists rspu_variant (
    variant_id varchar(64) primary key,
    rspu_id varchar(64) not null references rspu_master(rspu_id),
    display_name varchar(255),
    size_code varchar(64),
    size_text varchar(100),
    dimensions jsonb,
    package_count integer,
    package_volume_cbm numeric(18,9),
    gross_weight numeric(14,3),
    color_code varchar(64),
    color_text varchar(100),
    material_code varchar(128),
    material_text varchar(255),
    status varchar(20) default 'active',
    created_at timestamp default now(),
    updated_at timestamp default now(),
    deleted_at timestamp
);

-- 旧关联表保留已有资料；新商品录入不写入。
create table if not exists rspu_style (
    rspu_id varchar(64) not null references rspu_master(rspu_id),
    dict_type varchar(50) not null,
    style_code varchar(50) not null,
    is_primary boolean default false,
    created_at timestamp default now(),
    primary key (rspu_id, style_code)
);

create table if not exists rspu_scene (
    rspu_id varchar(64) not null references rspu_master(rspu_id),
    dict_type varchar(50) not null,
    scene_code varchar(50) not null,
    created_at timestamp default now(),
    primary key (rspu_id, scene_code)
);

-- 图片资产
create table if not exists image_assets (
    image_id varchar(64) primary key,
    rspu_id varchar(64) references rspu_master(rspu_id),
    variant_id varchar(64) references rspu_variant(variant_id),
    image_type varchar(50),
    storage_url varchar(500),
    file_size bigint,
    width int,
    height int,
    format varchar(20),
    is_primary boolean default false,
    uploaded_by varchar(64),
    created_at timestamp default now(),
    deleted_at timestamp
);

-- 单工厂信息
create table if not exists factory_master (
    id bigserial primary key,
    factory_code varchar(50) unique not null,
    factory_name varchar(100),
    contact_info text
);

-- 单工厂产品报价
create table if not exists factory_product_price (
    id bigserial primary key,
    rspu_id varchar(64) not null references rspu_master(rspu_id),
    variant_id varchar(64) references rspu_variant(variant_id),
    factory_id bigint not null references factory_master(id),
    factory_price decimal(12,2) default 0,
    moq int default 1,
    lead_time_days int default 0,
    effective_date date default current_date,
    unique (rspu_id, variant_id, factory_id)
);

-- 初始化办公品类字典
insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('category', 'OF', '办公家具', null, 1),
('category', 'DK', '办公桌', 'OF', 2),
('category', 'CH', '办公椅', 'OF', 3),
('category', 'CB', '文件柜', 'OF', 4),
('category', 'SO', '沙发', 'OF', 5),
('category', 'TB', '茶几', 'OF', 6),
('category', 'MT', '会议桌', 'OF', 7),
('category', 'MC', '会议椅', 'OF', 8),
('category', 'PA', '屏风/隔断', 'OF', 9)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('style', 'MC', '现代简约', null, 1),
('style', 'BZ', '商务正装', null, 2),
('style', 'GY', '工业风', null, 3),
('style', 'SN', '北欧风', null, 4)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('scene', 'OPEN', '开放办公区', null, 1),
('scene', 'MEET', '会议室', null, 2),
('scene', 'RECEP', '前台接待', null, 3),
('scene', 'LOUNGE', '休闲区', null, 4),
('scene', 'EXEC', '高管办公室', null, 5)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('material', 'LEATHER', '真皮', null, 1),
('material', 'MESH', '网布', null, 2),
('material', 'FABRIC', '布艺', null, 3),
('material', 'WOOD', '实木', null, 4),
('material', 'METAL', '金属', null, 5),
('material', 'PLASTIC', '塑料', null, 6)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('color', 'BK', '黑色', null, 1),
('color', 'WH', '白色', null, 2),
('color', 'GY', '灰色', null, 3),
('color', 'BR', '棕色', null, 4),
('color', 'BE', '米色', null, 5)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('size', 'S', '小号', null, 1),
('size', 'M', '中号', null, 2),
('size', 'L', '大号', null, 3),
('size', 'XL', '特大号', null, 4)
on conflict (dict_type, dict_code) do nothing;

insert into category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) values
('factory_level', 'A', 'A级', null, 1),
('factory_level', 'B', 'B级', null, 2),
('factory_level', 'C', 'C级', null, 3)
on conflict (dict_type, dict_code) do nothing;

-- 办公画布数据；project_id 仅作为现有设计工作区的隔离键。
create table if not exists office_canvas (
    canvas_id bigserial primary key,
    project_id varchar(64) not null,
    revision int default 1,
    mode varchar(20) default 'overview',
    design_zone_id varchar(64),
    objects_json jsonb not null,
    files_json jsonb,
    blank boolean default false,
    created_at timestamp default now()
);

create table if not exists office_cart (
    line_id varchar(64) primary key,
    project_id varchar(64) not null,
    zone_id varchar(64),
    zone_name varchar(255),
    product_id varchar(64) not null,
    qty int default 1,
    created_at timestamp default now(),
    updated_at timestamp default now()
);

create table if not exists office_agent_message (
    message_id bigserial primary key,
    project_id varchar(64) not null,
    role varchar(20) not null,
    content text not null,
    created_at timestamp default now()
);

-- ==================== 户型图 / 无限画布场景（从 RSDP 抽取并简化） ====================
-- 单位约定：所有坐标/尺寸字段为毫米（mm），面积为平方米（m²），除非另有注释。

-- 分析批次头表：一次上传对应一个分析批次
create table if not exists floor_plan_analysis (
    analysis_id varchar(64) primary key,
    project_id varchar(64),
    source_name varchar(128),
    status varchar(32) not null default 'pending',
    input_mode varchar(16) not null, -- image / pdf / cad / manual
    geometry_source varchar(32), -- ai_vision / cad_geometry / manual_geometry
    image_id varchar(64) references image_assets(image_id),
    preview_image_id varchar(64) references image_assets(image_id),
    task_id varchar(64),
    scale_ratio decimal(18,8),
    raw_result jsonb,
    confirmed_rooms jsonb,
    quality_issues jsonb,
    cad_object_key varchar(256),
    layout_json jsonb,
    layout_revision bigint default 1,
    active_analysis_revision integer,
    working_analysis_revision integer,
    latest_analysis_revision integer,
    error_message text,
    created_by varchar(64),
    created_at timestamp default now(),
    updated_at timestamp default now(),
    deleted_at timestamp
);

-- 空间识别明细：按 analysis + revision 保存每个空间
create table if not exists floor_plan_room (
    room_id varchar(64) primary key,
    analysis_id varchar(64) not null references floor_plan_analysis(analysis_id),
    analysis_revision integer not null default 1,
    room_type varchar(64) not null,
    label varchar(255),
    bbox jsonb,
    polygon jsonb, -- 毫米坐标 [[x,y],...]
    centroid jsonb,
    label_point jsonb,
    width_mm integer,
    depth_mm integer,
    area_m2 decimal(12,4),
    dimension_source varchar(32),
    dimension_confidence varchar(8),
    dimension_text varchar(255),
    geometry_source varchar(32),
    confirmation_status varchar(16) not null default 'AUTO', -- AUTO / CONFIRMED
    shape_parts jsonb,
    placement_zone jsonb,
    placement_zones jsonb,
    sort_order integer default 0,
    created_at timestamp default now(),
    updated_at timestamp default now(),
    deleted_at timestamp
);

create index if not exists idx_fpr_analysis_revision on floor_plan_room(analysis_id, analysis_revision) where deleted_at is null;

-- Scene Revision 头表：每个分析批次的版本快照
create table if not exists floor_plan_scene_revision (
    analysis_id varchar(64) not null,
    analysis_revision integer not null,
    parent_analysis_revision integer,
    revision_trigger varchar(32) not null, -- INITIAL_PARSE / MANUAL_EDIT / LEGACY_IMPORT
    revision_status varchar(24) not null default 'PROCESSING', -- PROCESSING / READY / EDITING / READY_TO_ACTIVATE / ACTIVE / FAILED / SUPERSEDED
    parse_status varchar(16), -- SUCCESS / PARTIAL / NEEDS_REVIEW / FAILED
    room_revision integer not null default 1,
    furniture_revision integer not null default 1,
    parser_version varchar(64),
    parser_config_hash varchar(64),
    source_profile_version varchar(64),
    scene_version varchar(16),
    unit_resolution jsonb,
    raw_result jsonb,
    preview_image_id varchar(64) references image_assets(image_id),
    quality_issues jsonb,
    drawing_bounds jsonb,
    preview_bounds jsonb,
    created_by varchar(64),
    created_at timestamp not null default now(),
    ready_at timestamp,
    confirmed_at timestamp,
    activated_at timestamp,
    activated_by varchar(64),
    primary key (analysis_id, analysis_revision),
    foreign key (analysis_id) references floor_plan_analysis(analysis_id)
);

-- 识别家具候选：按 revision 保存 CAD/视觉识别的家具对象
create table if not exists floor_plan_furniture (
    furniture_id varchar(64) primary key,
    analysis_id varchar(64) not null references floor_plan_analysis(analysis_id),
    analysis_revision integer not null default 1,
    source_kind varchar(16), -- INSERT / EXPLODED / MANUAL
    category varchar(16),
    semantic_label varchar(128),
    room_id varchar(64),
    block_name varchar(255),
    source_handles jsonb,
    insert_path jsonb,
    position jsonb,
    rotation_deg decimal(9,2),
    footprint_polygon jsonb,
    geometry_width_mm integer,
    geometry_depth_mm integer,
    annotated_width_mm integer,
    annotated_depth_mm integer,
    effective_width_mm integer,
    effective_depth_mm integer,
    height_mm integer,
    dimension_source varchar(32),
    semantic_source varchar(32),
    evidence jsonb,
    quality_flags jsonb,
    detection_confidence varchar(8),
    category_confidence varchar(8),
    confirmation_status varchar(16) not null default 'AUTO', -- AUTO / CONFIRMED / MANUAL_ADDED / MANUAL_EDITED
    matched_from_object_id varchar(64),
    derived_from_ids jsonb,
    sort_order integer not null default 0,
    created_by varchar(64),
    created_at timestamp not null default now(),
    updated_at timestamp,
    deleted_at timestamp
);

create index if not exists idx_fpf_analysis_revision on floor_plan_furniture(analysis_id, analysis_revision) where deleted_at is null;
create index if not exists idx_fpf_analysis_revision_id on floor_plan_furniture(analysis_id, analysis_revision, furniture_id);
