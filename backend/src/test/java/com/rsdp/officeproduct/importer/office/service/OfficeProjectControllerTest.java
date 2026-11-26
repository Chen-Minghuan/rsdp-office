package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.office.controller.OfficeProjectController;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectBudgetDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficeProjectControllerTest {
    @Test void projectStatusEndpointReturnsThePersistedStage() throws Exception {
        var service = mock(OfficeProjectService.class);
        when(service.updateStatus("P1", "DELIVERING")).thenReturn("DELIVERING");
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeProjectController(service)).build();
        mvc.perform(patch("/api/v1/office/projects/P1/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DELIVERING\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DELIVERING"));
    }
    private final OfficeProjectService projects = mock(OfficeProjectService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new OfficeProjectController(projects)).build();

    @Test void exposesProjectTreeCreationFloorCreationAndRequirementsRoutes() throws Exception {
        var floor = new OfficeFloorDto();
        floor.setFloorId("OFLR-1");
        floor.setProjectId("OPRJ-1");
        floor.setName("一层");
        var project = new OfficeProjectDto();
        project.setProjectId("OPRJ-1");
        project.setName("项目");
        project.setRequirementsRevision(1);
        project.setFloors(List.of(floor));
        when(projects.listProjects()).thenReturn(List.of(project));
        when(projects.listProjects(false)).thenReturn(List.of(project));
        when(projects.listProjectBudgets()).thenReturn(Map.of("OPRJ-1", new OfficeProjectBudgetDto(BigDecimal.TEN, "COMPLETE")));
        when(projects.createProject(any())).thenReturn(project);
        when(projects.addFloor(eq("OPRJ-1"), any())).thenReturn(floor);
        when(projects.updateRequirements(eq("OPRJ-1"), any())).thenReturn(project);
        when(projects.updateProject(eq("OPRJ-1"), any())).thenReturn(project);
        when(projects.updateFloor(eq("OPRJ-1"), eq("OFLR-1"), any())).thenReturn(floor);

        mvc.perform(get("/api/v1/office/projects")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].floors[0].floorId").value("OFLR-1"));
        mvc.perform(get("/api/v1/office/projects").param("includeBudget", "false"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].budgetTotal").doesNotExist());
        mvc.perform(get("/api/v1/office/projects/budgets")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data['OPRJ-1'].budgetTotal").value(10));
        mvc.perform(post("/api/v1/office/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"项目\",\"customerRequirements\":\"需求\",\"floors\":[{\"name\":\"一层\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value("OPRJ-1"));
        mvc.perform(post("/api/v1/office/projects/OPRJ-1/floors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"二层\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.floorId").value("OFLR-1"));
        mvc.perform(patch("/api/v1/office/projects/OPRJ-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"总部\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value("OPRJ-1"));
        mvc.perform(patch("/api/v1/office/projects/OPRJ-1/floors/OFLR-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"首层\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.floorId").value("OFLR-1"));
        mvc.perform(patch("/api/v1/office/projects/OPRJ-1/requirements").contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerRequirements\":\"更新需求\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.requirementsRevision").value(1));
        verify(projects).createProject(argThat(request -> request.getFloors().size() == 1));
        verify(projects).addFloor(eq("OPRJ-1"), argThat(request -> "二层".equals(request.getName())));
        verify(projects).updateRequirements(eq("OPRJ-1"), argThat(request -> "更新需求".equals(request.getCustomerRequirements())));
        verify(projects).updateProject(eq("OPRJ-1"), argThat(request -> "总部".equals(request.getName())));
        verify(projects).updateFloor(eq("OPRJ-1"), eq("OFLR-1"), argThat(request -> "首层".equals(request.getName())));
    }
}
