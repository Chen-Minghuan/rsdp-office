-- 个人商品收藏：默认收藏夹 + 用户自建分组；商品主档保留在 RSDP，仅存 rspu_id 弱关联。
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
