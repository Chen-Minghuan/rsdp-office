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
    private ProductAgent productAgent = new ProductAgent();
    private GeometryAudit geometryAudit = new GeometryAudit();

    @Data
    public static class GeometryAudit {
        private double centerPassRatio = .04;
        private double centerFailRatio = .10;
        private double iouPass = .45;
        private double iouFail = .20;
        private double minDetectionConfidence = .65;
        private double roomIouPass = .75;
    }

    @Data
    public static class ProductAgent {
        /** Application configuration enables the experiment explicitly; tests remain static by default. */
        private boolean enabled = false;
        /** Avoid a long sequence of first-time visual calls in the synchronous experiment endpoint. */
        private int maxNewProductsPerRender = 4;
        private int maxCachedProfileBytes = 1_000_000;
    }
}
