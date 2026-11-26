package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.GlobalExceptionHandler;
import com.rsdp.officeproduct.importer.dto.response.CatalogProductPageDto;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductImportControllerTest {

    @Test
    void bindsRepeatedLevels() throws Exception {
        var productQueryService = mock(ProductQueryService.class);
        when(productQueryService.pageProducts(any())).thenReturn(CatalogProductPageDto.empty(1, 20));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductImportController(productQueryService, mock(OfficeCatalogAccess.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/products/page").param("levels", "S", "A"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(productQueryService).pageProducts(argThat(request -> request.getLevels().equals(List.of("S", "A"))));
    }
}
