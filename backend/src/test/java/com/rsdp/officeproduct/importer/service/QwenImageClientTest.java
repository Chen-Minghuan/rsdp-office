package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class QwenImageClientTest {
    private final RenderProperties properties=new RenderProperties();
    private final RestTemplate http=new RestTemplate();
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(http).build();
    private final QwenImageClient client=new QwenImageClient(http,properties);

    @Test void usesNativeAsyncImageEndpointAndReferenceInsteadOfChatCompletions() {
        properties.setApiKey("test-secret");
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("X-DashScope-Async","enable"))
            .andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer test-secret"))
            .andExpect(jsonPath("$.model").value("qwen-image-3.0"))
            .andExpect(jsonPath("$.input.messages[0].content[0].image").value("data:image/png;base64,AQI="))
            .andExpect(jsonPath("$.input.messages[0].content[1].text").value("布局"))
            .andExpect(jsonPath("$.parameters.prompt_extend").value(false))
            .andExpect(jsonPath("$.parameters.n").value(1))
            .andExpect(jsonPath("$.parameters.negative_prompt").value(org.hamcrest.Matchers.containsString("平面布局图")))
            .andRespond(withSuccess("{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}",MediaType.APPLICATION_JSON));
        assertEquals("task-1",client.submit(new byte[]{1,2},"布局").path("output").path("task_id").asText());
        assertFalse(properties.toString().contains("test-secret")); server.verify();
    }
    @Test void transmitsLayoutAndProductImagesInOrder() {
        properties.setApiKey("test-secret");
        server.expect(anything()).andExpect(jsonPath("$.input.messages[0].content[0].image").value("data:image/png;base64,AQ=="))
            .andExpect(jsonPath("$.input.messages[0].content[1].image").value("data:image/png;base64,Ag=="))
            .andExpect(jsonPath("$.input.messages[0].content[2].text").value("商品外观"))
            .andRespond(withSuccess("{\"output\":{\"task_id\":\"task-2\"}}",MediaType.APPLICATION_JSON));
        client.submit(java.util.List.of(new byte[]{1},new byte[]{2}),"商品外观"); server.verify();
        assertThrows(BusinessException.class,()->client.submit(java.util.List.of(new byte[0],new byte[0],new byte[0],new byte[0]),"x"));
    }
    @Test void pollsSameRegionAndDoesNotResubmit() {
        properties.setApiKey("test-secret");
        server.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/tasks/task-1")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"output\":{\"task_status\":\"RUNNING\"}}",MediaType.APPLICATION_JSON));
        assertEquals("RUNNING",client.poll("task-1",client.base()).path("output").path("task_status").asText());
        assertThrows(BusinessException.class,()->client.poll("task-1","https://other.test/api/v1")); server.verify();
    }
    @Test void missingKeyWrongEndpointAndProviderErrorsAreExplicitAndRedacted() {
        assertThrows(BusinessException.class,()->client.submit(new byte[0],"x"));
        properties.setApiKey("test-secret"); properties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThrows(BusinessException.class,client::requireConfigured);
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("secret-provider-debug"));
        String error=assertThrows(BusinessException.class,()->client.submit(new byte[0],"x")).getMessage();
        assertTrue(error.contains("权限")); assertFalse(error.contains("secret-provider-debug")); server.verify();
    }
}
