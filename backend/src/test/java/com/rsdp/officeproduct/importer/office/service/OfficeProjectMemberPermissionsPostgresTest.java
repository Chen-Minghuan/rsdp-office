package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目成员权限位的真实数据库验证：迁移回填、check 约束、落库回读。
 * 仅在配置 RSDP_TEST_POSTGRES_URL 时运行；未配置时跳过，不计为数据库验收。
 */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class OfficeProjectMemberPermissionsPostgresTest {
    @Test void migrationAddsPermissionFlagsAndBackfillsLegacyMembers() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "member_permissions_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("create table office_user (user_id varchar(64) primary key, role varchar(20))");
                statement.execute("create table office_workspace (workspace_id varchar(64) primary key, created_at timestamp default now())");
                // 迁移前的历史表结构：无 permission_flags 列
                statement.execute("""
                    create table office_project_member (
                        project_id varchar(64) not null, user_id varchar(64) not null,
                        role varchar(20) not null, created_at timestamp default now(),
                        primary key (project_id, user_id))
                    """);
                statement.execute("""
                    insert into office_project_member(project_id, user_id, role) values
                        ('P1','U-LEAD','LEADER'), ('P1','U-COLLAB','COLLABORATOR')
                    """);
                var migration = new ClassPathResource("db/migrations/20261124_simple_office_permissions.sql");
                ScriptUtils.executeSqlScript(connection, migration);
                // 幂等：重复执行不报错、不覆盖已回填的值
                ScriptUtils.executeSqlScript(connection, migration);
                assertEquals(1 | 2 | 16 | 32, flags(statement, "U-LEAD"), "存量负责人回填 查看|编辑|方案导出|成员管理");
                assertEquals(1 | 2 | 16, flags(statement, "U-COLLAB"), "存量协作者回填 查看|编辑|方案导出");
                // 列默认 0 且 NOT NULL
                statement.execute("insert into office_project_member(project_id, user_id, role) values ('P1','U-NEW','COLLABORATOR')");
                assertEquals(0, flags(statement, "U-NEW"), "新成员默认无授权");
            }
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                // check 约束：权限位掩码不允许超出 0..127
                assertThrows(SQLException.class, () -> statement.execute(
                    "insert into office_project_member(project_id, user_id, role, permission_flags) values ('P1','U-BAD','COLLABORATOR',128)"));
            }
            // 落库 → 回读 → 位解码全链路
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OfficeProjectMemberMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(scoped);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(true)) {
                var mapper = session.getMapper(OfficeProjectMemberMapper.class);
                var granted = new OfficeProjectMember();
                granted.setProjectId("P1");
                granted.setUserId("U-GRANTED");
                granted.setRole(OfficeProjectMember.ROLE_COLLABORATOR);
                granted.setPermissionFlags(OfficeProjectPermission.encode(List.of(OfficeProjectPermission.READ,
                    OfficeProjectPermission.QUOTE_READ, OfficeProjectPermission.EXPORT_QUOTE)));
                granted.setCreatedAt(LocalDateTime.now());
                mapper.insert(granted);
                var readBack = mapper.selectList(null).stream()
                    .filter(member -> "U-GRANTED".equals(member.getUserId())).findFirst().orElseThrow();
                assertEquals(69, readBack.getPermissionFlags());
                assertEquals(List.of(OfficeProjectPermission.READ, OfficeProjectPermission.QUOTE_READ,
                    OfficeProjectPermission.EXPORT_QUOTE), OfficeProjectPermission.assigned(readBack.getPermissionFlags()));
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private static int flags(java.sql.Statement statement, String userId) throws Exception {
        try (var rs = statement.executeQuery("select permission_flags from office_project_member where user_id='" + userId + "'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
