package com.rsdp.officeproduct.importer.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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
        try {
            Resource resource = new ClassPathResource("db/schema.sql");
            if (resource.exists()) {
                ScriptUtils.executeSqlScript(dataSource.getConnection(), resource);
                log.info("schema.sql 执行完成");
            }
        } catch (Exception e) {
            log.error("执行 schema.sql 失败", e);
        }
    }
}
