package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final QwenVisionClient qwenVisionClient;

    public JsonNode recognize(MultipartFile file) {
        return recognize(file, 1);
    }

    public JsonNode recognize(MultipartFile file, int pageNumber) {
        return StringUtils.hasText(visionProperties.getUrl()) ? callExternal(file, pageNumber)
            : qwenVisionClient.recognize(file, pageNumber);
    }

    private JsonNode callExternal(MultipartFile file, int pageNumber) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new CadParseClient.MultipartInputStreamFileResource(file));
            body.add("pageNumber", pageNumber);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(visionProperties.getUrl(), request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientResponseException e) {
            log.warn("AI 图纸识别服务返回错误: status={}", e.getStatusCode());
            throw new BusinessException("AI 图纸识别服务返回错误：HTTP " + e.getStatusCode().value());
        } catch (IOException e) {
            throw new BusinessException("读取文件失败", e);
        } catch (Exception e) {
            log.error("调用 AI 图纸识别服务异常", e);
            throw new BusinessException("AI 图纸识别服务不可用");
        }
    }

}
