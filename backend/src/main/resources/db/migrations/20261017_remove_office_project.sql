-- Remove project-profile storage without deleting designs or changing their scope IDs.
-- Run in the same transaction as startup initialization (or explicitly with psql -1).
-- No CASCADE: unexpected dependent views/objects must fail instead of being deleted.
DO $migration$
DECLARE
    profile_table regclass := to_regclass('office_project');
    dependency record;
BEGIN
    IF profile_table IS NOT NULL THEN
        FOR dependency IN
            SELECT conrelid::regclass AS child_table, conname
            FROM pg_constraint
            WHERE contype = 'f' AND confrelid = profile_table
        LOOP
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', dependency.child_table, dependency.conname);
        END LOOP;
        EXECUTE format('DROP TABLE %s', profile_table);
    END IF;
END
$migration$;
