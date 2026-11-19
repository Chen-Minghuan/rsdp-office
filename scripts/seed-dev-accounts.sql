-- 本地开发账号种子。先启动 Java 后端完成数据库迁移，再从仓库根目录执行：
-- docker compose exec -T postgres psql -X -U postgres -d office < scripts/seed-dev-accounts.sql
\set ON_ERROR_STOP on

BEGIN;

LOCK TABLE office_user IN SHARE ROW EXCLUSIVE MODE;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TEMP TABLE dev_created_credentials (
    username TEXT NOT NULL,
    password TEXT NOT NULL
);

-- PostgreSQL 无内置 PBKDF2。用 pgcrypto 的 HMAC 推导一个 256 位块，
-- 迭代次数和 URL-safe Base64 格式与 Java PasswordHasher 一致。
CREATE FUNCTION pg_temp.dev_password_hash(password TEXT) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    salt BYTEA := gen_random_bytes(16);
    password_bytes BYTEA := convert_to(password, 'UTF8');
    digest BYTEA := hmac(salt || decode('00000001', 'hex'), password_bytes, 'sha256');
    derived BIT(256) := ('x' || encode(digest, 'hex'))::BIT(256);
    derived_bytes BYTEA;
BEGIN
    FOR iteration IN 2..210000 LOOP
        digest := hmac(digest, password_bytes, 'sha256');
        derived := derived # ('x' || encode(digest, 'hex'))::BIT(256);
    END LOOP;

    SELECT decode(string_agg(lpad(to_hex(substring(derived, offset_bit, 64)::BIGINT), 16, '0'),
                             '' ORDER BY offset_bit), 'hex')
    INTO derived_bytes
    FROM generate_series(1, 256, 64) AS offsets(offset_bit);

    RETURN 'pbkdf2-sha256$210000$'
        || translate(encode(salt, 'base64'), '+/=', '-_') || '$'
        || translate(encode(derived_bytes, 'base64'), '+/=', '-_');
END;
$$;

DO $$
DECLARE
    account RECORD;
    account_id TEXT;
    new_password TEXT;
    dev_workspace CONSTANT TEXT := 'DEV-OFFICE-WORKSPACE';
BEGIN
    FOR account IN SELECT * FROM (VALUES
        (1, 'admin',    '开发系统管理员', 'ADMIN', NULL),
        (2, 'dealer',   '开发经销商主号', 'DEALER', 'DEALER_STAFF'),
        (3, 'lead',     '开发项目负责人', 'USER', 'DEALER_STAFF'),
        (4, 'designer', '开发项目协作者', 'USER', 'DEALER_STAFF'),
        (5, 'factory',  '开发厂家业务员', 'USER', 'FACTORY_SALES')
    ) AS accounts(position, username, display_name, role, source) ORDER BY position LOOP
        account_id := 'DEV-OFFICE-DEV_' || upper(account.username);

        -- 早期脚本生成的测试账号沿用原用户 ID，只迁移登录名。
        UPDATE office_user
        SET username = account.username, updated_at = CURRENT_TIMESTAMP
        WHERE user_id = account_id AND username = 'dev_' || account.username
            AND role = account.role;
        IF FOUND THEN
            CONTINUE;
        END IF;

        IF account.role = 'ADMIN' AND EXISTS (SELECT 1 FROM office_user WHERE role = 'ADMIN') THEN
            RAISE NOTICE '已有系统管理员，沿用现有账号';
            CONTINUE;
        END IF;

        IF EXISTS (SELECT 1 FROM office_user WHERE user_id = account_id AND username = account.username
                   AND role = account.role) THEN
            CONTINUE;
        END IF;

        new_password := encode(gen_random_bytes(18), 'hex');
        INSERT INTO office_user (user_id, username, password_hash, display_name, role, source, workspace_id)
        VALUES (account_id, account.username, pg_temp.dev_password_hash(new_password),
                account.display_name, account.role, account.source,
                CASE WHEN account.role = 'USER' THEN dev_workspace END);

        IF account.role = 'DEALER' THEN
            INSERT INTO office_workspace (workspace_id, name, owner_user_id)
            VALUES (dev_workspace, '本地开发经销商', account_id);

            UPDATE office_user SET workspace_id = dev_workspace WHERE user_id = account_id;
        END IF;

        INSERT INTO dev_created_credentials VALUES (account.username, new_password);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM office_workspace
        WHERE workspace_id = dev_workspace AND owner_user_id = 'DEV-OFFICE-DEV_DEALER'
    ) THEN
        RAISE EXCEPTION '开发工作区的归属与种子数据不一致';
    END IF;

END;
$$;

UPDATE office_user
SET display_name = '开发项目协作者', updated_at = CURRENT_TIMESTAMP
WHERE user_id = 'DEV-OFFICE-DEV_DESIGNER' AND username = 'designer'
    AND display_name = '开发设计师';

COMMIT;

-- 仅本次新建账号会显示密码。保存在本机，勿上传运行输出。
SELECT username, password FROM dev_created_credentials ORDER BY username;
DROP TABLE dev_created_credentials;
DROP FUNCTION pg_temp.dev_password_hash(TEXT);
