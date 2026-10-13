package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rsdp.cad-parser")
public class CadParserProperties {

    private String url = "http://127.0.0.1:8091";
}
