package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenderReferencePlannerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final List<ProductVisualUnderstandingService.SourceImage> candidates=List.of(image("IMG-1"),image("IMG-2"),image("IMG-3"));
    private static ProductVisualUnderstandingService.SourceImage image(String id) {
        return new ProductVisualUnderstandingService.SourceImage(id,"/"+id+".png","a".repeat(64),new byte[128]);
    }
    private ObjectNode profile() throws Exception {
        return (ObjectNode)json.readTree("""
            {"views":[
              {"imageId":"IMG-1","view":"FRONT_3Q","confidence":0.8,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]},
              {"imageId":"IMG-2","view":"BACK_3Q","confidence":0.95,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]},
              {"imageId":"IMG-3","view":"LEFT","confidence":0.9,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]}],
             "overallShape":{"evidenceImageIds":["IMG-2"]},"components":[]}
            """);
    }
    @Test void prefersOneReliableWholeViewInsteadOfAllAngles() throws Exception {
        var selected=RenderReferencePlanner.select(candidates,profile(),"工位");
        assertEquals(List.of("IMG-2"),selected.images().stream().map(ProductVisualUnderstandingService.SourceImage::imageId).toList());
    }
    @Test void addsOneSecondViewOnlyWhenAssemblyEvidenceNeedsIt() throws Exception {
        var profile=profile();((ObjectNode)profile.path("views").get(2)).put("confidence",0.99);
        profile.putArray("components").addObject().putArray("evidenceImageIds").add("IMG-2");
        var selected=RenderReferencePlanner.select(candidates,profile,"工位");
        assertEquals(List.of("IMG-3","IMG-2"),selected.images().stream().map(ProductVisualUnderstandingService.SourceImage::imageId).toList());
    }
    @Test void excludesMixedVariantAndBlocksUnreliableMainImage() throws Exception {
        var profile=profile();((ObjectNode)profile.path("views").get(2)).putArray("warnings").add("VARIANT_MIX");
        profile.putArray("components").addObject().putArray("evidenceImageIds").add("IMG-3");
        assertEquals(1,RenderReferencePlanner.select(candidates,profile,"工位").images().size());
        ((ObjectNode)profile.path("views").get(0)).putArray("warnings").add("MULTIPLE_PRODUCTS");
        assertThrows(BusinessException.class,()->RenderReferencePlanner.select(candidates,profile,"工位"));
    }
    @Test void allowsMultipleProductsOnlyWithReliableTargetIsolation() throws Exception {
        var profile=profile();ObjectNode main=(ObjectNode)profile.path("views").get(0);
        main.putArray("warnings").add("MULTIPLE_PRODUCTS");
        main.put("requiresSubjectIsolation",true).putObject("target").put("status","IDENTIFIED").put("confidence",0.95).putArray("bbox").add(0).add(0).add(900).add(900);
        profile.path("views").forEach(view->{if(!"IMG-1".equals(view.path("imageId").asText())) ((ObjectNode)view).put("suitableAsPrimary",false);});
        assertEquals("IMG-1",RenderReferencePlanner.select(candidates,profile,"茶桌").images().get(0).imageId());
        ((ObjectNode)main.path("target")).put("status","AMBIGUOUS");
        assertThrows(BusinessException.class,()->RenderReferencePlanner.select(candidates,profile,"茶桌"));
    }
    @Test void blocksAmbiguousTargetInsteadOfReusingOriginalImage() throws Exception {
        var profile=profile();((ObjectNode)profile.path("views").get(0)).putObject("target").put("status","AMBIGUOUS");
        var error=assertThrows(BusinessException.class,()->RenderReferencePlanner.select(candidates,profile,"茶桌"));
        assertTrue(error.getMessage().contains("目标商品不明确"));
    }
    @Test void uncertainAgentUsesOnlyMainAndDisabledAgentKeepsStaticCandidates() throws Exception {
        var profile=profile();profile.path("views").forEach(view->{((ObjectNode)view).put("suitableAsPrimary",false);});
        assertEquals(1,RenderReferencePlanner.select(candidates,profile,"工位").images().size());
        assertEquals(3,RenderReferencePlanner.select(candidates,null,"工位").images().size());
    }
}
