package com.rsdp.officeproduct.importer.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "office.import.factory")
@RequiredArgsConstructor
public class FactoryProperties {

    private Long factoryId;
    private String factoryCode = "DEFAULT";
    private String factoryName = "默认工厂";
}
