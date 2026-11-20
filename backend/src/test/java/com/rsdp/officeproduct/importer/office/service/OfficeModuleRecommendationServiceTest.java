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
        service = new OfficeModuleRecommendationService(office, mock(OfficeWorkspaceLockMapper.class), carts, json);
        canvas = new CanvasDto(); canvas.setRevision(7); canvas.setMode("design"); canvas.setDesignZoneId("Z"); canvas.setFiles(List.of());
        canvas.setObjects(json.readValue("""
            [{"id":"Z","kind":"zone","reviewUnit":"mm","name":"办公区","polygon":[[0,0],[6000,0],[6000,4000],[0,4000]]},
             {"id":"A","kind":"furniture","zoneId":"Z","w":1200,"h":600,"x":300,"y":300,"rotation":0,"furnitureTemplateId":"desk-straight","layoutConfirmed":true},
             {"id":"B","kind":"furniture","zoneId":"Z","w":1200,"h":600,"x":2000,"y":300,"rotation":90,"furnitureTemplateId":"desk-straight","layoutConfirmed":true},
             {"id":"OTHER","kind":"furniture","zoneId":"OTHER-ZONE","w":1200,"h":600,"productId":"KEEP","layoutConfirmed":true}]
            """, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {}));
        when(office.getCanvas("P")).thenReturn(canvas);
        when(office.listOfficeProducts()).thenReturn(List.of(sku("FIT", "1200", "600"), sku("SMALL", "700", "300"), sku("TOO-WIDE", "1200.01", "600"), new OfficeProductDto()));
    }
    private OfficeProductDto sku(String id, String width, String depth) {
        var p = new OfficeProductDto(); p.setId(id); p.setVariantId(id); p.setRspuId("MASTER"); p.setName("办公桌"); p.setWidthMm(new BigDecimal(width)); p.setDepthMm(new BigDecimal(depth)); p.setPrice(BigDecimal.TEN); return p;
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

    @Test void ranksCoffeeTablesBeforeSimilarFurnitureAndTagsAndRejectsIncompatibleFurniture() {
        var module = (Map<String,Object>) canvas.getObjects().get(1);
        module.put("furnitureTemplateId", "UMOD-COFFEE");
        module.put("label", "客区定制款");
        module.put("moduleSnapshot", Map.of("glyphTemplateId", "coffee-round"));
        module.put("w", 462.65);
        module.put("h", 530.45);

        var declared = sku("CLASSIFIED", "430", "465");
        declared.setName("WG-1");
        declared.setCategoryPath(List.of("家具", "桌类", "茶几"));
        var named = sku("NAMED", "400", "400");
        named.setName("小圆茶几");
        named.setCategoryPath(List.of("办公家具"));
        var edge = sku("EDGE", "460", "525");
        edge.setName("沙发边几");
        var tagged = sku("TAGGED", "460", "525");
        tagged.setName("WG-2");
        tagged.setFurnitureTags(List.of("双层茶几", "圆形台面"));
        var chair = sku("CHAIR", "530", "450");
        chair.setName("明月主椅 WG-25332");
        chair.setCategoryPath(List.of("家具", "座椅", "休闲椅", "单椅"));
        chair.setFurnitureTags(List.of("搭配茶几", "FS-板面壳体靠背"));
        var oversize = sku("OVERSIZE", "462.66", "500");
        oversize.setName("圆形茶几");
        var unknown = sku("UNKNOWN", "462.65", "530.45");
        unknown.setName("WG-3");
        when(office.listOfficeProducts()).thenReturn(List.of(chair, tagged, oversize, unknown, edge, named, declared));
        var request = request();
        request.setAllowRotation(true);

        var result = service.recommend("P", request);
        assertEquals("茶几", result.getTargetCategory());
        assertEquals(List.of("CLASSIFIED", "NAMED", "EDGE", "TAGGED"), result.getCandidates().stream().map(c -> c.getProduct().getId()).toList());
        assertTrue(result.getCandidates().get(2).getFitRatio().compareTo(result.getCandidates().get(0).getFitRatio()) > 0);
        assertTrue(result.getCandidates().get(3).getMatchReason().contains("六维标签"));

        request.setProductId("CHAIR");
        assertThrows(BusinessException.class, () -> service.select("P", request));
        verifyNoInteractions(carts);
    }

    @Test void legacyModuleUsesItsLabelAndUnknownTypeDoesNotUseSizeAlone() {
        var module = (Map<String,Object>) canvas.getObjects().get(1);
        module.remove("furnitureTemplateId");
        module.put("label", "原图补录茶几");
        var variant = sku("V", "700", "500");
        variant.setName("WG");
        variant.setVariantName("方茶几");
        when(office.listOfficeProducts()).thenReturn(List.of(variant));
        assertEquals("V", service.recommend("P", request()).getCandidates().get(0).getProduct().getId());
        module.put("label", "其他家具");
        var result = service.recommend("P", request());
        assertNull(result.getTargetCategory());
        assertTrue(result.getCandidates().isEmpty());
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
    @Test void rejectsSelectionWhenMainCatalogNoLongerOffersTheCachedVariant() {
        assertThrows(BusinessException.class, () -> service.select("P", request()));
        verifyNoInteractions(carts);
        verify(office).currentProductVariant("MASTER", "FIT");
    }

    @Test void bindsSameModulesWithoutMovingOrResizingAndKeepsOtherRooms() {
        when(office.currentProductVariant("MASTER", "FIT")).thenReturn(sku("FIT", "1200", "600"));
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
        when(office.currentProductVariant("MASTER", "FIT")).thenReturn(sku("FIT", "1200", "600"));
        var line = new OfficeCart(); when(carts.selectById(anyString())).thenReturn(line);
        when(office.saveCanvas(any())).thenReturn(canvas);
        service.select("P", request());
        verify(carts).updateById(line); verify(carts, never()).insert(any(OfficeCart.class)); assertEquals(1, line.getQty());
    }
    @Test void matchingPriceUnknownStillShowsFootprintButCannotEnterPlan() {
        var product = sku("FIT", "1200", "600"); product.setPrice(BigDecimal.ZERO);
        when(office.listOfficeProducts()).thenReturn(List.of(product));
        when(office.currentProductVariant("MASTER", "FIT")).thenReturn(product);
        assertEquals(1, service.recommend("P", request()).getCandidates().size());
        assertThrows(BusinessException.class, () -> service.select("P", request())); verifyNoInteractions(carts);
    }
}
