package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import com.rsdp.officeproduct.importer.mapper.RspuVariantMapper;
import com.rsdp.officeproduct.importer.office.dto.SaveCanvasRequest;
import com.rsdp.officeproduct.importer.office.dto.SpaceReviewConfirmRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAgentMessageMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OfficeSpaceReviewServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<OfficeCanvas> canvases = new ArrayList<>();
    private OfficeCanvasMapper canvasMapper;
    private OfficeSpaceReviewService reviewService;
    private OfficeService officeService;

    @BeforeEach void setup() {
        canvases.clear();
        canvasMapper = mock(OfficeCanvasMapper.class);
        when(canvasMapper.insert(any(OfficeCanvas.class))).thenAnswer(call -> {
            OfficeCanvas canvas = call.getArgument(0);
            if (canvas.getCanvasId() == null) {
                canvas.setCanvasId((long) (canvases.size() + 1));
            }
            if (canvas.getCreatedAt() == null) {
                canvas.setCreatedAt(LocalDateTime.now());
            }
            canvases.add(canvas);
            return 1;
        });
        when(canvasMapper.selectList(any())).thenAnswer(call -> {
            List<OfficeCanvas> sorted = new ArrayList<>(canvases);
            sorted.sort((a, b) -> {
                int byTime = b.getCreatedAt().compareTo(a.getCreatedAt());
                return byTime != 0 ? byTime : Long.compare(b.getCanvasId() == null ? 0 : b.getCanvasId(), a.getCanvasId() == null ? 0 : a.getCanvasId());
            });
            return sorted.isEmpty() ? List.of() : List.of(sorted.get(0));
        });
        OfficeProjectService projects = mock(OfficeProjectService.class);
        OfficeProjectAccessService access = mock(OfficeProjectAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        OfficeWorkspaceLockMapper locks = mock(OfficeWorkspaceLockMapper.class);
        reviewService = new OfficeSpaceReviewService(projects, access, currentUser, canvasMapper, locks, json);
        officeService = new OfficeService(locks, projects, access, canvasMapper, mock(OfficeCartMapper.class),
            mock(OfficeAgentMessageMapper.class), mock(RspuMasterMapper.class), mock(RspuVariantMapper.class), json);
    }

    private String zone(String id, double origin) {
        return """
            {"id":"%s","kind":"zone","x":%f,"y":%f,"w":4000,"h":3000,"name":"会议室","zoneType":"MEETING_ROOM",
             "reviewUnit":"mm","polygon":[[%f,%f],[%f,%f],[%f,%f],[%f,%f]]}
            """.formatted(id, origin, origin, origin, origin, origin + 4000, origin, origin + 4000, origin + 3000, origin, origin + 3000);
    }

    private void seedCanvas(String objectsJson, int revision) {
        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId("F1");
        canvas.setRevision(revision);
        canvas.setMode("overview");
        canvas.setObjectsJson(objectsJson);
        canvas.setFilesJson("[]");
        canvas.setBlank(false);
        canvas.setCreatedAt(LocalDateTime.now());
        canvases.add(canvas);
    }

    private SpaceReviewConfirmRequest base(int revision) {
        SpaceReviewConfirmRequest request = new SpaceReviewConfirmRequest();
        request.setBaseCanvasRevision(revision);
        return request;
    }

    @Test void confirmIssuesServerSideCredentialAndNewRevision() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        var result = reviewService.confirm("F1", "Z1", base(3));
        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(4, result.getCanvasRevision());
        assertEquals(3, result.getConfirmedCanvasRevision());
        assertNotNull(result.getConfirmedAt());

        OfficeCanvas saved = canvases.get(1);
        assertEquals(4, saved.getRevision());
        JsonNode zoneNode = json.readTree(saved.getObjectsJson()).get(0);
        JsonNode rc = zoneNode.path("reviewConfirmation");
        assertEquals("CONFIRMED", rc.path("status").asText());
        assertTrue(rc.path("confirmedGeometry").isObject());
        assertEquals(4000.0, rc.path("confirmedGeometry").path("polygon").get(1).get(0).asDouble());
    }

    @Test void confirmRequiresExactBaseRevision() {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        assertEquals("画布版本已过期（当前修订 3），请刷新后重新确认", assertThrows(BusinessException.class,
            () -> reviewService.confirm("F1", "Z1", base(2))).getMessage());
        assertEquals(1, canvases.size());
    }

    @Test void repeatConfirmOnUnchangedSpaceIsIdempotent() {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        reviewService.confirm("F1", "Z1", base(3));
        var again = reviewService.confirm("F1", "Z1", base(4));
        assertEquals(4, again.getCanvasRevision());
        assertEquals("CONFIRMED", again.getStatus());
        assertEquals(2, canvases.size());
    }

    @Test void confirmRejectsMissingSpaceUncalibratedAndMissingCanvas() {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        assertEquals("空间不存在或已被删除", assertThrows(BusinessException.class,
            () -> reviewService.confirm("F1", "Z9", base(3))).getMessage());

        seedCanvas("[{\"id\":\"Z2\",\"kind\":\"zone\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"name\":\"未校准\",\"zoneType\":\"OTHER\"}]", 4);
        assertEquals("请先校准空间的实际尺寸", assertThrows(BusinessException.class,
            () -> reviewService.confirm("F1", "Z2", base(4))).getMessage());

        canvases.clear();
        assertEquals("画布尚未保存，请先保存空间后再确认复核", assertThrows(BusinessException.class,
            () -> reviewService.confirm("F1", "Z1", base(1))).getMessage());
    }

    @Test void confirmAllSignsEveryValidZoneInOneRevision() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "," + zone("Z2", 10000) + "]", 3);
        var result = reviewService.confirmAll("F1", base(3));
        assertEquals(4, result.getCanvasRevision());
        assertEquals(2, result.getConfirmed().size());
        assertEquals(0, result.getAlreadyConfirmed().size());
        assertEquals(0, result.getFailed().size());
        assertEquals(2, canvases.size());

        JsonNode saved = json.readTree(canvases.get(1).getObjectsJson());
        assertEquals("CONFIRMED", saved.get(0).path("reviewConfirmation").path("status").asText());
        assertEquals("CONFIRMED", saved.get(1).path("reviewConfirmation").path("status").asText());
    }

    @Test void confirmAllSkipsConfirmedAndReportsInvalidZones() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "," + zone("Z2", 10000) + "]", 3);
        reviewService.confirm("F1", "Z1", base(3));

        var request = new SaveCanvasRequest();
        request.setProjectId("F1");
        request.setMode("overview");
        request.setRevision(5);
        request.setObjects(List.of(
            json.readTree(zone("Z1", 0)),
            json.readTree(zone("Z2", 10000)),
            json.readTree("{\"id\":\"Z3\",\"kind\":\"zone\",\"x\":0,\"y\":0,\"w\":1,\"h\":1,\"name\":\"未校准\",\"zoneType\":\"OTHER\"}")));
        officeService.saveCanvas(request);

        var result = reviewService.confirmAll("F1", base(5));
        assertEquals(6, result.getCanvasRevision());
        assertEquals(1, result.getConfirmed().size());
        assertEquals("Z2", result.getConfirmed().get(0).getSpaceId());
        assertEquals(1, result.getAlreadyConfirmed().size());
        assertEquals("Z1", result.getAlreadyConfirmed().get(0).getSpaceId());
        assertEquals(1, result.getFailed().size());
        assertEquals("Z3", result.getFailed().get(0).getSpaceId());
        assertEquals("未校准", result.getFailed().get(0).getSpaceName());
        assertEquals("请先校准空间的实际尺寸", result.getFailed().get(0).getReason());
    }

    @Test void confirmAllIsIdempotentWhenNothingToConfirm() {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        reviewService.confirmAll("F1", base(3));
        var again = reviewService.confirmAll("F1", base(4));
        assertEquals(4, again.getCanvasRevision());
        assertEquals(0, again.getConfirmed().size());
        assertEquals(1, again.getAlreadyConfirmed().size());
        assertEquals(2, canvases.size());
    }

    @Test void confirmAllRequiresBaseRevisionAndZones() {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        assertEquals("画布版本已过期（当前修订 3），请刷新后重新确认", assertThrows(BusinessException.class,
            () -> reviewService.confirmAll("F1", base(2))).getMessage());

        seedCanvas("[]", 4);
        assertEquals("请先识别或创建空间", assertThrows(BusinessException.class,
            () -> reviewService.confirmAll("F1", base(4))).getMessage());
    }

    @Test void enteringDesignRequiresAllSpacesReviewConfirmed() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "," + zone("Z2", 10000) + "]", 3);
        var enter = new SaveCanvasRequest();
        enter.setProjectId("F1");
        enter.setMode("design");
        enter.setDesignZoneId("Z1");
        enter.setRevision(4);
        enter.setObjects(List.of(json.readTree(zone("Z1", 0)), json.readTree(zone("Z2", 10000))));
        assertEquals("还有 2 个空间未完成复核确认，请逐个确认或使用「一键复核」后再进入家具排版",
            assertThrows(BusinessException.class, () -> officeService.saveCanvas(enter)).getMessage());

        reviewService.confirmAll("F1", base(3));
        enter.setRevision(4);
        var saved = officeService.saveCanvas(enter);
        assertEquals("design", saved.getMode());
    }

    @Test void designContinuationSaveIsNotBlockedByStaleConfirmationButReloadDowngrades() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        reviewService.confirmAll("F1", base(3));
        var enter = new SaveCanvasRequest();
        enter.setProjectId("F1");
        enter.setMode("design");
        enter.setDesignZoneId("Z1");
        enter.setRevision(4);
        enter.setObjects(List.of(json.readTree(zone("Z1", 0))));
        officeService.saveCanvas(enter);

        var continueDesign = new SaveCanvasRequest();
        continueDesign.setProjectId("F1");
        continueDesign.setMode("design");
        continueDesign.setDesignZoneId("Z1");
        continueDesign.setRevision(5);
        continueDesign.setObjects(List.of(json.readTree(zone("Z1", 500))));
        officeService.saveCanvas(continueDesign);
        OfficeCanvas stored = canvases.get(canvases.size() - 1);
        assertEquals("design", stored.getMode());
        JsonNode staleZone = json.readTree(stored.getObjectsJson()).get(0);
        assertEquals("STALE", staleZone.path("reviewConfirmation").path("status").asText());

        var reloaded = officeService.getCanvas("F1");
        assertEquals("overview", reloaded.getMode());
        assertNull(reloaded.getDesignZoneId());
    }

    @Test void saveCanvasStripsForgedConfirmation() throws Exception {
        var request = new SaveCanvasRequest();
        request.setProjectId("F1");
        request.setMode("overview");
        request.setRevision(1);
        request.setObjects(List.of(json.readTree(zone("Z1", 0).replace("}",
            ",\"reviewConfirmation\":{\"status\":\"CONFIRMED\",\"confirmedByUserId\":\"FORGED\"}}"))));
        officeService.saveCanvas(request);
        JsonNode saved = json.readTree(canvases.get(0).getObjectsJson()).get(0);
        assertTrue(saved.path("reviewConfirmation").isMissingNode());
    }

    @Test void saveCanvasMarksStaleOnGeometryChangeAndKeepsNameOnlyChange() throws Exception {
        seedCanvas("[" + zone("Z1", 0) + "]", 3);
        reviewService.confirm("F1", "Z1", base(3));

        var renameOnly = new SaveCanvasRequest();
        renameOnly.setProjectId("F1");
        renameOnly.setMode("overview");
        renameOnly.setRevision(5);
        renameOnly.setObjects(List.of(json.readTree(zone("Z1", 0).replace("\"会议室\"", "\"大会议室\""))));
        officeService.saveCanvas(renameOnly);
        JsonNode afterRename = json.readTree(canvases.get(2).getObjectsJson()).get(0);
        assertEquals("CONFIRMED", afterRename.path("reviewConfirmation").path("status").asText());

        var geometryChange = new SaveCanvasRequest();
        geometryChange.setProjectId("F1");
        geometryChange.setMode("overview");
        geometryChange.setRevision(6);
        geometryChange.setObjects(List.of(json.readTree(zone("Z1", 500))));
        officeService.saveCanvas(geometryChange);
        JsonNode afterMove = json.readTree(canvases.get(3).getObjectsJson()).get(0);
        assertEquals("STALE", afterMove.path("reviewConfirmation").path("status").asText());

        var revert = new SaveCanvasRequest();
        revert.setProjectId("F1");
        revert.setMode("overview");
        revert.setRevision(7);
        revert.setObjects(List.of(json.readTree(zone("Z1", 0))));
        officeService.saveCanvas(revert);
        JsonNode afterRevert = json.readTree(canvases.get(4).getObjectsJson()).get(0);
        assertEquals("CONFIRMED", afterRevert.path("reviewConfirmation").path("status").asText());
    }
}
