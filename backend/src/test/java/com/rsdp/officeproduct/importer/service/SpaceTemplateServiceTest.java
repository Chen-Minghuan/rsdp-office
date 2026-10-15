package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.request.SpaceTemplateCreateRequest;
import com.rsdp.officeproduct.importer.dto.spatial.*;
import com.rsdp.officeproduct.importer.entity.SpaceTemplate;
import com.rsdp.officeproduct.importer.mapper.SpaceTemplateMapper;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpaceTemplateServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private SpaceTemplateMapper mapper;
    private SpaceTemplateService service;
    private final AtomicReference<SpaceTemplate> saved = new AtomicReference<>();
    @BeforeEach void setup() {
        mapper = mock(SpaceTemplateMapper.class);
        service = new SpaceTemplateService(mapper, new SpatialGeometryService(), json);
        when(mapper.insert(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return 1; });
        when(mapper.byChange(eq("P"), anyString())).thenAnswer(call -> saved.get() != null && saved.get().getClientChangeId().equals(call.getArgument(1)) ? saved.get() : null);
    }
    private SpatialGeometry shape(List<List<Double>> ring) {
        var geometry = new SpatialGeometry(); geometry.setType("POLYGON"); geometry.setCoordinateSystem("WORLD_MM"); geometry.setOuter(ring); geometry.setHoles(List.of()); return geometry;
    }
    private List<List<Double>> rect(double x, double y, double width, double depth) {
        return List.of(List.of(x,y),List.of(x+width,y),List.of(x+width,y+depth),List.of(x,y+depth));
    }
    private SpaceTemplateCreateRequest request() {
        var scene = new SpatialSceneSnapshot(); scene.setSchemaVersion("1.2"); scene.setSceneId("SOURCE"); scene.setSourceSceneId("ANALYSIS"); scene.setWorkingCoordinateSystem("WORLD_MM");
        var calibration = new SpatialCalibrationSnapshot(); calibration.setStatus("CONFIRMED"); calibration.setSourceToWorld(List.of(1.0,0.0,0.0,1.0,0.0,0.0)); scene.setCalibration(calibration);
        var room = new SpatialSpaceSnapshot(); room.setSpaceId("A"); room.setName("会议室"); room.setCategory("MEETING_ROOM"); room.setEvidenceKind("DEMO"); room.setDesignGeometry(shape(rect(0,0,6000,4000)));
        scene.setSpaces(List.of(room));
        var request = new SpaceTemplateCreateRequest(); request.setName("一层空间模板"); request.setClientChangeId("CHANGE"); request.setSourceCanvasRevision(7L); request.setScene(scene); return request;
    }
    @Test void recomputesAreaPreservesConcavityAndDoesNotMutateOriginalOrInventReview() {
        var request = request(); var room = request.getScene().getSpaces().get(0);
        room.setDesignGeometry(shape(List.of(List.of(0.0,0.0),List.of(6000.0,0.0),List.of(6000.0,2000.0),List.of(2000.0,2000.0),List.of(2000.0,6000.0),List.of(0.0,6000.0))));
        var original = json.valueToTree(request);
        var result = service.create("P", request);
        assertEquals(20, result.getAreaM2().doubleValue());
        assertEquals(6, result.getScene().getSpaces().get(0).getDesignGeometry().getOuter().size());
        assertNull(result.getScene().getSpaces().get(0).getRectangleFrame());
        assertEquals("UNCONFIRMED",result.getScene().getSpaces().get(0).getConfirmation().getStatus());
        assertEquals("DEMO", result.getEvidenceKind());
        assertEquals(original,json.valueToTree(request));
    }
    @Test void unknownScaleAndDuplicateIdsFailBeforeInsert() {
        var unknown=request(); unknown.getScene().getCalibration().setStatus("UNKNOWN");
        assertThrows(BusinessException.class,()->service.create("P",unknown));
        var request=request(); request.getScene().setSpaces(List.of(request.getScene().getSpaces().get(0),request.getScene().getSpaces().get(0)));
        final var duplicate=request;
        assertThrows(BusinessException.class,()->service.create("P",duplicate));
        verify(mapper,never()).insert(any());
    }
    @Test void repeatIsIdempotentButDifferentContentWithSameChangeIsRejected() {
        var request=request(); var first=service.create("P",request); var second=service.create("P",request);
        assertEquals(first.getTemplateId(),second.getTemplateId()); verify(mapper,times(1)).insert(any());
        request.setName("另一个名称"); assertThrows(BusinessException.class,()->service.create("P",request));
    }
    @Test void roomOverlapDoesNotRequireIndividualConfirmation() {
        var request=request(); var other=json.convertValue(request.getScene().getSpaces().get(0),SpatialSpaceSnapshot.class);
        other.setSpaceId("B"); other.setDesignGeometry(shape(rect(5000,0,6000,4000)));
        request.getScene().setSpaces(List.of(request.getScene().getSpaces().get(0),other));
        assertEquals(48,service.create("P",request).getAreaM2().doubleValue());
    }
    @Test void retainsHolesAndDeductsIntersectingObstaclesOnlyOnce() {
        var request=request(); request.getScene().getSpaces().get(0).getDesignGeometry().setHoles(List.of(rect(100,100,1000,1000)));
        var first=new SpatialObstacle(); first.setObstacleId("COLUMN-1"); first.setSpaceId("A"); first.setExcludesFloorArea(true); first.setGeometry(shape(rect(100,100,1000,1000)));
        var second=json.convertValue(first,SpatialObstacle.class); second.setObstacleId("COLUMN-2");
        request.getScene().setObstacles(List.of(first,second));
        var result=service.create("P",request);
        assertEquals(23,result.getAreaM2().doubleValue());
        assertEquals(1,result.getScene().getSpaces().get(0).getDesignGeometry().getHoles().size());
        assertEquals(2,result.getScene().getObstacles().size());
    }
    @Test void rejectsSelfIntersectionOutsideHolesAndInvalidProject() {
        var request=request(); request.getScene().getSpaces().get(0).setDesignGeometry(shape(List.of(List.of(0.0,0.0),List.of(6000.0,4000.0),List.of(0.0,4000.0),List.of(6000.0,0.0))));
        assertThrows(BusinessException.class,()->service.create("P",request));
        var outside=request(); outside.getScene().getSpaces().get(0).getDesignGeometry().setHoles(List.of(rect(7000,0,1000,1000)));
        assertThrows(BusinessException.class,()->service.create("P",outside));
        assertThrows(BusinessException.class,()->service.list("../P"));
    }
    @Test void readsAreScopedToTheProjectAndListDoesNotNeedScenePayload() {
        var response=service.create("P",request());
        when(mapper.get("P",response.getTemplateId())).thenReturn(saved.get());
        assertEquals(response.getContentHash(),service.get("P",response.getTemplateId()).getContentHash());
        assertThrows(BusinessException.class,()->service.get("OTHER",response.getTemplateId()));
        var summary=json.convertValue(saved.get(),SpaceTemplate.class); summary.setSceneJson(null); when(mapper.list("P")).thenReturn(List.of(summary));
        assertNull(service.list("P").get(0).getScene());
    }
}
