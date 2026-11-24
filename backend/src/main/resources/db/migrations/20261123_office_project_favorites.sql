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
