package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAgentMessageMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficeProposalCartValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsCartQuantityGreaterThanSavedFurnitureCount() throws Exception {
        OfficeCart cartLine = cartLine("LINE-1", "ZONE-1", "PRODUCT-1", 4);
        OfficeService service = serviceWithCanvasAndCart(canvasWithFurniture(3), List.of(cartLine));

        BusinessException error = assertThrows(BusinessException.class, () -> service.checkProposalCatalog("FLOOR-1"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("4"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("3"));
    }

    @Test
    void acceptsMatchingFurnitureAndCartQuantitiesWithValidSelections() throws Exception {
        OfficeCart cartLine = cartLine("LINE-1", "ZONE-1", "PRODUCT-1", 3);
        OfficeService service = serviceWithCanvasAndCart(canvasWithFurniture(3), List.of(cartLine));

        assertDoesNotThrow(() -> service.checkProposalCatalog("FLOOR-1"));
    }

    @Test
    void rejectsFurnitureWithoutMatchingCartLineAssociation() throws Exception {
        OfficeCart cartLine = cartLine("LINE-1", "ZONE-1", "PRODUCT-1", 1);
        String objects = canvasWithFurniture(1).replace("LINE-1", "OTHER-LINE");
        OfficeService service = serviceWithCanvasAndCart(objects, List.of(cartLine));

        assertThrows(BusinessException.class, () -> service.checkProposalCatalog("FLOOR-1"));
    }

    private OfficeService serviceWithCanvasAndCart(String objectsJson, List<OfficeCart> cart) {
        OfficeCanvasMapper canvases = mock(OfficeCanvasMapper.class);
        OfficeCartMapper carts = mock(OfficeCartMapper.class);
        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId("FLOOR-1");
        canvas.setRevision(1);
        canvas.setMode("overview");
        canvas.setObjectsJson(objectsJson);
        canvas.setFilesJson("[]");
        when(canvases.selectList(any())).thenReturn(List.of(canvas));
        when(carts.selectList(any())).thenReturn(cart);
        return new OfficeService(mock(OfficeWorkspaceLockMapper.class), mock(OfficeProjectService.class),
            mock(OfficeProjectAccessService.class), canvases, carts, mock(OfficeAgentMessageMapper.class),
            mock(RsdpCatalogGateway.class), mock(SharedCatalogService.class), objectMapper);
    }

    private OfficeCart cartLine(String id, String zoneId, String productId, int quantity) {
        OfficeCart cart = new OfficeCart();
        cart.setLineId(id);
        cart.setProjectId("FLOOR-1");
        cart.setZoneId(zoneId);
        cart.setZoneName("测试空间");
        cart.setProductId(productId);
        cart.setQty(quantity);
        return cart;
    }

    private String canvasWithFurniture(int quantity) throws Exception {
        StringBuilder objects = new StringBuilder("[{\"kind\":\"zone\",\"id\":\"ZONE-1\",\"name\":\"测试空间\"}");
        for (int i = 0; i < quantity; i++) {
            objects.append(",{")
                .append("\"kind\":\"furniture\",")
                .append("\"id\":\"FURNITURE-").append(i).append("\",")
                .append("\"zoneId\":\"ZONE-1\",")
                .append("\"productId\":\"PRODUCT-1\",")
                .append("\"productSelection\":{\"cartLineId\":\"LINE-1\",\"product\":{\"id\":\"PRODUCT-1\"}}}");
        }
        objects.append(']');
        return objectMapper.readTree(objects.toString()).toString();
    }
}
