package com.rsdp.officeproduct.importer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.GlobalExceptionHandler;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import com.rsdp.officeproduct.importer.service.RsdpClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficeProductBatchControllerTest {
    @Test
    void forwardsOnlyHistoryReadsWithTheCurrentWorkspaceAndActor() throws Exception {
        var access = mock(OfficeCatalogAccess.class);
        var rsdp = mock(RsdpClient.class);
        when(access.requireHistoryReader()).thenReturn(new OfficeCatalogAccess.Actor("WS-1", "USER-1", "Member", "B001"));
        var path = "/api/v1/products/office/batches/BATCH-1/status"
            + "?workspaceId=WS-1&factoryCode=B001&actorId=USER-1&actorName=Member";
        when(rsdp.get(path)).thenReturn(new ObjectMapper().readTree("{\"status\":\"done\",\"rows\":[]}"));
        var mvc = MockMvcBuilders.standaloneSetup(
                new OfficeProductBatchController(access, rsdp),
                new ProductImportController(mock(ProductQueryService.class), access))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/products/import-batches/BATCH-1/status"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("done"));
        mvc.perform(post("/api/v1/products/import-batches/BATCH-1/confirm"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/products/manual"))
            .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(405));
        verify(rsdp).get(path);
        verifyNoMoreInteractions(rsdp);
    }
}
