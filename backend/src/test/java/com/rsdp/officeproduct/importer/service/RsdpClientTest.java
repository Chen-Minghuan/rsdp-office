package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RsdpClientTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ObjectMapper json = new ObjectMapper();

    private RsdpClient client() {
        RsdpMainProperties properties = new RsdpMainProperties();
        properties.setBaseUrl("http://rsdp.test");
        properties.setUsername("service");
        properties.setPassword("secret");
        RsdpClient client = new RsdpClient(restTemplate, properties, json);
        ReflectionTestUtils.setField(client, "sessionCookie", "existing-session");
        return client;
    }

    @Test
    void getRetriesTransientNetworkFailures() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("first connection refused"))
            .thenThrow(new ResourceAccessException("second connection refused"))
            .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":[]}"));

        JsonNode result = client().get("/api/v1/products/RSPU-1/variants");

        assertTrue(result.isArray());
        verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void getStopsAfterThreeNetworkAttempts() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("connection refused"));

        BusinessException error = assertThrows(BusinessException.class, () -> client().get("/api/v1/products/RSPU-1/variants"));

        assertEquals("主项目数据服务不可用，请确认 RSDP 后端已启动", error.getMessage());
        verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void getDoesNotRetryBusinessErrors() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new BusinessException("business failure"));

        BusinessException error = assertThrows(BusinessException.class, () -> client().get("/api/v1/products/RSPU-1/variants"));

        assertEquals("business failure", error.getMessage());
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void binaryResponseUsesNewTransportHeaders() {
        byte[] image = {1, 2, 3};
        when(restTemplate.exchange(eq("http://rsdp.test/api/v1/images/IMG-1"), eq(HttpMethod.GET),
            any(HttpEntity.class), eq(byte[].class))).thenReturn(ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header(HttpHeaders.SET_COOKIE, "upstream=internal")
                .body(image));

        ResponseEntity<byte[]> response = client().getBinary("/api/v1/images/IMG-1");

        assertArrayEquals(image, response.getBody());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
        assertEquals("max-age=60", response.getHeaders().getCacheControl());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.TRANSFER_ENCODING));
        assertFalse(response.getHeaders().containsKey(HttpHeaders.CONNECTION));
        assertFalse(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }
}
