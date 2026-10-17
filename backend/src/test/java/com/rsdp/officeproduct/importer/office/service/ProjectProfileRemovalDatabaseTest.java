package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in PostgreSQL integration; creates and drops only a uniquely named test schema. */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_DATABASE_URL", matches = ".+")
class ProjectProfileRemovalDatabaseTest {
    private final PGSimpleDataSource dataSource = new PGSimpleDataSource();
    private final String schema = "test_profile_removal_" + UUID.randomUUID().toString().replace("-", "");
    private Connection connection;

    @BeforeEach void open() throws Exception {
        dataSource.setURL(System.getenv("RSDP_TEST_DATABASE_URL"));
        dataSource.setUser(System.getenv().getOrDefault("RSDP_TEST_DATABASE_USER", "postgres"));
        dataSource.setPassword(System.getenv().getOrDefault("RSDP_TEST_DATABASE_PASSWORD", "postgres"));
        connection = dataSource.getConnection();
        connection.createStatement().execute("CREATE SCHEMA " + schema);
        connection.createStatement().execute("SET search_path TO " + schema);
        connection.setAutoCommit(false);
    }

    @AfterEach void close() throws Exception {
        if (connection == null) return;
        try {
            connection.rollback();
            connection.setAutoCommit(true);
            connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
        } finally { connection.close(); }
    }

    private void cleanup() {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("db/migrations/20261017_remove_office_project.sql")),
            false, false, "--", ScriptUtils.EOF_STATEMENT_SEPARATOR, "/*", "*/");
    }

    @Test void migrationDropsOnlyProfileAndPreservesAllDependentDataAndIds() throws Exception {
        var sql = connection.createStatement();
        sql.execute("CREATE TABLE office_project(project_id varchar(64) PRIMARY KEY, project_name text, context_json jsonb)");
        sql.execute("INSERT INTO office_project VALUES ('P', 'removed profile', '{\"headcount\":60}')");
        var children = List.of("office_canvas", "office_cart", "office_agent_message", "floor_plan_analysis", "floor_plan_document", "floor_plan_design_scene");
        for (String table : children) {
            sql.execute("CREATE TABLE " + table + "(id text PRIMARY KEY, project_id varchar(64) REFERENCES office_project(project_id), snapshot jsonb)");
            sql.execute("INSERT INTO " + table + " VALUES ('stable-id', 'P', '{\"polygon\":[[0,0],[1200,0],[1200,600],[0,600]]}')");
        }
        connection.commit();
        cleanup();
        connection.commit();
        cleanup(); // Idempotent on an already migrated database.
        assertFalse(tableExists("office_project"));
        for (String table : children) {
            try (var result = sql.executeQuery("SELECT id, project_id, snapshot -> 'polygon' AS polygon FROM " + table)) {
                assertTrue(result.next()); assertEquals("stable-id", result.getString("id")); assertEquals("P", result.getString("project_id"));
                assertEquals("[[0, 0], [1200, 0], [1200, 600], [0, 600]]", result.getString("polygon")); assertFalse(result.next());
            }
            sql.execute("INSERT INTO " + table + " VALUES ('new-id', 'NEW-WORKSPACE', '{}')");
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var stmt = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            stmt.setString(1, table);
            try (var result = stmt.executeQuery()) { result.next(); return result.getBoolean(1); }
        }
    }

    @Test void freshSchemaDoesNotRecreateProjectProfiles() throws Exception {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        cleanup();
        assertFalse(tableExists("office_project"));
        assertTrue(tableExists("office_canvas")); assertTrue(tableExists("floor_plan_analysis"));
    }

    @Test void advisoryLockSerializesSameWorkspaceWithoutAProjectRow() throws Exception {
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        config.addMapper(OfficeWorkspaceLockMapper.class);
        var sessions = new SqlSessionFactoryBuilder().build(config);
        String scope = "test-lock-" + UUID.randomUUID();
        try (var first = sessions.openSession(false); var second = sessions.openSession(false)) {
            assertEquals(1, first.getMapper(OfficeWorkspaceLockMapper.class).lockWorkspace(scope));
            assertEquals(1, second.getMapper(OfficeWorkspaceLockMapper.class).lockWorkspace(scope + "-other"));
            second.getConnection().createStatement().execute("SET LOCAL lock_timeout = '150ms'");
            assertThrows(PersistenceException.class, () -> second.getMapper(OfficeWorkspaceLockMapper.class).lockWorkspace(scope));
            second.rollback(true); first.commit(true);
            assertEquals(1, second.getMapper(OfficeWorkspaceLockMapper.class).lockWorkspace(scope));
            second.rollback(true);
        }
    }
}
