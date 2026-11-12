-- 办公产品导入模块数据库初始化脚本
-- 先创建数据库：CREATE DATABASE office OWNER postgres;

-- 用户认证；密码只保存 PBKDF2 加盐哈希，业务数据仍按现有 project_id 隔离。
create table if not exists office_user (
    user_id varchar(64) primary key,
    username varchar(128) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(255) not null,
    role varchar(32) not null default 'USER',
    status varchar(20) not null default 'active',
    last_login_at timestamp,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

-- 登录会话；退出登录立即删除记录实现服务端吊销，过期由 expires_at 判定。
create table if not exists office_user_session (
    session_id varchar(64) primary key,
    user_id varchar(64) not null references office_user(user_id),
    remember_me boolean not null default false,
    expires_at timestamp not null,
    last_used_at timestamp,
    created_ip varchar(64),
    created_at timestamp not null default now()
);
create index if not exists idx_office_user_session_user on office_user_session(user_id);

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

-- 图片资产（office 自有图纸/上传文件登记；商品图片统一存主项目，本表不再关联商品表）
create table if not exists image_assets (
    image_id varchar(64) primary key,
    -- 历史商品关联字段（主项目 rspu/variant id，弱关联不加外键）
    rspu_id varchar(64),
    variant_id varchar(64),
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

create table if not exists office_design_project (
    project_id varchar(64) primary key,
    name varchar(200) not null,
    customer_requirements text not null default '',
    requirements_revision integer not null default 1,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create table if not exists office_design_floor (
    floor_id varchar(64) primary key,
    project_id varchar(64) not null references office_design_project(project_id),
    name varchar(200) not null,
    sort_order integer not null default 0,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);
create index if not exists idx_office_design_floor_project on office_design_floor(project_id, sort_order, created_at);
create unique index if not exists uk_office_design_floor_name on office_design_floor(project_id, lower(name));

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
