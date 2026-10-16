package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QwenImageClient {
    private final RestTemplate http;
    private final RenderProperties properties;

    public void requireConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) throw new BusinessException("尚未配置生图密钥，请配置 DASHSCOPE_API_KEY 或 RSDP_RENDER_API_KEY 后重启后端");
        if (properties.getBaseUrl() == null || !properties.getBaseUrl().startsWith("https://") || !properties.getBaseUrl().replaceAll("/+$", "").endsWith("/api/v1")) throw new BusinessException("生图地址须为百炼原生接口，末尾到 /api/v1");
        if (properties.getModel() == null || !properties.getModel().matches("qwen-image-3\\.0(-pro)?")) throw new BusinessException("当前生图接口支持 qwen-image-3.0 和 qwen-image-3.0-pro");
    }

    public JsonNode submit(byte[] reference, String prompt) {
        requireConfigured();
        var body = Map.of("model", properties.getModel(), "input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
            Map.of("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(reference)), Map.of("text", prompt))))),
            // Avoid automatic prompt rewrites changing the requested layout or adding furniture.
            "parameters", Map.of("n", 1, "size", "1536*1024", "prompt_extend", false, "watermark", false));
        HttpHeaders headers = headers();
        headers.set("X-DashScope-Async", "enable");
        return exchange(base() + "/services/aigc/image-generation/generation", HttpMethod.POST, new HttpEntity<>(body, headers));
    }

    public JsonNode poll(String taskId, String submittedBaseUrl) {
        requireConfigured();
        if (!base().equals(submittedBaseUrl)) throw new BusinessException("生图服务地域配置已变化，请恢复提交任务时的 RSDP_RENDER_BASE_URL 后查询");
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException("生图服务返回的任务 ID 无效");
        return exchange(submittedBaseUrl + "/tasks/" + taskId, HttpMethod.GET, new HttpEntity<>(headers()));
    }

    public String base() { return properties.getBaseUrl().replaceAll("/+$", ""); }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode exchange(String url, HttpMethod method, HttpEntity<?> entity) {
        try {
            JsonNode result = http.exchange(url, method, entity, JsonNode.class).getBody();
            if (result == null || result.hasNonNull("code")) throw new BusinessException("生图服务未受理请求，请核对模型权限、余额及输入内容");
            return result;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) throw new BusinessException("生图鉴权失败，请核对密钥、地域及 Qwen-Image-3.0 调用权限");
            if (status == 429) throw new BusinessException("生图服务限流或额度不足，请稍后重试并检查百炼额度");
            if (status == 400 || status == 404) throw new BusinessException("生图请求被拒绝，请核对原生接口地址、模型地域支持及内容要求");
            throw new BusinessException("生图服务暂时不可用，请稍后重试");
        } catch (ResourceAccessException e) {
            throw new BusinessException("生图服务连接超时，提交结果可能未知；请先在百炼核对请求记录，避免重复计费");
        } catch (RestClientException e) {
            throw new BusinessException("生图服务响应无法读取，请先核对百炼请求记录后重试");
        }
    }
}
