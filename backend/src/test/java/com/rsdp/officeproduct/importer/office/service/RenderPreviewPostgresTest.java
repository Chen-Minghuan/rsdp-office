package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.config.SchemaInitializer;
import com.rsdp.officeproduct.importer.office.entity.RenderPreview;
import com.rsdp.officeproduct.importer.office.mapper.RenderPreviewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Uses an isolated real PostgreSQL schema; no application data is read or modified. */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class RenderPreviewPostgresTest {
    @Test
    void timingAndAuditSnapshotsSurviveMapperUpdateAndFreshQuery() throws Exception {
        String url=System.getenv("RSDP_TEST_POSTGRES_URL");
        String username=System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER","postgres");
        String password=System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD","");
        var database=new DriverManagerDataSource(url,username,password);
        String schema="render_preview_test_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=database.getConnection();var statement=connection.createStatement()) {
            statement.execute("create schema "+schema);
        }
        try {
            var scoped=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,username,password);
            new SchemaInitializer(scoped).init();
            var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(RenderPreviewMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(scoped);factory.setConfiguration(configuration);factory.afterPropertiesSet();
            try(var session=factory.getObject().openSession(true)) {
                RenderPreviewMapper mapper=session.getMapper(RenderPreviewMapper.class);ObjectMapper json=new ObjectMapper();
                RenderPreview entry=new RenderPreview();entry.setPreviewId("RPRE-PERSISTENCE");entry.setProjectId("PROJECT");entry.setZoneId("ROOM");
                entry.setZoneName("会议室");entry.setCanvasRevision(3);entry.setModel("qwen-image-3.0-pro");entry.setStyle("现代办公");entry.setView("ISOMETRIC");
                entry.setInstructions("");entry.setStatus("PENDING");entry.setClientChangeId("CHANGE-PERSISTENCE");entry.setContentHash("a".repeat(64));
                entry.setSnapshotJson("{\"zone\":{\"id\":\"ROOM\"},\"renderProductReferences\":{\"generationTiming\":{\"requestStartedAt\":1000}}}");
                entry.setPrompt("test");entry.setProviderBaseUrl("https://example.com");entry.setCreatedAt(LocalDateTime.now());entry.setUpdatedAt(entry.getCreatedAt());
                assertEquals(1,mapper.insert(entry));
                RenderPreview loaded=mapper.byChange("PROJECT","CHANGE-PERSISTENCE");assertNotNull(loaded);
                ObjectNode stored=(ObjectNode)json.readTree(loaded.getSnapshotJson());ObjectNode timing=(ObjectNode)stored.path("renderProductReferences").path("generationTiming");
                timing.put("submitStartedAt",1100).put("submittedAt",1200).put("lastPolledAt",1300).put("failedAt",1400);
                loaded.setSnapshotJson(json.writeValueAsString(stored));loaded.setStatus("FAILED");loaded.setErrorMessage("提交失败");loaded.setUpdatedAt(LocalDateTime.now());
                assertEquals(1,mapper.update(loaded));
                loaded.setSnapshotJson("{}");session.clearCache();
                RenderPreview reread=mapper.byChange("PROJECT","CHANGE-PERSISTENCE");assertNotNull(reread);assertNotSame(loaded,reread);
                ObjectNode persisted=(ObjectNode)json.readTree(reread.getSnapshotJson()).path("renderProductReferences").path("generationTiming");
                assertEquals(1100,persisted.path("submitStartedAt").asLong());assertEquals(1200,persisted.path("submittedAt").asLong());
                assertEquals(1300,persisted.path("lastPolledAt").asLong());assertEquals(1400,persisted.path("failedAt").asLong());
                assertEquals("FAILED",reread.getStatus());assertEquals("提交失败",reread.getErrorMessage());

                ObjectNode withAudit=(ObjectNode)json.readTree(reread.getSnapshotJson());withAudit.withObject("/renderProductReferences").putObject("qualityAudit").put("status","UNCERTAIN");
                reread.setSnapshotJson(json.writeValueAsString(withAudit));reread.setUpdatedAt(LocalDateTime.now());assertEquals(1,mapper.updateSnapshot(reread));
                reread.setSnapshotJson("{}");session.clearCache();
                assertEquals("UNCERTAIN",json.readTree(mapper.byChange("PROJECT","CHANGE-PERSISTENCE").getSnapshotJson())
                    .path("renderProductReferences").path("qualityAudit").path("status").asText());
            }
        } finally {
            try(var connection=database.getConnection();var statement=connection.createStatement()) {
                statement.execute("drop schema "+schema+" cascade");
            }
        }
    }
}
