-- Database-backed users for the single-account first release.
CREATE TABLE IF NOT EXISTS office_user (
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
