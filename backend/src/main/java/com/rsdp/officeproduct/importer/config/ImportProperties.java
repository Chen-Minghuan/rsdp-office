package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Data
@Component
@ConfigurationProperties(prefix = "rsdp.import")
public class ImportProperties {

    private DataSize maxFileSize = DataSize.ofMegabytes(50);
}
