package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RequirementsSummaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RequirementsSummaryClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RequirementsSummaryProperties properties;
    private MockRestServiceServer server;
    private RequirementsSummaryClient client;

    @BeforeEach
    void setup() {
        RestTemplate restTemplate = new RestTemplate();
        properties = new RequirementsSummaryProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://ai.example.test/v1");
        properties.setModel("qwen-turbo");
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new RequirementsSummaryClient(restTemplate, properties, mapper);
    }

    @Test
    void extractsStructuredFactsWithoutThinking() throws Exception {
        server.expect(requestTo("https://ai.example.test/v1/chat/completions"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(jsonPath("$.model").value("qwen-turbo"))
            .andExpect(jsonPath("$.stream").value(false))
            .andExpect(jsonPath("$.enable_thinking").value(false))
            .andExpect(jsonPath("$.temperature").value(0))
            .andExpect(jsonPath("$.response_format.type").value("json_object"))
            .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("不要开放式会议室")))
            .andRespond(withSuccess(completion(Map.of("keyPoints", List.of(
                "开放办公区计划容纳60人。", "不考虑开放式会议室。"))), MediaType.APPLICATION_JSON));

        List<String> points = client.summarize("开放办公区60人，不要开放式会议室。");

        assertEquals(List.of("开放办公区计划容纳60人。", "不考虑开放式会议室。"), points);
        server.verify();
    }

    @Test
    void rejectsEmptyOrInvalidStructuredOutput() throws Exception {
        server.expect(requestTo("https://ai.example.test/v1/chat/completions"))
            .andRespond(withSuccess(completion(Map.of("keyPoints", List.of(" ", "  "))), MediaType.APPLICATION_JSON));

        BusinessException error = assertThrows(BusinessException.class, () -> client.summarize("需要会议室"));
        assertTrue(error.getMessage().contains("关键点"));
        server.verify();
    }

    private String completion(Object output) throws Exception {
        String content = mapper.writeValueAsString(output);
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
            "finish_reason", "stop", "message", Map.of("content", content)))));
    }
}
