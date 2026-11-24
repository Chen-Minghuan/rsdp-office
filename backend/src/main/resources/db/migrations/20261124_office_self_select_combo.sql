-- 自选组合：单表保存，rspu_ids 数组顺序即展示顺序；每账号最多10个组合由服务层校验，数组最多5项。
create table if not exists office_combo (
    combo_id varchar(64) primary key,
    user_id varchar(64) not null references office_user(user_id) on delete cascade,
    name varchar(50) not null,
    rspu_ids varchar(128)[] not null default '{}',
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    constraint ck_office_combo_name check (char_length(btrim(name)) > 0),
    constraint ck_office_combo_rspu_ids_max check (coalesce(cardinality(rspu_ids), 0) <= 5)
);
create unique index if not exists uk_office_combo_user_name
    on office_combo(user_id, name);
