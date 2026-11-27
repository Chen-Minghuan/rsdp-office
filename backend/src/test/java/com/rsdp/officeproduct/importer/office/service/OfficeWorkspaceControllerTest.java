package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.GlobalExceptionHandler;
import com.rsdp.officeproduct.importer.office.controller.OfficeController;
import com.rsdp.officeproduct.importer.office.dto.CanvasDto;
import com.rsdp.officeproduct.importer.office.dto.CartItemDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OfficeWorkspaceControllerTest {
    @Test
    void legacyProjectProfileRoutesAreRemoved() throws Exception {
        var office = mock(OfficeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, mock(OfficeProjectAccessService.class), mock(OfficeModuleRecommendationService.class), mock(OfficeSpaceReviewService.class))).build();
        mvc.perform(get("/api/v1/office/projects/P")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/office/projects/P").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        verifyNoInteractions(office);
    }

    @Test
    void canvasCanBeSavedWithoutCreatingAProjectProfile() throws Exception {
        var office = mock(OfficeService.class);
        var saved = new CanvasDto(); saved.setProjectId("P"); saved.setRevision(1);
        when(office.saveCanvas(any())).thenReturn(saved);
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, mock(OfficeProjectAccessService.class), mock(OfficeModuleRecommendationService.class), mock(OfficeSpaceReviewService.class))).build();
        mvc.perform(put("/api/v1/office/projects/P/canvas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1,\"mode\":\"overview\",\"objects\":[],\"files\":[],\"blank\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectId").value("P"));
        verify(office).saveCanvas(argThat(request -> "P".equals(request.getProjectId()) && request.getRevision() == 1));
        verifyNoMoreInteractions(office);
    }

    @Test
    void proposalExportRequiresExportProjectPermission() throws Exception {
        var office = mock(OfficeService.class);
        var access = mock(OfficeProjectAccessService.class);
        doThrow(new AccessDeniedException("无权执行该项目操作"))
            .when(access).requireFloorPermission("F1", OfficeProjectPermission.EXPORT_PROJECT);
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, access,
                mock(OfficeModuleRecommendationService.class), mock(OfficeSpaceReviewService.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/v1/office/projects/F1/proposal/export"))
            .andExpect(jsonPath("$.code").value(403));
        // 拒绝时不读购物车、不产出 CSV（后端真实校验，而非仅 UI 禁用按钮）
        verifyNoInteractions(office);
    }

    @Test
    void proposalExportStreamsCsvForAuthorizedMember() throws Exception {
        var office = mock(OfficeService.class);
        var access = mock(OfficeProjectAccessService.class);
        var item = new CartItemDto();
        item.setZoneName("开放办公区");
        item.setProductId("P-1");
        item.setQty(2);
        var product = new OfficeProductDto();
        product.setId("P-1");
        product.setName("工位桌");
        product.setVariant("1200x600");
        product.setPrice(new BigDecimal("500"));
        when(office.listCart("F1")).thenReturn(List.of(item));
        when(office.listOfficeProducts()).thenReturn(List.of(product));
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeController(office, access,
                mock(OfficeModuleRecommendationService.class), mock(OfficeSpaceReviewService.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/v1/office/projects/F1/proposal/export"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv;charset=utf-8"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("空间区域,演示商品ID,商品名称,规格,数量,参考单价,参考小计")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"开放办公区\",\"P-1\",\"工位桌\",\"1200x600\",2,500,1000")));
        // 校验的是 EXPORT_PROJECT 位而非 EDIT
        verify(access).requireFloorPermission("F1", OfficeProjectPermission.EXPORT_PROJECT);
    }
}
