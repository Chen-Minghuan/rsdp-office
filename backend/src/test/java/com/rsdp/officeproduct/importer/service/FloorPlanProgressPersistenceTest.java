package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.*;
import com.rsdp.officeproduct.importer.mapper.*;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FloorPlanProgressPersistenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FloorPlanAnalysisMapper analyses = mock(FloorPlanAnalysisMapper.class);
    private final FloorPlanRoomMapper rooms = mock(FloorPlanRoomMapper.class);
    private final FloorPlanSceneRevisionMapper revisions = mock(FloorPlanSceneRevisionMapper.class);
    private final ImageAssetsMapper assets = mock(ImageAssetsMapper.class);
    private final FloorPlanService service = new FloorPlanService(analyses,
        mock(com.rsdp.officeproduct.importer.office.service.OfficeProjectService.class), rooms, revisions, assets,
        mock(CadParseClient.class), mock(VisionRecognitionClient.class), mock(FloorPlanDimensionService.class),
        mock(FloorPlanDesignCorrectionService.class), json, new SpatialGeometryService());
    private FloorPlanAnalysis analysis;
    private FloorPlanSceneRevision revision;

    @BeforeEach void setup() {
        analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId("A"); analysis.setStatus("analyzing"); analysis.setPreviewImageId("PREVIEW");
        analysis.setLatestAnalysisRevision(1); analysis.setInputMode("image");
        analysis.setRawResult("{\"imageSha256\":\"hash\",\"processing\":{\"stage\":\"RECOGNIZING\"}}");
        revision = new FloorPlanSceneRevision(); revision.setAnalysisId("A"); revision.setAnalysisRevision(1);
        revision.setRevisionStatus("PROCESSING"); revision.setRawResult(analysis.getRawResult());
        var asset = new ImageAssets(); asset.setStorageUrl("http://test/preview.png");
        when(analyses.selectById("A")).thenReturn(analysis);
        when(revisions.selectByRevision("A", 1)).thenReturn(revision);
        when(assets.selectById("PREVIEW")).thenReturn(asset);
        when(rooms.selectByAnalysisRevision("A", 1)).thenReturn(java.util.List.of());
    }

    @Test void previewAndRealPhaseRemainReadableWhenRecognitionFails() {
        var running = service.getAnalysis("A", null);
        assertEquals("analyzing", running.getStatus());
        assertEquals("http://test/preview.png", running.getPreviewUrl());
        assertEquals("RECOGNIZING", running.getProcessing().path("stage").asText());
        service.failBackground("A", "模型超时", json.createObjectNode().put("stage", "FAILED"));
        var failed = service.getAnalysis("A", null);
        assertEquals("failed", failed.getStatus());
        assertEquals("http://test/preview.png", failed.getPreviewUrl());
        assertEquals("FAILED", failed.getProcessing().path("stage").asText());
        assertEquals("FAILED", revision.getRevisionStatus());
        assertThrows(BusinessException.class, () -> service.completeBackground("A", json.createObjectNode(), "http://test"));
        verify(rooms, never()).insert(any(FloorPlanRoom.class));
    }

    @Test void finalSaveReusesEarlyPreviewAndRejectsDuplicateCompletion() throws Exception {
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("{\"rooms\":[],\"units\":\"px\",\"drawingBounds\":{\"minX\":0,\"minY\":0,\"maxX\":100,\"maxY\":80}}");
        service.completeBackground("A", result, "http://test");
        assertEquals("awaiting_confirm", analysis.getStatus());
        assertEquals("READY", revision.getRevisionStatus());
        assertEquals("PREVIEW", revision.getPreviewImageId());
        verifyNoInteractions(assets);
        verify(rooms, never()).insert(any(FloorPlanRoom.class));
        assertThrows(BusinessException.class, () -> service.completeBackground("A", result, "http://test"));
    }

    @Test void progressCannotOverwriteConfirmationFieldsOrChangeTerminalAnalyses() {
        service.updateProgress("A", json.createObjectNode().put("stage", "REFINING"));
        verify(analyses).updateById(argThat((FloorPlanAnalysis value) -> value.getStatus() == null && value.getPreviewImageId() == null && value.getRawResult().contains("REFINING")));
        clearInvocations(analyses);
        analysis.setStatus("confirmed");
        service.updateProgress("A", json.createObjectNode().put("stage", "DONE"));
        verify(analyses, never()).updateById(any(FloorPlanAnalysis.class));
    }
}
