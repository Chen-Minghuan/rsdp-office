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
    requirements_summary jsonb,
    requirements_summary_revision integer,
    requirements_summary_model varchar(100),
    requirements_summary_prompt_version varchar(64),
    requirements_summary_generated_at timestamp,
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
    quality_issues jsonb,
    cad_object_key varchar(256),
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

-- 项目报价：确认后只增不改，同一项目仅一条 CONFIRMED，历史版本置 SUPERSEDED。
create table if not exists office_quotation (
    quotation_id varchar(64) primary key,
    project_id varchar(64) not null,
    -- 确认时的项目名称快照：项目改名后历史报价单仍显示当时名称；业务关联仍以 project_id 为准
    project_name varchar(200) not null,
    workspace_id varchar(64),
    revision integer not null default 1,
    status varchar(20) not null default 'CONFIRMED',
    pricing_mode varchar(20) not null,
    pricing_value numeric(12,4) not null,
    total_amount numeric(14,2) not null default 0,
    fees_json jsonb not null default '[]'::jsonb,
    created_by varchar(64),
    created_at timestamp not null default now(),
    superseded_at timestamp,
    constraint ck_office_quotation_fees_array check (jsonb_typeof(fees_json) = 'array')
);
create unique index if not exists uk_office_quotation_project_revision on office_quotation(project_id, revision);
create unique index if not exists uk_office_quotation_confirmed on office_quotation(project_id) where status = 'CONFIRMED';

create table if not exists office_quotation_line (
    quotation_id varchar(64) not null references office_quotation(quotation_id) on delete cascade,
    line_no integer not null,
    floor_id varchar(64),
    floor_name varchar(200),
    zone_id varchar(64),
    zone_name varchar(200),
    product_id varchar(128) not null,
    rspu_id varchar(128),
    product_name varchar(255),
    variant varchar(255),
    image_url varchar(500),
    qty integer not null default 1,
    factory_price numeric(14,2),
    unit_price numeric(14,2),
    issue varchar(255),
    primary key (quotation_id, line_no)
);

-- 个人商品收藏：默认收藏夹 + 用户自建分组；RSDP 商品仅存 rspu_id 弱关联。
create table if not exists office_favorite_folder (
    folder_id varchar(64) primary key,
    user_id varchar(64) not null references office_user(user_id) on delete cascade,
    name varchar(50) not null,
    is_default boolean not null default false,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ck_office_favorite_folder_name check (char_length(btrim(name)) > 0)
);
create unique index if not exists uk_office_favorite_folder_user_name
    on office_favorite_folder(user_id, name);
create unique index if not exists uk_office_favorite_folder_user_default
    on office_favorite_folder(user_id) where is_default;

create table if not exists office_favorite_item (
    folder_id varchar(64) not null references office_favorite_folder(folder_id) on delete cascade,
    rspu_id varchar(128) not null,
    created_at timestamp not null default now(),
    primary key (folder_id, rspu_id),
    constraint ck_office_favorite_item_rspu check (char_length(btrim(rspu_id)) > 0)
);
create index if not exists idx_office_favorite_item_rspu on office_favorite_item(rspu_id);

-- 项目商品收藏：每个成员在每个项目下各自收藏；RSDP 商品仅存 rspu_id 弱关联。
create table if not exists office_project_favorite_item (
    project_id varchar(64) not null references office_design_project(project_id) on delete cascade,
    user_id varchar(64) not null references office_user(user_id) on delete cascade,
    rspu_id varchar(128) not null,
    created_at timestamp not null default now(),
    primary key (project_id, user_id, rspu_id),
    constraint ck_office_project_favorite_rspu check (char_length(btrim(rspu_id)) > 0)
);
create index if not exists idx_office_project_favorite_rspu on office_project_favorite_item(rspu_id);
