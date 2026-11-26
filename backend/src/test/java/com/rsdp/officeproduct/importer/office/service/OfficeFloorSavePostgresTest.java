package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 楼层改名与参考图保存并发交错时两列互不覆盖的真实数据库验证。
 * 两个请求都在对方写入前读到同一行快照：按列定点更新（updateName/updateReferenceImages）
 * 各自只写本列，两列都保留；旧的整行 updateById 语义会让后写者用旧快照覆盖先写者的列。
 */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class OfficeFloorSavePostgresTest {
    @Test void interleavedFloorRenameAndReferenceSaveBothSurvive() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "floor_save_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("""
                    create table office_design_floor (
                        floor_id varchar(64) primary key, project_id varchar(64) not null,
                        name text not null, sort_order int default 0, reference_images text,
                        created_at timestamp default now(), updated_at timestamp default now())
                    """);
                statement.execute("""
                    insert into office_design_floor(floor_id, project_id, name, reference_images)
                    values ('OFLR-1', 'OPRJ-1', '一层', '[{"url":"/uploads/images/base.png","name":"基线"}]')
                    """);
            }
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OfficeDesignFloorMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(scoped);
            factory.afterPropertiesSet();
            try (var sessionA = factory.getObject().openSession(true);
                 var sessionB = factory.getObject().openSession(true)) {
                var floorsA = sessionA.getMapper(OfficeDesignFloorMapper.class);
                var floorsB = sessionB.getMapper(OfficeDesignFloorMapper.class);
                // 交错读-改-写：两个会话都在对方写入前读取同一行。
                OfficeDesignFloor snapshotA = floorsA.selectById("OFLR-1");
                OfficeDesignFloor snapshotB = floorsB.selectById("OFLR-1");
                assertEquals("一层", snapshotA.getName());
                assertTrue(snapshotB.getReferenceImages().contains("/uploads/images/base.png"));

                floorsA.updateName("OFLR-1", "并发改名", LocalDateTime.now());
                floorsB.updateReferenceImages("OFLR-1",
                    "[{\"url\":\"/uploads/images/concurrent.png\",\"name\":\"并发图\"}]", LocalDateTime.now());

                OfficeDesignFloor reread = floorsA.selectById("OFLR-1");
                assertEquals("并发改名", reread.getName());
                assertTrue(reread.getReferenceImages().contains("/uploads/images/concurrent.png"));
                assertFalse(reread.getReferenceImages().contains("/uploads/images/base.png"));
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
