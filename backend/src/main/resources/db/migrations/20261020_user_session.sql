-- 登录会话表：支持记住我、滑动续期与退出吊销
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
