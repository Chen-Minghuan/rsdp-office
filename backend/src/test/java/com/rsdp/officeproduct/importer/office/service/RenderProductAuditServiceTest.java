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
    @Test void auditIncludesArchitecturalFactsAndProductVisualProfiles() throws Exception {
        var products=json.createArrayNode();var product=products.addObject().put("productId","SKU-1");product.putArray("candidateImages").addObject().put("imageUrl","/uploads/images/private.png");product.putObject("visualProfile").put("appearancePrompt","保持单侧柜体").putArray("views").addObject().put("imageId","P1-V1");
        var facts=json.createObjectNode().put("openingsKnown",true);facts.putArray("openings").addObject().put("kind","WINDOW");
        when(vision.auditRender(anyString(),anyList())).thenReturn(json.readTree("{\"furnitureCount\":{\"observed\":2,\"status\":\"PASS\"},\"layout\":{\"status\":\"PASS\"},\"architecture\":{\"status\":\"FAIL\",\"reason\":\"窗缺失\"},\"imageQuality\":{\"status\":\"PASS\"},\"items\":[{\"furnitureReferenceId\":\"F1\",\"status\":\"PASS\"}]}"));
        var result=service.audit(new byte[]{1},new byte[]{2},List.of(),bindings(),products,facts);
        assertEquals("FAIL",result.path("status").asText());assertEquals("窗缺失",result.path("architecture").path("reason").asText());
        verify(vision).auditRender(argThat(prompt->prompt.contains("建筑事实") && prompt.contains("保持单侧柜体") && prompt.contains("后续图片作为输入参考资料存在") && prompt.contains("这里的“成品”始终只指图1") && !prompt.contains("candidateImages") && !prompt.contains("private.png") && !prompt.contains("P1-V1")),anyList());
    }
    @Test void auditUsesResultGuideAndProductBoardWithoutRetryingGeneration() throws Exception {
        when(vision.auditRender(anyString(),anyList())).thenReturn(json.readTree("{\"furnitureCount\":{\"observed\":2,\"status\":\"PASS\"},\"layout\":{\"status\":\"PASS\"},\"items\":[{\"furnitureReferenceId\":\"F1\",\"status\":\"PASS\"}]}"));
        var result=service.audit(new byte[]{1},new byte[]{2},List.of(new byte[]{3}),bindings(),json.createArrayNode());
        assertEquals("PASS",result.path("status").asText());verify(vision,times(1)).auditRender(contains("家具绑定"),argThat(images->images.size()==3));
        assertEquals("UNAVAILABLE",service.unavailable().path("status").asText());
    }
}
