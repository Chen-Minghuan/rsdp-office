package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rsdp.vision")
public class VisionProperties {

    /**
     * RSDP AI 图纸识别服务地址。
     * 为空时使用本地 mock 逻辑（仅用于演示）。
     */
    private String url;
}
