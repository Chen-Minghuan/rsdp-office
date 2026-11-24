-- 只退役空的旧助手消息表，保留其他环境可能仍有的历史记录和依赖对象。
DO $migration$
BEGIN
    IF to_regclass('office_agent_message') IS NOT NULL THEN
        LOCK TABLE office_agent_message IN ACCESS EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM office_agent_message) THEN
            RAISE EXCEPTION 'office_agent_message 仍有历史消息，停止清理';
        END IF;
        DROP TABLE office_agent_message RESTRICT;
    END IF;
END
$migration$;
