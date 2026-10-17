-- Spatial Scene 首期存储模型迁移
-- 依据 docs/spatial/api-storage-contract.md V1.0

-- 来源文档资产（复用 image_assets 原文件引用）
create table if not exists floor_plan_document (
    asset_id varchar(64) primary key references image_assets(image_id),
    project_id varchar(64),
    sha256 varchar(64),
    media_type varchar(64) not null,
    original_filename varchar(255),
    created_by varchar(64),
    created_at timestamp not null default now()
);

create index if not exists idx_fpd_project on floor_plan_document(project_id);

-- 文档页：一个上传文件对应一页（首期不拆分 PDF 多页）
create table if not exists floor_plan_page (
    page_id varchar(64) primary key,
    asset_id varchar(64) not null references floor_plan_document(asset_id),
    page_number integer not null default 1,
    processing_revision integer not null default 1,
    width_px integer not null,
    height_px integer not null,
    underlay_asset_id varchar(64) references image_assets(image_id),
    page_transform jsonb, -- 裁剪/旋转/缩放记录
    created_by varchar(64),
    created_at timestamp not null default now(),
    unique (asset_id, page_number, processing_revision)
);

create index if not exists idx_fpp_asset on floor_plan_page(asset_id);

-- 不可变识别原始结果
create table if not exists floor_plan_source_scene (
    source_scene_id varchar(64) primary key,
    page_id varchar(64) not null references floor_plan_page(page_id),
    analysis_id varchar(64) references floor_plan_analysis(analysis_id),
    analysis_revision integer,
    run_id varchar(64),
    input_hash varchar(64),
    service_version varchar(128),
    parse_status varchar(16) not null default 'PENDING',
    source_json jsonb not null,
    raw_result_hash varchar(64),
    created_by varchar(64),
    created_at timestamp not null default now(),
    unique (analysis_id, analysis_revision)
);

create index if not exists idx_fpss_page on floor_plan_source_scene(page_id);

-- 可编辑设计稿头表
create table if not exists floor_plan_design_scene (
    scene_id varchar(64) primary key,
    project_id varchar(64) not null,
    page_id varchar(64) not null references floor_plan_page(page_id),
    source_scene_id varchar(64) references floor_plan_source_scene(source_scene_id),
    failed_run_id varchar(64),
    current_revision bigint not null default 1,
    scope_revision bigint not null default 1,
    created_by varchar(64),
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);

create index if not exists idx_fpds_project_page on floor_plan_design_scene(project_id, page_id);

-- 不可变设计修订快照
create table if not exists floor_plan_design_revision (
    scene_id varchar(64) not null references floor_plan_design_scene(scene_id),
    revision bigint not null,
    parent_revision bigint,
    client_change_id varchar(64) not null,
    action varchar(32) not null, -- CREATE / DRAFT / CALIBRATION / CONFIRM
    snapshot_json jsonb not null,
    created_by varchar(64),
    created_at timestamp not null default now(),
    primary key (scene_id, revision)
);

-- 校准记录
create table if not exists floor_plan_calibration (
    scene_id varchar(64) not null references floor_plan_design_scene(scene_id),
    calibration_revision bigint not null,
    page_id varchar(64) not null references floor_plan_page(page_id),
    reference_points jsonb not null,
    reference_length_mm decimal(18, 4) not null,
    mm_per_source_unit decimal(18, 12) not null,
    source_to_world jsonb not null,
    basis varchar(32) not null default 'TWO_POINT',
    status varchar(16) not null default 'UNKNOWN',
    created_by varchar(64),
    created_at timestamp not null default now(),
    primary key (scene_id, calibration_revision)
);

-- 确认快照
create table if not exists floor_plan_confirmation (
    confirmation_id varchar(64) primary key,
    scene_id varchar(64) not null references floor_plan_design_scene(scene_id),
    kind varchar(16) not null, -- SPACE / SCENE
    space_id varchar(64),
    scene_revision bigint not null,
    geometry_revision bigint,
    requirement_revision bigint,
    calibration_revision bigint not null,
    scope_revision bigint,
    geometry_policy_version varchar(32) not null default 'GEOM-P1-1',
    snapshot_json jsonb not null,
    created_by varchar(64),
    created_at timestamp not null default now()
);

create index if not exists idx_fpc_scene on floor_plan_confirmation(scene_id);
create index if not exists idx_fpc_scene_space on floor_plan_confirmation(scene_id, space_id);

-- 操作回执（幂等键）
create table if not exists floor_plan_operation_receipt (
    actor_id varchar(64) not null,
    scope_key varchar(128) not null,
    client_change_id varchar(64) not null,
    action varchar(32) not null,
    request_hash varchar(64) not null,
    http_status integer not null,
    response_json jsonb not null,
    scene_id varchar(64) references floor_plan_design_scene(scene_id),
    scene_revision bigint,
    created_at timestamp not null default now(),
    primary key (actor_id, scope_key, client_change_id)
);

-- 外键：设计稿当前修订指向存在的修订行（延迟检查，支持同一事务写入）
-- 数据库层由应用保证，首期不添加即时外键以避免事务顺序依赖

-- 为现有 analysis 回填最小 document/page/source_scene（可选回填脚本，首期不自动执行）
-- 回填时须由业务服务明确来源与证据类型，不能自动将演示数据标为真实服务
