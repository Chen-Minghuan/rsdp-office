package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationLineDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationSpaceDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationLineMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目报价试算：口径换算、清单优先、缺出厂价单列与参数校验。
 */
class ProjectQuotationServiceTest {

    private static final String PROJECT_ID = "PROJ-1";
    private static final String FLOOR_ID = "FLOOR-1";

    private final ObjectMapper json = new ObjectMapper();
    private final OfficeProjectAccessService accessService = mock(OfficeProjectAccessService.class);
    private final OfficeDesignProjectMapper projectMapper = mock(OfficeDesignProjectMapper.class);
    private final OfficeDesignFloorMapper floorMapper = mock(OfficeDesignFloorMapper.class);
    private final OfficeCanvasMapper canvasMapper = mock(OfficeCanvasMapper.class);
    private final OfficeCartMapper cartMapper = mock(OfficeCartMapper.class);
    private final OfficeQuotationMapper quotationMapper = mock(OfficeQuotationMapper.class);
    private final OfficeQuotationLineMapper quotationLineMapper = mock(OfficeQuotationLineMapper.class);
    private final RsdpCatalogGateway catalogGateway = mock(RsdpCatalogGateway.class);
    private final OfficeAuditService auditService = mock(OfficeAuditService.class);

    @Test
    void coefficientQuotesCartLinesAndUnassignedFurniture() {
        stubScenario(true);

        ProjectQuotationPreviewDto preview = service().preview(PROJECT_ID, "COEFFICIENT", new BigDecimal("1.25"));

        assertEquals(1, preview.getFloors().size());
        assertEquals(2, preview.getFloors().get(0).getSpaces().size());
        QuotationSpaceDto cartSpace = preview.getFloors().get(0).getSpaces().get(0);
        assertEquals("开放办公区", cartSpace.getZoneName());
        QuotationLineDto deskLine = cartSpace.getLines().get(0);
        assertAmount("194", deskLine.getFactoryPrice());
        assertAmount("242.50", deskLine.getUnitPrice());
        assertAmount("485.00", deskLine.getSubtotal());
        assertNull(deskLine.getIssue());

        QuotationSpaceDto furnitureSpace = preview.getFloors().get(0).getSpaces().get(1);
        assertEquals("会议室", furnitureSpace.getZoneName());
        QuotationLineDto chairLine = furnitureSpace.getLines().get(0);
        assertEquals(2, chairLine.getQty());
        assertAmount("125.00", chairLine.getUnitPrice());

        assertEquals(2, preview.getTotals().getLineCount());
        assertEquals(0, preview.getTotals().getMissingFactoryPriceLines());
        assertAmount("588", preview.getTotals().getFactoryTotal());
        assertAmount("735", preview.getTotals().getDealTotal());
    }

    @Test
    void marginModeDividesByOneMinusMargin() {
        stubScenario(true);

        ProjectQuotationPreviewDto preview = service().preview(PROJECT_ID, "MARGIN", new BigDecimal("0.35"));

        assertAmount("298.46", preview.getFloors().get(0).getSpaces().get(0).getLines().get(0).getUnitPrice());
        assertAmount("596.92", preview.getFloors().get(0).getSpaces().get(0).getLines().get(0).getSubtotal());
    }

    @Test
    void lineWithoutFactoryPriceIsReportedAndExcludedFromTotals() {
        stubScenario(false);

        ProjectQuotationPreviewDto preview = service().preview(PROJECT_ID, "COEFFICIENT", new BigDecimal("1.20"));

        QuotationLineDto deskLine = preview.getFloors().get(0).getSpaces().get(0).getLines().get(0);
        assertNotNull(deskLine.getIssue());
        assertNull(deskLine.getUnitPrice());
        assertNull(deskLine.getSubtotal());
        assertEquals(2, preview.getTotals().getLineCount());
        assertEquals(2, preview.getTotals().getMissingFactoryPriceLines());
        assertAmount("0", preview.getTotals().getDealTotal());
    }

    @Test
    void rejectsOutOfRangeMargin() {
        stubScenario(true);

        assertThrows(BusinessException.class, () -> service().preview(PROJECT_ID, "MARGIN", BigDecimal.ONE));
        assertThrows(BusinessException.class, () -> service().preview(PROJECT_ID, "MARGIN", new BigDecimal("-0.1")));
    }

    @Test
    void confirmStoresServerComputedTotalsAndSupersedesPreviousVersion() {
        stubScenario(true);
        when(quotationMapper.selectOne(any())).thenReturn(null);

        ProjectQuotationRecordDto stored = service().confirm(PROJECT_ID, request("COEFFICIENT", "1.25",
            "100", "50", List.of(confirmLine("V-DESK", 2, "300"))));

        ArgumentCaptor<OfficeQuotation> header = ArgumentCaptor.forClass(OfficeQuotation.class);
        verify(quotationMapper).insert(header.capture());
        assertEquals("CONFIRMED", header.getValue().getStatus());
        assertEquals(1, header.getValue().getRevision());
        assertEquals("报价测试项目", header.getValue().getProjectName());
        assertAmount("600", header.getValue().getGoodsTotal());
        assertAmount("100", header.getValue().getServiceFee());
        assertAmount("750", header.getValue().getTotalAmount());
        assertEquals(1, header.getValue().getLineCount());
        assertNotNull(header.getValue().getSnapshotJson());
        verify(quotationMapper).update(any(OfficeQuotation.class), any());
        verify(quotationLineMapper).insert(any(com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine.class));
        assertAmount("750", stored.getTotalAmount());
        assertEquals("CONFIRMED", stored.getStatus());
    }

    @Test
    void confirmRejectsEmptyLinesAndNegativeFees() {
        stubScenario(true);
        assertThrows(BusinessException.class, () -> service().confirm(PROJECT_ID,
            request("COEFFICIENT", "1.2", "0", "0", List.of())));
        assertThrows(BusinessException.class, () -> service().confirm(PROJECT_ID,
            request("COEFFICIENT", "1.2", "-1", "0", List.of(confirmLine("V-DESK", 1, "100")))));
        assertThrows(BusinessException.class, () -> service().confirm(PROJECT_ID,
            request("MARGIN", "1.5", "0", "0", List.of(confirmLine("V-DESK", 1, "100")))));
        ConfirmProjectQuotationRequest blankFeeName = request("COEFFICIENT", "1.2", "0", "0",
            List.of(confirmLine("V-DESK", 1, "100")));
        blankFeeName.getFees().add(fee(" ", "1"));
        assertThrows(BusinessException.class, () -> service().confirm(PROJECT_ID, blankFeeName));
    }

    @Test
    void confirmKeepsMissingFactoryPriceLineWithIssueAndNoAmount() {
        stubScenario(false);
        when(quotationMapper.selectOne(any())).thenReturn(null);

        service().confirm(PROJECT_ID, request("COEFFICIENT", "1.2", "0", "0",
            List.of(confirmLine("V-DESK", 1, "300"))));

        ArgumentCaptor<OfficeQuotation> header = ArgumentCaptor.forClass(OfficeQuotation.class);
        verify(quotationMapper).insert(header.capture());
        assertEquals(1, header.getValue().getMissingPriceLines());
        assertAmount("0", header.getValue().getTotalAmount());
    }

    @Test
    void confirmStoresMultipleFeesAndLatestRestoresThem() {
        stubScenario(true);
        when(quotationMapper.selectOne(any())).thenReturn(null);
        ConfirmProjectQuotationRequest request = request("COEFFICIENT", "1.25", "100", "50",
            List.of(confirmLine("V-DESK", 2, "300")));
        request.getFees().add(fee("额外服务费", "20.125"));
        request.getFees().add(fee("加急费", "30"));
        ProjectQuotationService service = service();

        ProjectQuotationRecordDto stored = service.confirm(PROJECT_ID, request);

        assertAmount("800.13", stored.getTotalAmount());
        assertEquals(4, stored.getFees().size());
        assertEquals("额外服务费", stored.getFees().get(2).getName());
        assertAmount("20.13", stored.getFees().get(2).getAmount());
        assertAmount("100", stored.getServiceFee());
        assertAmount("50", stored.getLogisticsFee());
        ArgumentCaptor<OfficeQuotation> header = ArgumentCaptor.forClass(OfficeQuotation.class);
        verify(quotationMapper).insert(header.capture());
        when(quotationMapper.selectOne(any())).thenReturn(header.getValue());
        when(quotationLineMapper.selectList(any())).thenReturn(List.of());
        assertEquals(4, service.latest(PROJECT_ID).getFees().size());
    }

    @Test
    void previewConfirmationUsesSameTotalsWithoutPersistence() {
        stubScenario(true);
        ConfirmProjectQuotationRequest request = request("COEFFICIENT", "1.25", "100", "50",
            List.of(confirmLine("V-DESK", 2, "300")));

        ProjectQuotationRecordDto draft = service().previewConfirmation(PROJECT_ID, request);

        assertEquals("DRAFT", draft.getStatus());
        assertNull(draft.getQuotationId());
        assertAmount("750", draft.getTotalAmount());
        assertEquals(2, draft.getFees().size());
        verify(quotationMapper, never()).insert(any(OfficeQuotation.class));
        verifyNoInteractions(auditService);
    }

    private ConfirmProjectQuotationRequest request(String mode, String value, String serviceFee, String logisticsFee,
                                                    List<ConfirmProjectQuotationRequest.Line> lines) {
        ConfirmProjectQuotationRequest request = new ConfirmProjectQuotationRequest();
        request.setPricingMode(mode);
        request.setPricingValue(new BigDecimal(value));
        request.setFees(new ArrayList<>(List.of(fee("服务费", serviceFee), fee("物流搬运安装费", logisticsFee))));
        request.setLines(lines);
        return request;
    }

    private ConfirmProjectQuotationRequest.Fee fee(String name, String amount) {
        ConfirmProjectQuotationRequest.Fee fee = new ConfirmProjectQuotationRequest.Fee();
        fee.setName(name);
        fee.setAmount(new BigDecimal(amount));
        return fee;
    }

    private ConfirmProjectQuotationRequest.Line confirmLine(String productId, int qty, String unitPrice) {
        ConfirmProjectQuotationRequest.Line line = new ConfirmProjectQuotationRequest.Line();
        line.setFloorId(FLOOR_ID);
        line.setZoneId("Z1");
        line.setZoneName("开放办公区");
        line.setProductId(productId);
        line.setQty(qty);
        line.setUnitPrice(new BigDecimal(unitPrice));
        return line;
    }

    private ProjectQuotationService service() {
        return new ProjectQuotationService(accessService, projectMapper, floorMapper, canvasMapper, cartMapper,
            quotationMapper, quotationLineMapper, catalogGateway, auditService, json);
    }

    /** 一间楼层：清单里 2 张办公桌（空间 Z1），画布里 2 把会议椅（空间 Z2）。 */
    private void stubScenario(boolean withFactoryPrice) {
        OfficeDesignProject project = new OfficeDesignProject();
        project.setProjectId(PROJECT_ID);
        project.setName("报价测试项目");
        project.setWorkspaceId("WS-1");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        OfficeDesignFloor floor = new OfficeDesignFloor();
        floor.setFloorId(FLOOR_ID);
        floor.setProjectId(PROJECT_ID);
        floor.setName("1F");
        when(floorMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(floor));

        OfficeCart cartLine = new OfficeCart();
        cartLine.setLineId("LINE-1");
        cartLine.setProjectId(FLOOR_ID);
        cartLine.setZoneId("Z1");
        cartLine.setZoneName("开放办公区");
        cartLine.setProductId("V-DESK");
        cartLine.setQty(2);
        when(cartMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(cartLine));

        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId(FLOOR_ID);
        canvas.setObjectsJson("""
            [{"kind":"zone","id":"Z1","name":"开放办公区"},
             {"kind":"zone","id":"Z2","name":"会议室"},
             {"kind":"furniture","id":"F-1","productId":"V-CHAIR","zoneId":"Z2"},
             {"kind":"furniture","id":"F-2","productId":"V-CHAIR","zoneId":"Z2"}]
            """);
        when(canvasMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(canvas);

        when(catalogGateway.listOfficeProducts(anyString())).thenReturn(List.of(
            product("V-DESK", "RSPU-DESK", "办公桌", "标准款"),
            product("V-CHAIR", "RSPU-CHAIR", "会议椅", "标准款")));
        when(catalogGateway.getProductDetail("RSPU-DESK")).thenReturn(detail("V-DESK", withFactoryPrice ? "194" : null));
        when(catalogGateway.getProductDetail("RSPU-CHAIR")).thenReturn(detail("V-CHAIR", withFactoryPrice ? "100" : null));
    }

    private OfficeProductDto product(String variantId, String rspuId, String name, String variant) {
        OfficeProductDto product = new OfficeProductDto();
        product.setId(variantId);
        product.setVariantId(variantId);
        product.setRspuId(rspuId);
        product.setName(name);
        product.setVariant(variant);
        product.setPrice(new BigDecimal("588"));
        return product;
    }

    private ProductDetailDto detail(String variantId, String factoryPrice) {
        ProductDetailDto detail = new ProductDetailDto();
        ProductDetailDto.Variant variant = new ProductDetailDto.Variant();
        variant.setVariantId(variantId);
        variant.setFactoryPrice(factoryPrice == null ? null : new BigDecimal(factoryPrice));
        detail.getVariants().add(variant);
        return detail;
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + "，实际 " + actual);
    }
}
