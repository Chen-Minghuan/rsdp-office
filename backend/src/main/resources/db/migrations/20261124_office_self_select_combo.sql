-- 自选组合：每账号最多10个组合、每组合最多5个商品（上限由服务层校验）；商品仅存 rspu_id 弱关联。
create table if not exists office_combo (
    combo_id varchar(64) primary key,
    user_id varchar(64) not null references office_user(user_id) on delete cascade,
    name varchar(50) not null,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ck_office_combo_name check (char_length(btrim(name)) > 0)
);
create unique index if not exists uk_office_combo_user_name
    on office_combo(user_id, name);

create table if not exists office_combo_item (
    combo_id varchar(64) not null references office_combo(combo_id) on delete cascade,
    rspu_id varchar(128) not null,
    sort_order integer not null default 0,
    created_at timestamp not null default now(),
    primary key (combo_id, rspu_id),
    constraint ck_office_combo_item_rspu check (char_length(btrim(rspu_id)) > 0)
);
create index if not exists idx_office_combo_item_rspu on office_combo_item(rspu_id);
