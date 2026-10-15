package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanSceneRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FloorPlanDimensionServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FloorPlanAnalysisMapper analyses = mock(FloorPlanAnalysisMapper.class);
    private final FloorPlanSceneRevisionMapper revisions = mock(FloorPlanSceneRevisionMapper.class);
    private final QwenVisionClient qwen = mock(QwenVisionClient.class);
    private final VisionImagePreparer preparer = new VisionImagePreparer(new VisionProperties());
    private final FloorPlanDimensionService service = new FloorPlanDimensionService(analyses, revisions, qwen, preparer, json);
    private FloorPlanAnalysis analysis;
    private FloorPlanSceneRevision scene;
    private ObjectNode raw;

    @BeforeEach void setup() throws Exception {
        analysis = new FloorPlanAnalysis(); analysis.setInputMode("image"); analysis.setWorkingAnalysisRevision(1);
        scene = new FloorPlanSceneRevision(); scene.setAnalysisId("A"); scene.setAnalysisRevision(1); scene.setRevisionStatus("READY");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB), "png", out);
        String hash = VisionImagePreparer.sha256(out.toByteArray());
        raw = json.createObjectNode().put("units", "px").put("coordinateSystem", "SOURCE_PAGE").put("imageSha256", hash);
        raw.putObject("preview").put("width", 1000).put("height", 500).put("pngBase64", Base64.getEncoder().encodeToString(out.toByteArray()))
            .putObject("bounds").put("minX", 0).put("minY", 0).put("maxX", 1000).put("maxY", 500);
        raw.set("modelOutput", json.readTree("""
            {"rooms":[{"label":"原始空间","polygon":[[1,2],[3,4],[5,6]]}],"dimensionReferences":[
              {"text":"6000","confidence":"high","start":[100,100],"end":[700,100]},
              {"text":"4000","confidence":"high","start":[100,100],"end":[100,900]}]}
            """));
        raw.set("dimensionCalibration", json.valueToTree(DimensionScaleEstimator.estimate(raw.path("modelOutput").path("dimensionReferences"), 1000, 500, hash, "R", "mm")));
        scene.setRawResult(raw.toString());
        when(analyses.selectById("A")).thenReturn(analysis); when(revisions.selectByRevision("A", 1)).thenReturn(scene);
        when(revisions.compareAndSetDimensionResult(eq("A"), eq(1), anyString(), anyString())).thenAnswer(invocation -> { scene.setRawResult(invocation.getArgument(3)); return 1; });
    }

    @Test void confirmationPersistsProofWithoutChangingRoomsOrSourceUnitsAndIsIdempotent() throws Exception {
        var result = service.confirm("A", 1, "R", "mm");
        assertNotNull(result.getConfirmationId()); assertEquals(10, result.getMmPerPixel());
        var saved = json.readTree(scene.getRawResult());
        assertEquals(raw.path("modelOutput").path("rooms"), saved.path("modelOutput").path("rooms"));
        assertEquals("px", saved.path("units").asText());
        assertEquals(result.getConfirmationId(), service.confirm("A", 1, "R", "mm").getConfirmationId());
        verify(revisions, times(1)).compareAndSetDimensionResult(anyString(), anyInt(), anyString(), anyString());
        verifyNoInteractions(qwen);
    }
    @Test void validatesUnitChoiceOnServerAndNeverAcceptsArbitraryClientScale() {
        assertEquals(100, service.confirm("A", 1, "R", "cm").getMmPerPixel());
        assertNull(service.get("A", 1, "mm").getConfirmationId());
    }
    @Test void staleRevisionReportOrSourceHashCannotBeConfirmed() {
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "OLD", "mm"));
        analysis.setWorkingAnalysisRevision(2);
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "R", "mm"));
        analysis.setWorkingAnalysisRevision(1);
        raw.put("imageSha256", "CHANGED"); scene.setRawResult(raw.toString());
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "R", "mm"));
        verify(revisions, never()).compareAndSetDimensionResult(anyString(), anyInt(), anyString(), anyString());
    }
    @Test void insufficientOrConflictingEvidenceCannotBeConfirmed() {
        ((ObjectNode) raw.path("modelOutput").path("dimensionReferences").get(1)).put("text", "5000");
        scene.setRawResult(raw.toString());
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "R", "mm"));
        ((ObjectNode) raw.path("modelOutput")).putArray("dimensionReferences");
        scene.setRawResult(raw.toString());
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "R", "mm"));
    }
    @Test void recognizesOnlyDimensionsFromCachedImageAndKeepsExistingRooms() throws Exception {
        ObjectNode response = json.createObjectNode();
        response.set("dimensionModelOutput", raw.path("modelOutput"));
        response.set("dimensionCalibration", raw.path("dimensionCalibration"));
        when(qwen.recognizeDimensions(any())).thenReturn(response);
        var result = service.recognize("A", 1);
        assertEquals("READY", result.getStatus()); assertNull(result.getConfirmationId());
        assertEquals(raw.path("modelOutput").path("rooms"), json.readTree(scene.getRawResult()).path("modelOutput").path("rooms"));
        verify(qwen).recognizeDimensions(argThat(image -> image.width() == 1000 && image.height() == 500));
        verify(revisions, never()).updateByRevision(any());
    }
    @Test void concurrentUpdateAndCorruptCachedPreviewDoNotOverwriteSource() {
        when(revisions.compareAndSetDimensionResult(anyString(), anyInt(), anyString(), anyString())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.confirm("A", 1, "R", "mm"));
        raw.with("preview").put("pngBase64", "invalid"); scene.setRawResult(raw.toString());
        assertThrows(BusinessException.class, () -> service.recognize("A", 1));
        verifyNoInteractions(qwen);
    }
}
