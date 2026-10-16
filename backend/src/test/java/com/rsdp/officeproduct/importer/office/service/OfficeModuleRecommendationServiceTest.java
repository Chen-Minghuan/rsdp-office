package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.*;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficeModuleRecommendationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private OfficeService office;
    private OfficeCartMapper carts;
    private OfficeModuleRecommendationService service;
    private CanvasDto canvas;
    @BeforeEach void setup() throws Exception {
        office = mock(OfficeService.class); carts = mock(OfficeCartMapper.class);
        service = new OfficeModuleRecommendationService(office, mock(OfficeProjectMapper.class), carts, json);
        canvas = new CanvasDto(); canvas.setRevision(7); canvas.setMode("design"); canvas.setDesignZoneId("Z"); canvas.setFiles(List.of());
        canvas.setObjects(json.readValue("""
            [{"id":"Z","kind":"zone","reviewUnit":"mm","name":"办公区","polygon":[[0,0],[6000,0],[6000,4000],[0,4000]]},
             {"id":"A","kind":"furniture","zoneId":"Z","w":1200,"h":600,"x":300,"y":300,"rotation":0,"furnitureTemplateId":"desk-straight","layoutConfirmed":true},
             {"id":"B","kind":"furniture","zoneId":"Z","w":1200,"h":600,"x":2000,"y":300,"rotation":90,"furnitureTemplateId":"desk-straight","layoutConfirmed":true},
             {"id":"OTHER","kind":"furniture","zoneId":"OTHER-ZONE","w":1200,"h":600,"productId":"KEEP","layoutConfirmed":true}]
            """, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {}));
        when(office.getCanvas("P")).thenReturn(canvas);
        when(office.listOfficeProducts()).thenReturn(List.of(sku("FIT", "1200", "600"), sku("SMALL", "600", "300"), sku("TOO-WIDE", "1200.01", "600"), new OfficeProductDto()));
    }
    private OfficeProductDto sku(String id, String width, String depth) {
        var p = new OfficeProductDto(); p.setId(id); p.setVariantId(id); p.setRspuId("MASTER"); p.setWidthMm(new BigDecimal(width)); p.setDepthMm(new BigDecimal(depth)); p.setPrice(BigDecimal.TEN); return p;
    }
    private ModuleRecommendationRequest request() {
        var request = new ModuleRecommendationRequest(); request.setZoneId("Z"); request.setModuleId("A"); request.setCanvasRevision(7); request.setProductId("FIT"); return request;
    }
    @Test void returnsRealSkuMatchesInFitOrderAndReportsMissingDimensions() {
        var result = service.recommend("P", request());
        assertEquals(List.of("FIT", "SMALL"), result.getCandidates().stream().map(item -> item.getProduct().getId()).toList());
        assertEquals(4, result.getEligibleVariantCount());
        assertEquals(1, result.getMissingDimensions()); assertEquals(2, result.getSameModuleCount());
        verifyNoInteractions(carts);
    }
    @Test void enforcesAllConfirmedCurrentRevisionAndOwnedModule() {
        var stale = request(); stale.setCanvasRevision(6); assertThrows(BusinessException.class, () -> service.recommend("P", stale));
        var other = request(); other.setModuleId("OTHER"); assertThrows(BusinessException.class, () -> service.recommend("P", other));
        ((Map<String,Object>) canvas.getObjects().get(2)).put("layoutConfirmed", false);
        assertThrows(BusinessException.class, () -> service.recommend("P", request())); verifyNoInteractions(carts);
    }
    @Test void selectionRechecksAvailabilityAndSizeBeforeAnyWrites() {
        var request = request(); request.setProductId("TOO-WIDE");
        assertThrows(BusinessException.class, () -> service.select("P", request));
        when(office.listOfficeProducts()).thenReturn(List.of());
        assertEquals(0, service.recommend("P", request()).getEligibleVariantCount());
        assertThrows(BusinessException.class, () -> service.select("P", request()));
        verify(carts, never()).insert(any(OfficeCart.class)); verify(office, never()).saveCanvas(any());
    }
    @Test void bindsSameModulesWithoutMovingOrResizingAndKeepsOtherRooms() {
        var request = request(); request.setApplyToSameModules(true);
        var before = json.valueToTree(canvas.getObjects());
        when(office.saveCanvas(any())).thenAnswer(call -> {
            SaveCanvasRequest save = call.getArgument(0); var dto = new CanvasDto(); dto.setObjects(save.getObjects()); dto.setRevision(save.getRevision()); return dto;
        });
        var response = service.select("P", request);
        assertEquals(2, response.getCartItems().size()); assertEquals(8, response.getCanvas().getRevision());
        var after = json.valueToTree(response.getCanvas().getObjects());
        for (int index = 1; index <= 2; index++) {
            assertEquals("FIT", after.get(index).path("productId").asText());
            for (String key : List.of("x","y","w","h","rotation","layoutConfirmed")) assertEquals(before.get(index).get(key), after.get(index).get(key));
            assertEquals("MASTER", after.get(index).path("productSelection").path("product").path("rspuId").asText());
        }
        assertEquals(before.get(3), after.get(3));
        verify(carts, times(2)).insert(any(OfficeCart.class));
    }
    @Test void selectingAgainUpdatesTheBoundLineInsteadOfAddingQuantity() {
        var line = new OfficeCart(); when(carts.selectById(anyString())).thenReturn(line);
        when(office.saveCanvas(any())).thenReturn(canvas);
        service.select("P", request());
        verify(carts).updateById(line); verify(carts, never()).insert(any(OfficeCart.class)); assertEquals(1, line.getQty());
    }
    @Test void matchingPriceUnknownStillShowsFootprintButCannotEnterPlan() {
        var product = sku("FIT", "1200", "600"); product.setPrice(BigDecimal.ZERO);
        when(office.listOfficeProducts()).thenReturn(List.of(product));
        assertEquals(1, service.recommend("P", request()).getCandidates().size());
        assertThrows(BusinessException.class, () -> service.select("P", request())); verifyNoInteractions(carts);
    }
}
