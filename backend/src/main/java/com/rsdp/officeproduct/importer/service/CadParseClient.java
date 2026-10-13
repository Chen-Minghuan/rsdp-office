package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.CadParserProperties;
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
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CadParseClient {

    private final RestTemplate restTemplate;
    private final CadParserProperties cadParserProperties;
    private final ObjectMapper objectMapper;

    public JsonNode parse(MultipartFile file) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new MultipartInputStreamFileResource(file));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            String url = cadParserProperties.getUrl() + "/parse";

            log.info("转发 CAD 文件到解析服务: {}, fileName={}, size={}", url, file.getOriginalFilename(), file.getSize());
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return objectMapper.readTree(response.getBody());
        } catch (RestClientResponseException e) {
            log.error("CAD 解析服务返回错误: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new BusinessException("CAD 解析失败: " + parseErrorMessage(e));
        } catch (IOException e) {
            log.error("读取 CAD 文件失败", e);
            throw new BusinessException("读取 CAD 文件失败", e);
        } catch (Exception e) {
            log.error("调用 CAD 解析服务异常", e);
            throw new BusinessException("CAD 解析服务不可用，请确认 cad-parser 已启动");
        }
    }

    private String parseErrorMessage(RestClientResponseException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            if (node.has("errorMessage") && !node.get("errorMessage").isNull()) {
                return node.get("errorMessage").asText();
            }
            if (node.has("message") && !node.get("message").isNull()) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        return e.getStatusText();
    }

    public static class MultipartInputStreamFileResource extends InputStreamResource {

        private final String filename;

        public MultipartInputStreamFileResource(MultipartFile file) throws IOException {
            super(file.getInputStream());
            this.filename = file.getOriginalFilename();
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public long contentLength() {
            return -1;
        }
    }
}
