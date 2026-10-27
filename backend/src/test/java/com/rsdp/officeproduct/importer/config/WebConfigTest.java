package com.rsdp.officeproduct.importer.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigTest {
    @Test void allowsProjectEditingPatchRequests() {
        var registry = new TestCorsRegistry();
        new WebConfig().addCorsMappings(registry);
        var configuration = registry.configurations().get("/api/v1/**");
        assertTrue(configuration.checkHttpMethod(HttpMethod.PATCH).contains(HttpMethod.PATCH));
    }

    private static class TestCorsRegistry extends CorsRegistry {
        private java.util.Map<String, org.springframework.web.cors.CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
