package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rsdp.render")
public class RenderProperties {
    @ToString.Exclude
    private String apiKey;
    /** Native DashScope API, independent of the recognition Chat Completions endpoint. */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1";
    private String model = "qwen-image-3.0";
    private String storageDirectory = "uploads/render-previews";
}
