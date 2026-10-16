package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FloorPlanDesignCorrectionServiceTest {
    private static final String HASH = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfficeCanvasMapper canvas = mock(OfficeCanvasMapper.class);
    private final FloorPlanAnalysisMapper analyses = mock(FloorPlanAnalysisMapper.class);
    private final FloorPlanSceneRevisionMapper revisions = mock(FloorPlanSceneRevisionMapper.class);
    private final FloorPlanDesignCorrectionService service = new FloorPlanDesignCorrectionService(canvas, analyses, revisions, mapper, new SpatialGeometryService());

    private void source(String id, String project, String hash) {
        var analysis = new FloorPlanAnalysis(); analysis.setProjectId(project);
        when(analyses.selectById(id)).thenReturn(analysis);
        var revision = new FloorPlanSceneRevision();
        revision.setRawResult("{\"imageSha256\":\"" + hash + "\",\"units\":\"px\",\"preview\":{\"width\":1000,\"height\":800}}");
        when(revisions.selectByRevision(id, 1)).thenReturn(revision);
    }

    private OfficeCanvas snapshot(int revision, String objects) {
        var result = new OfficeCanvas(); result.setRevision(revision); result.setObjectsJson(objects); return result;
    }

    private String zone(String name, String id, String unit, double scale, String ring) {
        return "{\"kind\":\"zone\",\"name\":\"" + name + "\",\"sourceAnalysisId\":\"" + id
            + "\",\"sourceAnalysisRevision\":1,\"reviewUnit\":\"" + unit + "\",\"mmPerSourceUnit\":" + scale
            + ",\"geometrySource\":\"manual_geometry\",\"evidenceKind\":\"MANUAL\",\"polygon\":" + ring + "}";
    }

    @Test
    void latestManualCorrectionsSurviveNewRecognitionAndCalibratedDraftConvertsBackToPixels() {
        source("OLD", "P", HASH);
        String latest = zone("过道", "OLD", "mm", 10, "[[100,100],[400,100],[400,7000],[100,7000]]");
        String earlier = zone("过道", "OLD", "source", 1, "[[10,10],[200,10],[200,400],[10,400]]");
        when(canvas.selectCorrectionSnapshots(anyString(), anyString())).thenReturn(List.of(snapshot(455, "[" + latest + "]"), snapshot(452, "[" + earlier + "]")));
        var result = service.find("P", HASH);
        assertEquals(1, result.size());
        assertEquals(455, result.get(0).canvasRevision());
        assertEquals(List.of(40.0, 700.0), result.get(0).polygon().get(2));
        verify(revisions, times(1)).selectByRevision("OLD", 1);
        verify(canvas, never()).insert(any(OfficeCanvas.class));
    }

    @Test
    void rejectsOtherImagesProjectsDuplicateNamesAndInvalidGeometry() {
        source("OTHER-IMAGE", "P", "b".repeat(64)); source("OTHER-PROJECT", "OTHER", HASH); source("OK", "P", HASH);
        String ring = "[[10,10],[200,10],[200,400],[10,400]]";
        String z = zone("重名", "OK", "source", 1, ring);
        when(canvas.selectCorrectionSnapshots(anyString(), anyString())).thenReturn(List.of(snapshot(1, "["
            + zone("异图", "OTHER-IMAGE", "source", 1, ring) + ","
            + zone("异项目", "OTHER-PROJECT", "source", 1, ring) + "," + z + "," + z + ","
            + zone("自交", "OK", "source", 1, "[[10,10],[200,400],[200,10],[10,400]]") + ","
            + zone("越界", "OK", "source", 1, "[[10,10],[1200,10],[1200,400],[10,400]]") + ","
            + zone("无比例", "OK", "mm", 0, ring) + "]")));
        assertTrue(service.find("P", HASH).isEmpty());
        verify(revisions, never()).selectByRevision("OTHER-PROJECT", 1);
    }

    @Test
    void absentHashDoesNotScanHistoryAndAutomaticCandidatesAreNotCorrections() {
        assertTrue(service.find("P", null).isEmpty()); verifyNoInteractions(canvas);
        String zone = zone("候选", "OK", "source", 1, "[[10,10],[200,10],[200,400],[10,400]]").replace("manual_geometry", "ai_vision");
        when(canvas.selectCorrectionSnapshots(anyString(), anyString())).thenReturn(List.of(snapshot(1, "[" + zone + "]")));
        assertTrue(service.find("P", HASH).isEmpty()); verifyNoInteractions(analyses, revisions);
    }
}
