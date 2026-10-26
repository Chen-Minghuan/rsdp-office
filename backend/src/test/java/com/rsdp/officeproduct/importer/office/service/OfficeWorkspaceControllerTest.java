package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.controller.OfficeController;
import com.rsdp.officeproduct.importer.office.dto.CanvasDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OfficeWorkspaceControllerTest {
    @Test
    void legacyProjectProfileRoutesAreRemoved() throws Exception {
        var office = mock(OfficeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, mock(OfficeModuleRecommendationService.class))).build();
        mvc.perform(get("/api/v1/office/projects/P")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/office/projects/P").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        verifyNoInteractions(office);
    }

    @Test
    void canvasCanBeSavedWithoutCreatingAProjectProfile() throws Exception {
        var office = mock(OfficeService.class);
        var saved = new CanvasDto(); saved.setProjectId("P"); saved.setRevision(1);
        when(office.saveCanvas(any())).thenReturn(saved);
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, mock(OfficeModuleRecommendationService.class))).build();
        mvc.perform(put("/api/v1/office/projects/P/canvas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1,\"mode\":\"overview\",\"objects\":[],\"files\":[],\"blank\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value("P"));
        verify(office).saveCanvas(argThat(request -> "P".equals(request.getProjectId()) && request.getRevision() == 1));
        verifyNoMoreInteractions(office);
    }
}
