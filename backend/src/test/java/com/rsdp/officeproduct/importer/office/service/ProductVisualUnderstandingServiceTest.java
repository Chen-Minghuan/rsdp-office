package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductVisualUnderstandingServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final QwenVisionClient vision=mock(QwenVisionClient.class);
    private final VisionProperties visionProperties=new VisionProperties();
    private final RenderProperties renderProperties=new RenderProperties();
    private ProductVisualUnderstandingService service;

    @BeforeEach void setup(@TempDir Path directory) {
        visionProperties.setModel("qwen3-vl-max");
        renderProperties.setStorageDirectory(directory.toString());
        renderProperties.getProductAgent().setEnabled(true);
        service=new ProductVisualUnderstandingService(vision,new ProductVisualProfileCache(renderProperties,json),visionProperties,renderProperties,json);
    }

    private ProductVisualUnderstandingService.SourceImage image(String id,String hash) {
        return new ProductVisualUnderstandingService.SourceImage(id,"/uploads/images/"+id+".png",hash,new byte[128]);
    }
    private ProductVisualUnderstandingService.Request request(String productId) {
        return new ProductVisualUnderstandingService.Request(productId,"SPU1","V1","测试工位",List.of(image("IMG-1","a".repeat(64)),image("IMG-2","b".repeat(64))));
    }
    private ObjectNode raw(String productId) throws Exception {
        return (ObjectNode)json.readTree("""
            {"productId":"%s","rspuId":"SPU1","variantId":"V1","model":"qwen3-vl-max",
             "views":[{"imageId":"IMG-1","view":"FRONT_3Q","imageComposition":"SINGLE_PRODUCT","target":{"status":"IDENTIFIED","category":"工位","description":"完整工位","bbox":[],"confidence":0.93},"containsNonTargetProducts":false,"requiresSubjectIsolation":false,"originalImageSuitableForDirectGeneration":true,"confidence":0.93,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]},
                      {"imageId":"IMG-2","view":"BACK_3Q","imageComposition":"SINGLE_PRODUCT","target":{"status":"IDENTIFIED","category":"工位","description":"完整工位","bbox":[],"confidence":0.88},"containsNonTargetProducts":false,"requiresSubjectIsolation":false,"originalImageSuitableForDirectGeneration":true,"confidence":0.88,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]}],
             "detectedObjects":[{"imageId":"IMG-1","category":"工位","role":"TARGET","bbox":[0,0,1000,1000],"confidence":0.93}],
             "overallShape":{"description":"矩形桌面和单侧柜体","evidenceImageIds":["IMG-1"],"confidence":0.9},
             "components":[{"name":"柜体","description":"桌面下方单侧","mustPreserve":true,"evidenceImageIds":["IMG-2"],"confidence":0.88}],
             "assemblyConstraints":["柜体只能位于一侧"],"mustPreserve":["桌面上方挡板"],
             "materialsAndColors":[{"description":"浅木色桌面","evidenceImageIds":["IMG-1"]}],
             "appearancePrompt":"保持单侧柜体与挡板的真实连接关系","warnings":[],"uncertainties":[]}
            """.formatted(productId));
    }

    @Test void normalizesAndCachesByProductImagesModelAndProtocol() throws Exception {
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(raw("P1"));
        var first=service.understandAll(List.of(request("P1")));
        assertEquals("SUCCEEDED",first.status());assertEquals(1,first.generatedCount());assertEquals(0,first.cacheHits());
        var profile=first.forProduct("P1").profile();
        assertEquals("product-visual-profile-v2",profile.path("schemaVersion").asText());
        assertEquals(64,profile.path("cacheKey").asText().length());
        assertEquals("IMG-2",profile.path("components").get(0).path("evidenceImageIds").get(0).asText());
        assertFalse(profile.has("reasoning_content"));

        var second=service.understandAll(List.of(request("P1")));
        assertEquals("CACHE_HIT",second.forProduct("P1").status());assertEquals(1,second.cacheHits());
        verify(vision,times(1)).understandProduct(anyString(),anyString(),anyList());
        assertTrue(second.manifest(4).path("items").get(0).has("profileCacheKey"));
    }

    @Test void rejectsForgedImagesLayoutOverridesAndInvalidConfidence() throws Exception {
        var forged=raw("P1");((ObjectNode)forged.path("components").get(0)).putArray("evidenceImageIds").add("NOT-INPUT");
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(forged);
        assertEquals("INVALID_RESPONSE",service.understandAll(List.of(request("P1"))).forProduct("P1").status());

        var layout=raw("P1");layout.put("furnitureCount",99);
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(layout);
        assertEquals("INVALID_RESPONSE",service.understandAll(List.of(request("P1"))).forProduct("P1").status());

        var confidence=raw("P1");((ObjectNode)confidence.path("views").get(0)).put("confidence",1.2);
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(confidence);
        assertEquals("INVALID_RESPONSE",service.understandAll(List.of(request("P1"))).forProduct("P1").status());
    }

    @Test void unavailableAndPerRenderLimitUseExplicitStaticFallback() throws Exception {
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenThrow(new com.rsdp.officeproduct.importer.common.BusinessException("offline"));
        var unavailable=service.understandAll(List.of(request("P1")));
        assertEquals("UNAVAILABLE",unavailable.status());assertNull(unavailable.forProduct("P1").profile());

        reset(vision);renderProperties.getProductAgent().setMaxNewProductsPerRender(1);
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenAnswer(call->raw(call.getArgument(1,String.class).contains("productId=P2")?"P2":"P1"));
        var limited=service.understandAll(List.of(request("P1"),request("P2")));
        assertEquals("PARTIAL",limited.status());assertEquals("GENERATED",limited.forProduct("P1").status());
        assertEquals("SKIPPED",limited.forProduct("P2").status());verify(vision,times(1)).understandProduct(anyString(),anyString(),anyList());
    }

    @Test void validatesProductSetTargetAndRejectsContradictoryResponses() throws Exception {
        var productSet=raw("P1");ObjectNode view=(ObjectNode)productSet.path("views").get(0);
        view.put("imageComposition","PRODUCT_SET").put("containsNonTargetProducts",true)
            .put("requiresSubjectIsolation",true).put("originalImageSuitableForDirectGeneration",false);
        ((ObjectNode)view.path("target")).putArray("bbox").add(100).add(100).add(800).add(900);
        view.putArray("warnings").add("MULTIPLE_PRODUCTS");
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(productSet);
        var profile=service.understandAll(List.of(request("P1"))).forProduct("P1").profile();
        assertEquals("PRODUCT_SET",profile.path("views").get(0).path("imageComposition").asText());
        assertTrue(profile.path("views").get(0).path("requiresSubjectIsolation").asBoolean());

        var contradictory=raw("P2");((ObjectNode)contradictory.path("views").get(0)).put("imageComposition","PRODUCT_SET");
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(contradictory);
        assertEquals("INVALID_RESPONSE",service.understandAll(List.of(request("P2"))).forProduct("P2").status());

        var invalidBox=raw("P3");((ObjectNode)invalidBox.path("views").get(0).path("target")).putArray("bbox").add(800).add(100).add(100).add(900);
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(invalidBox);
        assertEquals("INVALID_RESPONSE",service.understandAll(List.of(request("P3"))).forProduct("P3").status());
    }

    @Test void missingViewBecomesUnknownInsteadOfGuessing() throws Exception {
        var missing=raw("P1");((com.fasterxml.jackson.databind.node.ArrayNode)missing.path("views")).remove(1);
        when(vision.understandProduct(anyString(),anyString(),anyList())).thenReturn(missing);
        JsonNode view=service.understandAll(List.of(request("P1"))).forProduct("P1").profile().path("views").get(1);
        assertEquals("UNKNOWN",view.path("view").asText());assertEquals(0,view.path("confidence").asDouble());
        assertFalse(view.path("suitableAsPrimary").asBoolean());
    }
}
