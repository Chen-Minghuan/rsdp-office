package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private ObjectNode passRaw() throws Exception {
        return (ObjectNode)json.readTree("""
            {"furnitureCount":{"expected":2,"observed":2,"status":"PASS"},
             "layout":{"status":"PASS","reason":""},"imageQuality":{"status":"PASS","reason":""},
             "composition":{"singleScene":true,"containsReferenceBoard":false,"containsPlan":false,"containsLabels":false,"containsCollage":false,"status":"PASS","reason":""},
             "items":[{"furnitureReferenceId":"F1","productReferenceId":"P1","status":"PASS","reason":""}],"providerRequestId":"req"}
            """);
    }
    @Test void normalizesPassFailAndMissingProductEvidence() throws Exception {
        var raw=passRaw();
        var pass=service.normalize(raw,bindings());assertEquals("PASS",pass.path("status").asText());assertEquals(2,pass.path("furnitureCount").path("expected").asInt());assertEquals("req",pass.path("providerRequestId").asText());
        ((ObjectNode)raw.path("furnitureCount")).put("status","FAIL");
        assertEquals("FAIL",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();raw.putArray("items");
        var missing=service.normalize(raw,bindings());assertEquals("UNCERTAIN",missing.path("status").asText());assertEquals("UNCERTAIN",missing.path("items").get(0).path("status").asText());
    }
    @Test void deterministicChecksRejectContradictoryOrIncompleteAudits() throws Exception {
        var raw=passRaw();((ObjectNode)raw.path("furnitureCount")).put("observed",1);
        assertEquals("FAIL",service.normalize(raw,bindings()).path("status").asText(),"数量矛盾不能被模型的PASS覆盖");
        raw=passRaw();((ObjectNode)raw.path("furnitureCount")).remove("expected");
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();((ObjectNode)raw.path("furnitureCount")).put("expected",3);
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();((ObjectNode)raw.path("furnitureCount")).remove("observed");
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();raw.remove("composition");
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();((ObjectNode)raw.path("composition")).remove("singleScene");
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();((ObjectNode)raw.path("composition")).put("singleScene",false);
        assertEquals("FAIL",service.normalize(raw,bindings()).path("status").asText());
        raw=passRaw();((ObjectNode)raw.path("items").get(0)).put("productReferenceId","P2");
        var wrongProduct=service.normalize(raw,bindings());assertEquals("FAIL",wrongProduct.path("status").asText());
        assertEquals("P1",wrongProduct.path("items").get(0).path("productReferenceId").asText());
        assertEquals("P2",wrongProduct.path("items").get(0).path("observedProductReferenceId").asText());
        raw=passRaw();((ObjectNode)raw.path("items").get(0)).remove("productReferenceId");
        assertEquals("UNCERTAIN",service.normalize(raw,bindings()).path("status").asText());
    }
    @Test void auditIncludesArchitecturalFactsAndProductVisualProfiles() throws Exception {
        var products=json.createArrayNode();var product=products.addObject().put("productId","SKU-1");product.putArray("candidateImages").addObject().put("imageUrl","/uploads/images/private.png");product.putObject("visualProfile").put("appearancePrompt","保持单侧柜体").putArray("views").addObject().put("imageId","P1-V1");
        var facts=json.createObjectNode().put("openingsKnown",true);facts.putArray("openings").addObject().put("kind","WINDOW");
        var raw=passRaw();raw.putObject("architecture").put("status","FAIL").put("reason","窗缺失");
        when(vision.auditRender(anyString(),anyList())).thenReturn(raw);
        var result=service.audit(new byte[]{1},new byte[]{2},List.of(),bindings(),products,facts);
        assertEquals("FAIL",result.path("status").asText());assertEquals("窗缺失",result.path("architecture").path("reason").asText());
        verify(vision).auditRender(argThat(prompt->prompt.contains("建筑事实") && prompt.contains("保持单侧柜体") && prompt.contains("后续图片作为输入参考资料存在") && prompt.contains("这里的“成品”始终只指图1") && !prompt.contains("candidateImages") && !prompt.contains("private.png") && !prompt.contains("P1-V1")),anyList());
    }
    @Test void auditUsesResultGuideAndProductBoardWithoutRetryingGeneration() throws Exception {
        when(vision.auditRender(anyString(),anyList())).thenReturn(passRaw());
        var result=service.audit(new byte[]{1},new byte[]{2},List.of(new byte[]{3}),bindings(),json.createArrayNode());
        assertEquals("PASS",result.path("status").asText());verify(vision,times(1)).auditRender(contains("家具绑定"),argThat(images->images.size()==3));
        assertEquals("UNAVAILABLE",service.unavailable().path("status").asText());
    }
}
