package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.config.SchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RSDP_TEST_DATABASE_URL", matches = ".+")
class LegacyStorageMigrationTest {

    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();
    private final String schema = "test_legacy_storage_cleanup_" + UUID.randomUUID().toString().replace("-", "");
    private Connection connection;

    @BeforeEach
    void open() throws SQLException {
        dataSource.setURL(System.getenv("RSDP_TEST_DATABASE_URL"));
        dataSource.setUser(System.getenv().getOrDefault("RSDP_TEST_DATABASE_USER", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("RSDP_TEST_DATABASE_PASSWORD", "postgres"));
        try (var setup = dataSource.getConnection(); var sql = setup.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        dataSource.setCurrentSchema(schema);
        connection = dataSource.getConnection();
    }

    @AfterEach
    void close() throws SQLException {
        if (connection == null) return;
        try (var opened = connection; var sql = opened.createStatement()) {
            sql.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void startupRemovesEmptyLegacyStorageAndKeepsActiveRecords() throws SQLException {
        prepareLegacyStorage();
        var initializer = new SchemaInitializer(dataSource);
        initializer.init();
        initializer.init();

        assertFalse(result("SELECT to_regclass('floor_plan_furniture') IS NOT NULL"));
        assertFalse(result("SELECT to_regclass('office_agent_message') IS NOT NULL"));
        assertTrue(result("""
            SELECT EXISTS (SELECT 1 FROM office_design_project
                WHERE project_id = 'P' AND customer_requirements = '保留客户需求')
            """));
        assertFalse(result("""
            SELECT EXISTS (SELECT 1 FROM pg_attribute
                WHERE attrelid = 'floor_plan_analysis'::regclass AND NOT attisdropped
                AND attname IN ('confirmed_rooms', 'layout_json', 'layout_revision'))
            """));
        assertTrue(result("""
            SELECT EXISTS (SELECT 1 FROM floor_plan_analysis a
                JOIN floor_plan_scene_revision r USING (analysis_id)
                JOIN floor_plan_room m ON m.analysis_id = r.analysis_id AND m.analysis_revision = r.analysis_revision
                WHERE a.analysis_id = 'A' AND a.raw_result ->> 'source' = 'original'
                    AND r.raw_result ->> 'source' = 'revision' AND m.room_id = 'R')
            """));
    }

    @Test
    void startupPreservesLegacyFurnitureRowsIfAnotherDatabaseStillHasThem() throws SQLException {
        prepareLegacyStorage();
        execute("INSERT INTO floor_plan_furniture VALUES ('F', 'A')");
        var failure = assertThrows(IllegalStateException.class, () -> new SchemaInitializer(dataSource).init());

        assertTrue(failure.getCause() instanceof ScriptStatementFailedException);
        assertTrue(failure.getCause().getCause().getMessage().contains("floor_plan_furniture 仍有历史记录"));
        assertTrue(result("SELECT EXISTS (SELECT 1 FROM floor_plan_furniture WHERE furniture_id = 'F')"));
    }

    @ParameterizedTest
    @CsvSource(value = {"confirmed_rooms|'[]'", "layout_json|'{}'", "layout_revision|2"}, delimiter = '|', quoteCharacter = '"')
    void startupRollsBackTableDropIfAnOldColumnStillContainsData(String column, String value) throws SQLException {
        prepareLegacyStorage();
        execute("UPDATE floor_plan_analysis SET " + column + " = " + value + " WHERE analysis_id = 'A'");
        var failure = assertThrows(IllegalStateException.class, () -> new SchemaInitializer(dataSource).init());

        assertTrue(failure.getCause().getCause().getMessage().contains("floor_plan_analysis." + column + " 仍有历史记录"));
        assertTrue(result("SELECT to_regclass('floor_plan_furniture') IS NOT NULL"));
        assertTrue(result("SELECT " + column + " = " + value + " FROM floor_plan_analysis WHERE analysis_id = 'A'"));
    }

    @Test
    void startupPreservesAgentMessagesAndRollsBackPriorCleanupIfHistoryExists() throws SQLException {
        prepareLegacyStorage();
        execute("INSERT INTO office_agent_message VALUES (1, 'FLOOR', '历史对话')");
        var failure = assertThrows(IllegalStateException.class, () -> new SchemaInitializer(dataSource).init());

        assertTrue(failure.getCause().getCause().getMessage().contains("office_agent_message 仍有历史消息"));
        assertTrue(result("SELECT EXISTS (SELECT 1 FROM office_agent_message WHERE message_id = 1)"));
        assertTrue(result("SELECT to_regclass('floor_plan_furniture') IS NOT NULL"));
    }

    private void prepareLegacyStorage() throws SQLException {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        execute("""
            ALTER TABLE floor_plan_analysis
                ADD COLUMN confirmed_rooms jsonb, ADD COLUMN layout_json jsonb, ADD COLUMN layout_revision bigint DEFAULT 1;
            CREATE TABLE floor_plan_furniture (
                furniture_id text PRIMARY KEY, analysis_id varchar(64) REFERENCES floor_plan_analysis(analysis_id)
            );
            CREATE TABLE office_agent_message (message_id bigint PRIMARY KEY, project_id text, content text);
            INSERT INTO office_design_project (project_id, name, customer_requirements)
                VALUES ('P', '测试项目', '保留客户需求');
            INSERT INTO floor_plan_analysis (analysis_id, input_mode, raw_result) VALUES ('A', 'cad', '{"source":"original"}');
            INSERT INTO floor_plan_scene_revision (analysis_id, analysis_revision, revision_trigger, raw_result)
                VALUES ('A', 1, 'INITIAL_PARSE', '{"source":"revision"}');
            INSERT INTO floor_plan_room (room_id, analysis_id, analysis_revision, room_type)
                VALUES ('R', 'A', 1, 'office');
            """);
    }

    private void execute(String statement) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private boolean result(String statement) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(statement)) {
            assertTrue(rows.next());
            return rows.getBoolean(1);
        }
    }
}
