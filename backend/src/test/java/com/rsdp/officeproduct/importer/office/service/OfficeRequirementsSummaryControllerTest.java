package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.controller.OfficeRequirementsSummaryController;
import com.rsdp.officeproduct.importer.office.dto.OfficeRequirementsSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OfficeRequirementsSummaryControllerTest {
    @Test
    void returnsStructuredSummaryForRequestedRevision() throws Exception {
        OfficeRequirementsSummaryService service = mock(OfficeRequirementsSummaryService.class);
        OfficeRequirementsSummaryDto summary = new OfficeRequirementsSummaryDto();
        summary.setKeyPoints(List.of("开放办公区计划容纳60人。"));
        summary.setSourceRevision(3);
        summary.setCacheHit(true);
        when(service.ensureSummary("OPRJ-1", 3)).thenReturn(summary);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OfficeRequirementsSummaryController(service)).build();

        mvc.perform(post("/api/v1/office/projects/OPRJ-1/requirements/summary")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requirementsRevision\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.keyPoints[0]").value("开放办公区计划容纳60人。"))
            .andExpect(jsonPath("$.data.sourceRevision").value(3))
            .andExpect(jsonPath("$.data.cacheHit").value(true));

        verify(service).ensureSummary("OPRJ-1", 3);
    }
}
