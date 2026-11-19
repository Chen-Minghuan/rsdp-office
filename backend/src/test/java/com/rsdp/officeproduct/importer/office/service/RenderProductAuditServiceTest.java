package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderProductAuditServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final QwenVisionClient vision=mock(QwenVisionClient.class);
    private final RenderProductAuditService service=new RenderProductAuditService(vision);
    private RenderFurnitureBindings.Prepared bindings() {
        return new RenderFurnitureBindings.Prepared(List.of(
            new RenderFurnitureBindings.Binding("F1","A","职员桌","空间左侧后侧",0,0,0,"SKU-1","P1"),
            new RenderFurnitureBindings.Binding("F2","B","访客椅","空间右侧前侧",0,0,0,"","")
        ));
    }
    @Test void normalizesPassFailAndMissingProductEvidence() throws Exception {
        var raw=json.readTree("{\"furnitureCount\":{\"observed\":2,\"status\":\"PASS\"},\"layout\":{\"status\":\"PASS\"},\"items\":[{\"furnitureReferenceId\":\"F1\",\"productReferenceId\":\"P1\",\"status\":\"PASS\"}],\"providerRequestId\":\"req\"}");
        var pass=service.normalize(raw,bindings());assertEquals("PASS",pass.path("status").asText());assertEquals(2,pass.path("furnitureCount").path("expected").asInt());assertEquals("req",pass.path("providerRequestId").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("furnitureCount")).put("status","FAIL");
        assertEquals("FAIL",service.normalize(raw,bindings()).path("status").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("furnitureCount")).put("status","PASS");
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw).putArray("items");
        var missing=service.normalize(raw,bindings());assertEquals("UNCERTAIN",missing.path("status").asText());assertEquals("UNCERTAIN",missing.path("items").get(0).path("status").asText());
    }
    @Test void auditUsesResultGuideAndProductBoardWithoutRetryingGeneration() throws Exception {
        when(vision.auditRender(anyString(),anyList())).thenReturn(json.readTree("{\"furnitureCount\":{\"observed\":2,\"status\":\"PASS\"},\"layout\":{\"status\":\"PASS\"},\"items\":[{\"furnitureReferenceId\":\"F1\",\"status\":\"PASS\"}]}"));
        var result=service.audit(new byte[]{1},new byte[]{2},List.of(new byte[]{3}),bindings(),json.createArrayNode());
        assertEquals("PASS",result.path("status").asText());verify(vision,times(1)).auditRender(contains("家具绑定"),argThat(images->images.size()==3));
        assertEquals("UNAVAILABLE",service.unavailable().path("status").asText());
    }
}
