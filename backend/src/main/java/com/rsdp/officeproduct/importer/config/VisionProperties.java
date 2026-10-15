package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "rsdp.vision")
public class VisionProperties {

    /**
     * RSDP AI 图纸识别服务地址。
     * 配置时优先调用该接口；为空时直连百炼。
     */
    private String url;

    @ToString.Exclude
    private String apiKey;
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen3-vl-plus";
    private int maxImageEdge = 3072;
    private int pdfDpi = 144;
}
