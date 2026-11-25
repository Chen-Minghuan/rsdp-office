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
