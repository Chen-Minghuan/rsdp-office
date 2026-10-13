package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionRecognitionClient {

    private final RestTemplate restTemplate;
    private final VisionProperties visionProperties;
    private final ObjectMapper objectMapper;

    public JsonNode recognize(MultipartFile file) {
        if (!StringUtils.hasText(visionProperties.getUrl())) {
            log.warn("未配置 RSDP AI 图纸识别服务地址，使用演示数据");
            return mockResult(file);
        }
        return callExternal(file);
    }

    private JsonNode callExternal(MultipartFile file) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new CadParseClient.MultipartInputStreamFileResource(file));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(visionProperties.getUrl(), request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientResponseException e) {
            log.error("AI 图纸识别服务返回错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException("AI 图纸识别失败: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException("读取文件失败", e);
        } catch (Exception e) {
            log.error("调用 AI 图纸识别服务异常", e);
            throw new BusinessException("AI 图纸识别服务不可用");
        }
    }

    private JsonNode mockResult(MultipartFile file) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("success", true);
        root.put("parseStatus", "PARTIAL");
        root.put("units", "mm");
        root.put("message", "当前为演示识别结果。如需真实识别，请配置 rsdp.vision.url 指向 RSDP AI 图纸识别服务。");

        ObjectNode bounds = root.putObject("drawingBounds");
        bounds.put("minX", 0);
        bounds.put("minY", 0);
        bounds.put("maxX", 8000);
        bounds.put("maxY", 6000);

        ArrayNode rooms = root.putArray("rooms");
        ObjectNode room = rooms.addObject();
        room.put("roomId", "ROOM-001");
        room.put("label", "开放办公区");
        room.put("roomType", "OPEN_OFFICE");
        room.put("widthMm", 8000);
        room.put("depthMm", 6000);
        room.put("areaM2", 48.0);
        room.put("confidence", "mid");
        ArrayNode polygon = room.putArray("polygon");
        polygon.addArray().add(0).add(0);
        polygon.addArray().add(8000).add(0);
        polygon.addArray().add(8000).add(6000);
        polygon.addArray().add(0).add(6000);
        polygon.addArray().add(0).add(0);

        root.putArray("qualityIssues").addObject()
            .put("level", "warn")
            .put("code", "MOCK_RECOGNITION")
            .put("message", "PNG/PDF 真实识别需要接入 RSDP VisionService 或第三方多模态 API");

        return root;
    }
}
