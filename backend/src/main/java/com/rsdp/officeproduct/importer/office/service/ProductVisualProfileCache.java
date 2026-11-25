package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Content-addressed product profile cache. Spatial/layout descriptions are never cached here. */
@Service
@RequiredArgsConstructor
public class ProductVisualProfileCache {
    private final RenderProperties properties;
    private final ObjectMapper json;

    public Optional<ObjectNode> find(String cacheKey) {
        try {
            Path file=file(cacheKey);
            if(!Files.isRegularFile(file) || Files.size(file)>properties.getProductAgent().getMaxCachedProfileBytes()) return Optional.empty();
            JsonNode value=json.readTree(Files.readString(file));
            if(!value.isObject() || !cacheKey.equals(value.path("cacheKey").asText())) return Optional.empty();
            return Optional.of((ObjectNode)value);
        } catch(Exception ignored) {
            return Optional.empty();
        }
    }

    public void save(String cacheKey,ObjectNode profile) {
        Path temporary=null;
        try {
            byte[] bytes=json.writeValueAsBytes(profile);
            if(bytes.length>properties.getProductAgent().getMaxCachedProfileBytes()) throw new BusinessException("商品视觉档案超过缓存限制");
            Path directory=directory();Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,"profile-",".tmp");
            Files.write(temporary,bytes);
            Files.move(temporary,file(cacheKey),StandardCopyOption.REPLACE_EXISTING);
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException("商品视觉档案缓存写入失败"); }
        finally {
            if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(Exception ignored) { }
        }
    }

    private Path file(String cacheKey) {
        if(cacheKey==null || !cacheKey.matches("[a-f0-9]{64}")) throw new BusinessException("商品视觉档案缓存键无效");
        return directory().resolve(cacheKey+".json");
    }
    Path directory() { return Path.of(properties.getStorageDirectory()).toAbsolutePath().resolve("product-visual-profiles"); }
}
