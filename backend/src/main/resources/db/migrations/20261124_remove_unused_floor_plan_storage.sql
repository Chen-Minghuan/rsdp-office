-- 检查与删除共用事务和表锁，避免清理期间写入旧数据。
-- 有历史数据或依赖对象时停止迁移，保留所有记录。
DO $migration$
DECLARE
    legacy_columns constant text[] := ARRAY['confirmed_rooms', 'layout_json', 'layout_revision'];
    legacy_column text;
    has_data boolean;
BEGIN
    IF to_regclass('floor_plan_furniture') IS NOT NULL THEN
        LOCK TABLE floor_plan_furniture IN ACCESS EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM floor_plan_furniture) THEN
            RAISE EXCEPTION 'floor_plan_furniture 仍有历史记录，停止清理';
        END IF;
        DROP TABLE floor_plan_furniture RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('floor_plan_analysis')
            AND NOT attisdropped AND attname = ANY(legacy_columns)
    ) THEN
        RETURN;
    END IF;

    LOCK TABLE floor_plan_analysis IN ACCESS EXCLUSIVE MODE;
    FOR legacy_column IN
        SELECT attname FROM pg_attribute
        WHERE attrelid = 'floor_plan_analysis'::regclass
            AND NOT attisdropped AND attname = ANY(legacy_columns)
    LOOP
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM floor_plan_analysis WHERE %I %s)',
            legacy_column, CASE WHEN legacy_column = 'layout_revision' THEN '<> 1' ELSE 'IS NOT NULL' END)
            INTO has_data;
        IF has_data THEN
            RAISE EXCEPTION 'floor_plan_analysis.% 仍有历史记录，停止清理', legacy_column;
        END IF;
        EXECUTE format('ALTER TABLE floor_plan_analysis DROP COLUMN %I RESTRICT', legacy_column);
    END LOOP;
END
$migration$;
