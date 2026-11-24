alter table office_user add column if not exists product_entry_enabled boolean not null default false;

create table if not exists office_product (
    product_id varchar(64) primary key,
    workspace_id varchar(64) not null references office_workspace(workspace_id),
    name varchar(200) not null,
    category_code varchar(16) not null,
    factory_code varchar(16) not null,
    factory_model varchar(64) not null,
    revision integer not null check (revision > 0),
    created_by varchar(64) not null references office_user(user_id),
    updated_by varchar(64) not null references office_user(user_id),
    updated_at timestamptz not null default now(),
    unique (workspace_id, product_id),
    unique (workspace_id, factory_code, factory_model)
);
create index if not exists idx_office_product_workspace_updated on office_product(workspace_id, updated_at desc, product_id);

create table if not exists office_product_variant (
    variant_id varchar(64) primary key,
    workspace_id varchar(64) not null,
    product_id varchar(64) not null,
    display_name varchar(128) not null,
    width_mm numeric(10,2) not null check (width_mm > 0),
    depth_mm numeric(10,2) not null check (depth_mm > 0),
    height_mm numeric(10,2) check (height_mm > 0),
    size_text varchar(64),
    color_text varchar(64),
    material_text varchar(128),
    position integer not null,
    active boolean not null default true,
    unique (workspace_id, product_id, variant_id),
    foreign key (workspace_id, product_id) references office_product(workspace_id, product_id)
);

create table if not exists office_product_image (
    image_id varchar(64) primary key,
    workspace_id varchar(64) not null,
    product_id varchar(64) not null,
    variant_id varchar(64),
    media_type varchar(32) not null,
    content bytea not null,
    position integer,
    foreign key (workspace_id, product_id) references office_product(workspace_id, product_id),
    foreign key (workspace_id, product_id, variant_id) references office_product_variant(workspace_id, product_id, variant_id)
);

create table if not exists office_product_outbox (
    workspace_id varchar(64) not null references office_workspace(workspace_id),
    batch_id uuid not null,
    actor_id varchar(64) not null references office_user(user_id),
    request_hash varchar(64) not null,
    payload jsonb,
    result jsonb not null,
    state varchar(16) not null default 'PENDING'
        check (state in ('PENDING','SENDING','DELIVERED','FAILED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    claim_id uuid,
    review_id varchar(64),
    last_error varchar(200),
    created_at timestamptz not null default now(),
    primary key (workspace_id, batch_id)
);
create index if not exists idx_office_product_outbox_ready
    on office_product_outbox(next_attempt_at, created_at) where state in ('PENDING','SENDING');
