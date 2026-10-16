package com.rsdp.officeproduct.importer.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class RenderWebConfig implements WebMvcConfigurer {
    private final RenderProperties properties;
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/render-previews/**")
            .addResourceLocations(Path.of(properties.getStorageDirectory()).toAbsolutePath().toUri().toString().replaceAll("/+$", "")+"/");
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/uploads/render-previews/**").allowedOriginPatterns("*").allowedMethods("GET");
    }
}
