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
    goods_total numeric(14,2) not null default 0,
    service_fee numeric(14,2) not null default 0,
    logistics_fee numeric(14,2) not null default 0,
    total_amount numeric(14,2) not null default 0,
    line_count integer not null default 0,
    missing_price_lines integer not null default 0,
    snapshot_json jsonb not null default '{}'::jsonb,
    created_by varchar(64),
    created_at timestamp not null default now(),
    superseded_at timestamp
);
create index if not exists idx_office_quotation_project on office_quotation(project_id, revision desc);
create unique index if not exists uk_office_quotation_confirmed on office_quotation(project_id) where status = 'CONFIRMED';

create table if not exists office_quotation_line (
    line_id varchar(64) primary key,
    quotation_id varchar(64) not null references office_quotation(quotation_id) on delete cascade,
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
    subtotal numeric(14,2),
    issue varchar(255),
    sort_order integer not null default 0
);
create index if not exists idx_office_quotation_line_quotation on office_quotation_line(quotation_id, sort_order);
