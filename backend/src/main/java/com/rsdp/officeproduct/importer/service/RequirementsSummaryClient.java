package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RequirementsSummaryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementsSummaryClient {
    private static final int MAX_KEY_POINTS = 8;
    private static final int MAX_KEY_POINT_LENGTH = 160;

    private final RestTemplate restTemplate;
    private final RequirementsSummaryProperties properties;
    private final ObjectMapper objectMapper;

    public List<String> summarize(String sourceText) {
        validateConfiguration();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", properties.getModel().trim())
            .put("stream", false)
            .put("enable_thinking", false)
            .put("temperature", 0)
            .put("max_tokens", 1200);
        payload.putObject("response_format").put("type", "json_object");
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", """
            你是办公项目的需求事实抽取器。只提取用户原文明示的需求，不创作、不补充、不推测。
            准确保留数字、单位、预算、房间名称、风格、偏好、否定和限制条件；重复内容合并，冲突内容分别保留，信息不明确时标为待确认。
            按原文实际有效要点数量输出，最多8条，不为凑数编造。每个关键点是一句简明中文，不超过160字。
            只输出JSON对象：{"keyPoints":["关键点1","关键点2"]}，不要输出解释、Markdown或其他字段。
            """);
        messages.addObject().put("role", "user").put("content", sourceText);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey().trim());
        String endpoint = properties.getBaseUrl().trim().replaceAll("/+$", "") + "/chat/completions";
        try {
            String body = restTemplate.postForObject(endpoint, new HttpEntity<>(payload.toString(), headers), String.class);
            JsonNode choice = objectMapper.readTree(body == null ? "" : body).path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) {
                throw new BusinessException("项目需求总结返回不完整，请重试");
            }
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual()) throw new BusinessException("项目需求总结没有返回有效内容");
            JsonNode result = objectMapper.readTree(stripFence(content.asText()));
            return readKeyPoints(result);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("项目需求总结模型请求失败，HTTP {}", status);
            String detail = switch (status) {
                case 401, 403 -> "模型密钥无效或权限不足，请检查后端配置";
                case 429 -> "模型请求限流或额度不足，请稍后重试";
                case 400, 404 -> "模型或接口地址不可用，请检查总结模型配置";
                default -> "模型服务暂不可用，请稍后重试";
            };
            throw new BusinessException("项目需求总结失败：" + detail);
        } catch (RestClientException e) {
            throw new BusinessException("无法连接项目需求总结服务或请求超时，请稍后重试");
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new BusinessException("项目需求总结不是有效的结构化结果，请重试");
        }
    }

    private List<String> readKeyPoints(JsonNode result) {
        if (result == null || !result.isObject() || !result.path("keyPoints").isArray()) {
            throw new BusinessException("项目需求总结缺少关键点列表");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode node : result.path("keyPoints")) {
            if (!node.isTextual() || !StringUtils.hasText(node.asText())) {
                throw new BusinessException("项目需求总结包含空白关键点");
            }
            String point = node.asText().trim();
            if (point.length() > MAX_KEY_POINT_LENGTH) {
                throw new BusinessException("项目需求总结关键点过长");
            }
            unique.add(point);
        }
        if (unique.isEmpty() || unique.size() > MAX_KEY_POINTS) {
            throw new BusinessException("项目需求总结关键点数量不符合要求");
        }
        return new ArrayList<>(unique);
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException("尚未配置项目需求总结密钥，请在后端 DASHSCOPE_API_KEY 中填写后重启");
        }
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getModel())) {
            throw new BusinessException("项目需求总结接口地址或模型配置为空");
        }
    }

    private String stripFence(String text) {
        String result = text.trim();
        if (result.startsWith("```")) {
            int firstLine = result.indexOf('\n');
            if (firstLine >= 0 && result.endsWith("```")) {
                result = result.substring(firstLine + 1, result.length() - 3).trim();
            }
        }
        return result;
    }
}
