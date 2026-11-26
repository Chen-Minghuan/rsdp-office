package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class OfficeProjectStatusPostgresTest {
    @Test void backfillsLegacyDerivedStageAndStaysIdempotent() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "project_status_backfill_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("""
                    create table office_design_project (
                        project_id varchar(64) primary key, name text not null,
                        status varchar(20) not null default 'DESIGNING'
                        constraint ck_office_project_status check (status in ('DESIGNING', 'DELIVERING', 'COMPLETED')))
                    """);
                statement.execute("create table office_design_floor (floor_id varchar(64) primary key, project_id varchar(64) not null)");
                statement.execute("""
                    create table office_proposal_confirmation (
                        confirmation_id varchar(64) primary key, floor_id varchar(64) not null, retired boolean not null default false)
                    """);
                statement.execute("insert into office_design_project(project_id,name) values ('ALL-CONFIRMED','全确认'), ('PARTIAL','部分确认'), ('NO-FLOOR','无楼层'), ('RETIRED','确认失效'), ('DONE','人工已完成')");
                statement.execute("update office_design_project set status='COMPLETED' where project_id='DONE'");
                statement.execute("""
                    insert into office_design_floor(floor_id, project_id) values
                        ('F1','ALL-CONFIRMED'), ('F2','ALL-CONFIRMED'),
                        ('F3','PARTIAL'), ('F4','PARTIAL'),
                        ('F5','RETIRED'), ('F6','DONE')
                    """);
                statement.execute("""
                    insert into office_proposal_confirmation(confirmation_id, floor_id, retired) values
                        ('C1','F1',false), ('C2','F2',false),
                        ('C3','F3',false),
                        ('C5','F5',true),
                        ('C6','F6',false)
                    """);
                var backfill = new ClassPathResource("db/migrations/20261126_project_status_backfill.sql");
                ScriptUtils.executeSqlScript(connection, backfill);
                ScriptUtils.executeSqlScript(connection, backfill);
                assertEquals("DELIVERING", status(statement, "ALL-CONFIRMED"));
                assertEquals("DESIGNING", status(statement, "PARTIAL"));
                assertEquals("DESIGNING", status(statement, "NO-FLOOR"));
                assertEquals("DESIGNING", status(statement, "RETIRED"));
                assertEquals("COMPLETED", status(statement, "DONE"), "人工已完成的存量项目不被回填覆盖");
                // 回填只执行一次：负责人此后把项目改回设计中，重启重跑脚本不得覆盖
                statement.execute("update office_design_project set status='DESIGNING' where project_id='ALL-CONFIRMED'");
                ScriptUtils.executeSqlScript(connection, backfill);
                assertEquals("DESIGNING", status(statement, "ALL-CONFIRMED"), "一次性回填不覆盖回填后的人工修改");
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static String status(java.sql.Statement statement, String projectId) throws Exception {
        try (var rs = statement.executeQuery("select status from office_design_project where project_id='" + projectId + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test void migratesLegacyProjectsAndKeepsStatusIndependentFromOtherEdits() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "project_status_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("""
                    create table office_design_project (
                        project_id varchar(64) primary key, name text not null,
                        customer_requirements text default '', requirements_revision int default 1,
                        requirements_summary jsonb, requirements_summary_revision int,
                        requirements_summary_model text, requirements_summary_prompt_version text,
                        requirements_summary_generated_at timestamp, workspace_id varchar(64), reference_images jsonb,
                        created_at timestamp default now(), updated_at timestamp default now())
                    """);
                statement.execute("insert into office_design_project(project_id,name) values ('OLD','旧项目')");
                var migration = new ClassPathResource("db/migrations/20261124_project_status.sql");
                ScriptUtils.executeSqlScript(connection, migration);
                ScriptUtils.executeSqlScript(connection, migration);
            }
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OfficeDesignProjectMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(scoped);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(true)) {
                var projects = session.getMapper(OfficeDesignProjectMapper.class);
                var stale = projects.selectById("OLD");
                assertEquals("DESIGNING", stale.getStatus());
                var fresh = new OfficeDesignProject();
                fresh.setProjectId("NEW");
                fresh.setName("新项目");
                projects.insert(fresh);
                assertEquals("DESIGNING", projects.selectById("NEW").getStatus());
                projects.updateStatus("OLD", "DELIVERING", LocalDateTime.now());
                stale.setName("改名不覆盖阶段");
                projects.updateById(stale);
                assertEquals("DELIVERING", projects.selectById("OLD").getStatus());
                projects.updateStatus("OLD", "COMPLETED", LocalDateTime.now());
                assertEquals("COMPLETED", projects.selectById("OLD").getStatus());
            }
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.execute("update office_design_project set status='INVALID' where project_id='OLD'"));
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
