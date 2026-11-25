package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.CategoryDictDto;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.rsdp.officeproduct.importer.office.product.SupplierWorkbookMapping.Field.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProductImportAssistantTest {
    private final LocalProductService products = mock(LocalProductService.class);
    private final RsdpCatalogGateway catalog = mock(RsdpCatalogGateway.class);
    private final QwenVisionClient qwen = mock(QwenVisionClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final LocalProductImportAssistant assistant = new LocalProductImportAssistant(products, catalog, qwen, json);

    @Test
    void limitsFieldSuggestionsToDistinctExistingColumns() throws Exception {
        when(qwen.suggestProductImport(anyString(), any())).thenReturn(json.readTree("""
            {"fields":{"MODEL":2,"NAME":0,"WIDTH_MM":7,"DEPTH_MM":7,"MATERIAL":25,"PICTURE":"2"}}
            """));
        var result = assistant.suggestFields(new LocalProductImportAssistant.HeaderRequest(Map.of(0, "名称", 2, "货号", 7, "长")));
        assertEquals(Map.of(MODEL, 2, NAME, 0), result);
        verify(products).requireWriter();
    }

    @Test
    void classifiesOnlyAllowedCategoriesAndLeavesUnknownOrConflictingRowsBlank() throws Exception {
        when(catalog.listDicts("category")).thenReturn(List.of(category("DS", "桌"), category("CH", "椅"), category("CA", "柜")));
        when(qwen.suggestProductImport(anyString(), any())).thenReturn(json.readTree("""
            {"categories":[{"index":0,"categoryCode":"ds"},{"index":1,"categoryCode":"CA"},
            {"index":2,"categoryCode":"DS"},{"index":2,"categoryCode":"CH"},{"index":30,"categoryCode":"CH"},
            {"index":3,"categoryCode":null}]}
            """));
        var goods = List.of(item("桌"), item("柜"), item("重复结果"), item("未知"));
        assertEquals(Map.of(0, "DS"), assistant.suggestCategories(
            new LocalProductImportAssistant.CategoryRequest(List.of("DS", "CH"), goods)));
        var payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(qwen).suggestProductImport(anyString(), payload.capture());
        assertEquals(json.readTree("{\"DS\":\"桌\",\"CH\":\"椅\"}"), payload.getValue().get("candidates"));
        assertEquals(4, payload.getValue().get("products").size());
    }

    @Test
    void materialSuggestionsRequireMatchingDescriptionEvidence() throws Exception {
        when(qwen.suggestProductImport(anyString(), any())).thenReturn(json.readTree("""
            {"materials":[{"index":0,"terms":["碳晶板","乌金石","胡桃木","实木","竹纤维板"]},
            {"index":1,"terms":["铝合金"]},{"index":1,"terms":["铝合金"]},{"index":99,"terms":["岩板"]}]}
            """));
        var request = new LocalProductImportAssistant.MaterialRequest(List.of(
            "台面采用碳晶板，标配乌金石茶盘。不是实木。柜体采用竹纤维板。", "支架采用铝合金"));
        assertEquals(Map.of(0, "碳晶板、竹纤维板"), assistant.suggestMaterials(request));
        verify(products).requireWriter();
        verifyNoInteractions(catalog);
    }

    @Test
    void validatesWriterAndCandidateDictionaryBeforeCallingQwen() {
        var rows = List.of(item("桌"));
        when(products.requireWriter()).thenThrow(new AccessDeniedException("无录入权限"));
        assertThrows(AccessDeniedException.class, () -> assistant.suggestCategories(
            new LocalProductImportAssistant.CategoryRequest(List.of("DS", "FAKE"), rows)));
        verifyNoInteractions(catalog, qwen);

        reset(products);
        when(catalog.listDicts("category")).thenReturn(List.of(category("DS", "桌")));
        assertThrows(BusinessException.class, () -> assistant.suggestCategories(
            new LocalProductImportAssistant.CategoryRequest(List.of("DS", "FAKE"), rows)));
        verifyNoInteractions(qwen);
    }

    private CategoryDictDto category(String code, String name) {
        var item = new CategoryDictDto();
        item.setDictCode(code);
        item.setDictName(name);
        return item;
    }

    private LocalProductImportAssistant.CategoryItem item(String name) {
        return new LocalProductImportAssistant.CategoryItem(name, null, null);
    }
}
