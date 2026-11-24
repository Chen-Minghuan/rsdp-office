package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class RenderSubjectCropperTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode view() {
        ObjectNode view=json.createObjectNode().put("requiresSubjectIsolation",true).put("originalImageSuitableForDirectGeneration",false);
        view.putObject("target").put("status","IDENTIFIED").put("confidence",0.95).putArray("bbox").add(250).add(250).add(750).add(750);
        return view;
    }
    @Test void mapsNormalizedTargetToPixelsWithoutModifyingOriginal() {
        BufferedImage source=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);
        var result=RenderSubjectCropper.prepare(source,view(),"茶桌");
        assertEquals("TARGET_BBOX_CROP",result.method());
        assertEquals(216,result.image().getWidth());assertEquals(108,result.image().getHeight());
        assertEquals(400,source.getWidth());assertEquals(200,source.getHeight());
        assertFalse(result.warnings().isEmpty());
    }
    @Test void usesOriginalOnlyWhenExplicitlySafeAndBlocksAmbiguousTarget() {
        BufferedImage source=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);
        ObjectNode safe=view().put("requiresSubjectIsolation",false).put("originalImageSuitableForDirectGeneration",true);
        assertSame(source,RenderSubjectCropper.prepare(source,safe,"工位").image());
        ObjectNode ambiguous=view();((ObjectNode)ambiguous.path("target")).put("status","AMBIGUOUS");
        assertThrows(BusinessException.class,()->RenderSubjectCropper.prepare(source,ambiguous,"茶桌"));
    }

    @Test void isolationRequirementCannotFallBackToOriginalWhenDirectFlagIsMissing() {
        BufferedImage source=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);ObjectNode required=view();required.remove("originalImageSuitableForDirectGeneration");
        var result=RenderSubjectCropper.prepare(source,required,"茶桌",true);
        assertEquals("TARGET_BBOX_CROP",result.method());assertNotSame(source,result.image());
    }
}
