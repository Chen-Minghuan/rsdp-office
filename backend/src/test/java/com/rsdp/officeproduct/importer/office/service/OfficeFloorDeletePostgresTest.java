package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.mapper.SpaceTemplateMapper;
import com.rsdp.officeproduct.importer.mapper.spatial.FloorPlanCalibrationMapper;
import com.rsdp.officeproduct.importer.mapper.spatial.FloorPlanDesignRevisionMapper;
import com.rsdp.officeproduct.importer.mapper.spatial.FloorPlanOperationReceiptMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProposalConfirmationMapper;
import com.rsdp.officeproduct.importer.office.mapper.RenderPreviewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 删除楼层时新增清理语句的真实数据库验证。
 * 覆盖：效果图/空间模板按楼层删除、方案确认按楼层删除（先 retired 再硬删后楼层行可删）、
 * 遗留空间链路按 scene_id 的批量删除语句在 Postgres 下可执行。
 */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class OfficeFloorDeletePostgresTest {
    @Test void floorCleanupStatementsClearRelatedRowsAndUnblockFloorDelete() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, user, password);
        String schema = "floor_delete_" + UUID.randomUUID().toString().replace("-", "");
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
                // 方案确认对楼层行的 FK 与生产一致（无级联），验证先删确认行后楼层可删。
                statement.execute("""
                    create table office_proposal_confirmation (
                        confirmation_id varchar(64) primary key, floor_id varchar(64) not null
                            references office_design_floor(floor_id),
                        canvas_revision int, fingerprint text, snapshot_json jsonb,
                        confirmed_by varchar(64), confirmed_at timestamp default now(), retired boolean default false)
                    """);
                statement.execute("""
                    create table office_render_preview (
                        preview_id varchar(64) primary key, project_id varchar(64) not null, zone_id varchar(64),
                        status varchar(32), created_at timestamp default now(), updated_at timestamp default now())
                    """);
                statement.execute("""
                    create table office_space_template (
                        template_id varchar(64) primary key, project_id varchar(64) not null,
                        name text, client_change_id varchar(64), created_at timestamp default now())
                    """);
                statement.execute("""
                    create table floor_plan_design_revision (
                        scene_id varchar(64) not null, revision bigint not null, parent_revision bigint,
                        client_change_id varchar(64), action varchar(64), snapshot_json jsonb,
                        created_by varchar(64), created_at timestamp default now(),
                        primary key (scene_id, revision))
                    """);
                statement.execute("""
                    create table floor_plan_calibration (
                        scene_id varchar(64) not null, calibration_revision bigint not null, page_id varchar(64),
                        reference_points jsonb, reference_length_mm numeric, mm_per_source_unit numeric,
                        source_to_world jsonb, basis text, status varchar(32),
                        created_by varchar(64), created_at timestamp default now(),
                        primary key (scene_id, calibration_revision))
                    """);
                statement.execute("""
                    create table floor_plan_operation_receipt (
                        receipt_id bigserial primary key, actor_id varchar(64), scope_key varchar(128),
                        client_change_id varchar(64), action varchar(64), request_hash varchar(128),
                        http_status int, response_json jsonb, scene_id varchar(64), scene_revision bigint,
                        created_at timestamp default now())
                    """);
                statement.execute("""
                    insert into office_design_floor(floor_id, project_id, name) values ('OFLR-DEL', 'OPRJ-1', '二层')
                    """);
                statement.execute("""
                    insert into office_proposal_confirmation(confirmation_id, floor_id, snapshot_json)
                    values ('OCFM-DEL', 'OFLR-DEL', '{}'::jsonb)
                    """);
                statement.execute("""
                    insert into office_render_preview(preview_id, project_id, zone_id, status)
                    values ('ORPV-DEL', 'OFLR-DEL', 'ZONE-1', 'succeeded')
                    """);
                statement.execute("""
                    insert into office_space_template(template_id, project_id, name)
                    values ('OTPL-DEL', 'OFLR-DEL', '模板')
                    """);
                statement.execute("""
                    insert into floor_plan_design_revision(scene_id, revision) values ('OSCN-DEL', 1)
                    """);
                statement.execute("""
                    insert into floor_plan_calibration(scene_id, calibration_revision) values ('OSCN-DEL', 1)
                    """);
                statement.execute("""
                    insert into floor_plan_operation_receipt(scene_id, scene_revision) values ('OSCN-DEL', 1)
                    """);
            }
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(RenderPreviewMapper.class);
            configuration.addMapper(SpaceTemplateMapper.class);
            configuration.addMapper(OfficeProposalConfirmationMapper.class);
            configuration.addMapper(FloorPlanDesignRevisionMapper.class);
            configuration.addMapper(FloorPlanCalibrationMapper.class);
            configuration.addMapper(FloorPlanOperationReceiptMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration);
            factory.setDataSource(scoped);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(true)) {
                String floorId = "OFLR-DEL";
                List<String> sceneIds = List.of("OSCN-DEL");
                assertEquals(1, session.getMapper(FloorPlanOperationReceiptMapper.class).deleteBySceneIds(sceneIds));
                assertEquals(1, session.getMapper(FloorPlanCalibrationMapper.class).deleteBySceneIds(sceneIds));
                assertEquals(1, session.getMapper(FloorPlanDesignRevisionMapper.class).deleteBySceneIds(sceneIds));
                assertEquals(1, session.getMapper(RenderPreviewMapper.class).deleteByProject(floorId));
                assertEquals(1, session.getMapper(SpaceTemplateMapper.class).deleteByProject(floorId));
                assertEquals(1, session.getMapper(OfficeProposalConfirmationMapper.class).deleteByFloor(floorId));

                // 确认行删除后，楼层行的 FK 不再阻止删除。
                try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                    assertEquals(1, statement.executeUpdate("delete from office_design_floor where floor_id = 'OFLR-DEL'"));
                    var counts = statement.executeQuery("""
                        select
                          (select count(*) from office_proposal_confirmation) +
                          (select count(*) from office_render_preview) +
                          (select count(*) from office_space_template) +
                          (select count(*) from floor_plan_design_revision) +
                          (select count(*) from floor_plan_calibration) +
                          (select count(*) from floor_plan_operation_receipt) as remaining
                        """);
                    assertTrue(counts.next());
                    assertEquals(0, counts.getLong("remaining"));
                }
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
