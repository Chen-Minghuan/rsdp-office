package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离 schema 验证迁移幂等、真实 Mapper 存储，以及楼层事务锁阻止交错写入。 */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class QuotationProposalPostgresTest {
    @Test void sourceSnapshotRoundTripsAndFloorLockLivesUntilCommit() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "quote_source_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
            try (var connection = scoped.getConnection(); var sql = connection.createStatement()) {
                sql.execute("""
                    create table office_quotation (
                      quotation_id varchar(64) primary key, project_id text, project_name text, workspace_id text,
                      revision int, status text, pricing_mode text, pricing_value numeric, total_amount numeric,
                      fees_json jsonb, created_by text, created_at timestamp, superseded_at timestamp)
                    """);
                var migration = new ClassPathResource("db/migrations/20261127_quotation_proposal_sources.sql");
                ScriptUtils.executeSqlScript(connection, migration);
                ScriptUtils.executeSqlScript(connection, migration);
            }
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OfficeQuotationMapper.class);
            configuration.addMapper(OfficeWorkspaceLockMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setTransactionFactory(new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory());
            factory.setDataSource(scoped);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(false)) {
                session.getMapper(OfficeWorkspaceLockMapper.class).lockWorkspace("FLOOR-1");
                var quotation = new OfficeQuotation();
                quotation.setQuotationId("QT-1"); quotation.setProjectId("PROJ"); quotation.setRevision(1);
                quotation.setStatus("CONFIRMED"); quotation.setCreatedAt(LocalDateTime.now());
                quotation.setTotalAmount(BigDecimal.TEN); quotation.setFeesJson("[]");
                quotation.setProposalConfirmationsJson("{\"FLOOR-1\":\"PROP-1\"}");
                assertEquals(1, session.getMapper(OfficeQuotationMapper.class).insert(quotation));

                CountDownLatch started = new CountDownLatch(1);
                Future<String> editor = executor.submit(() -> {
                    try (var other = scoped.getConnection(); var sql = other.createStatement()) {
                        other.setAutoCommit(false);
                        sql.execute("set statement_timeout='5s'");
                        started.countDown();
                        sql.execute("select pg_advisory_xact_lock(hashtextextended('FLOOR-1',0))");
                        try (var row = sql.executeQuery("select proposal_confirmations_json::text from office_quotation where quotation_id='QT-1'")) {
                            assertTrue(row.next());
                            String snapshot = row.getString(1);
                            other.commit();
                            return snapshot;
                        }
                    }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> editor.get(150, TimeUnit.MILLISECONDS), "编辑事务应等待报价事务释放同一楼层锁");
                session.commit();
                assertTrue(editor.get(5, TimeUnit.SECONDS).contains("PROP-1"));
            }
            try (var session = factory.getObject().openSession(true)) {
                assertTrue(session.getMapper(OfficeQuotationMapper.class).selectById("QT-1").getProposalConfirmationsJson().contains("PROP-1"));
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            try (var connection = database.getConnection(); var sql = connection.createStatement()) {
                sql.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
