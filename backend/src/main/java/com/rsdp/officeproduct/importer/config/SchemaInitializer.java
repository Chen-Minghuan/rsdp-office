package com.rsdp.officeproduct.importer.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 启动期执行 db/schema.sql，确保表结构最新（脚本本身幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaInitializer {

    private final DataSource dataSource;

    @PostConstruct
    public void init() {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Resource resource = new ClassPathResource("db/schema.sql");
            if (resource.exists()) {
                ScriptUtils.executeSqlScript(connection, resource);
                log.info("schema.sql 执行完成");
            }
            Resource migration = new ClassPathResource("db/migrations/20261014_variant_packaging.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            log.info("变体包装数据迁移执行完成");
            Resource metadataCleanup = new ClassPathResource("db/migrations/20261014_remove_legacy_product_metadata.sql");
            ScriptUtils.executeSqlScript(connection, metadataCleanup);
            log.info("旧商品标签字段清理完成");
            Resource productCategoryCleanup = new ClassPathResource("db/migrations/20261014_remove_product_category.sql");
            ScriptUtils.executeSqlScript(connection, productCategoryCleanup);
            log.info("商品品类码字段清理完成");
            Resource projectInfoCleanup = new ClassPathResource("db/migrations/20261017_remove_office_project.sql");
            ScriptUtils.executeSqlScript(connection, new EncodedResource(projectInfoCleanup), false, false, "--",
                ScriptUtils.EOF_STATEMENT_SEPARATOR, "/*", "*/");
            Resource projectFloorMigration = new ClassPathResource("db/migrations/20261026_office_project_floor.sql");
            ScriptUtils.executeSqlScript(connection, new EncodedResource(projectFloorMigration), false, false, "--",
                ScriptUtils.EOF_STATEMENT_SEPARATOR, "/*", "*/");
            connection.commit();
            log.info("项目楼层结构及默认工作区映射初始化完成");
        } catch (Exception e) {
            throw new IllegalStateException("数据库结构更新失败", e);
        }
    }
}
