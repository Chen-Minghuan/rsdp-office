package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.SaveCanvasRequest;
import com.rsdp.officeproduct.importer.office.entity.*;
import com.rsdp.officeproduct.importer.office.mapper.*;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficeProductCatalogTest {
    /** 排版选品目录已切换为主项目数据源：服务层只负责按当前用户开关解析 viewMode 并委托网关。 */
    @Test void listOfficeProductsDelegatesToMainCatalogWithResolvedViewMode() {
        var gateway = mock(RsdpCatalogGateway.class);
        var sharedCatalog = mock(SharedCatalogService.class);
        var service = new OfficeService(mock(OfficeWorkspaceLockMapper.class), mock(OfficeProjectService.class), mock(OfficeProjectAccessService.class), mock(OfficeCanvasMapper.class), mock(OfficeCartMapper.class), mock(OfficeAgentMessageMapper.class), gateway, sharedCatalog, new ObjectMapper());
        when(sharedCatalog.resolveViewMode()).thenReturn("own");
        var dto = new OfficeProductDto();
        dto.setRspuId("MASTER");
        dto.setVariantId("VARIANT");
        when(gateway.listOfficeProducts("own")).thenReturn(List.of(dto));

        var result = service.listOfficeProducts();

        assertEquals(1, result.size());
        assertEquals("MASTER", result.get(0).getRspuId());
        assertEquals("VARIANT", result.get(0).getVariantId());
        verify(gateway).listOfficeProducts("own");
    }
    @Test void rejectsMultipleDrawingFilesBeforeWritingCanvas() {
        var locks = mock(OfficeWorkspaceLockMapper.class); var canvases = mock(OfficeCanvasMapper.class);
        var service = new OfficeService(locks, mock(OfficeProjectService.class), mock(OfficeProjectAccessService.class), canvases, mock(OfficeCartMapper.class), mock(OfficeAgentMessageMapper.class), mock(RsdpCatalogGateway.class), mock(SharedCatalogService.class), new ObjectMapper());
        var request = new SaveCanvasRequest(); request.setProjectId("P"); request.setMode("overview");
        request.setFiles(List.of(Map.of("name", "first.png"), Map.of("name", "second.pdf")));
        assertEquals("一次只能上传一张图纸，请移除多余文件", assertThrows(BusinessException.class, () -> service.saveCanvas(request)).getMessage());
        verifyNoInteractions(locks, canvases);
    }
    @Test void staleDebouncedCanvasWritesCannotOverwriteNewerModuleSelections() {
        var projects = mock(OfficeWorkspaceLockMapper.class); var canvases = mock(OfficeCanvasMapper.class);
        var service = new OfficeService(projects, mock(OfficeProjectService.class), mock(OfficeProjectAccessService.class), canvases, mock(OfficeCartMapper.class), mock(OfficeAgentMessageMapper.class), mock(RsdpCatalogGateway.class), mock(SharedCatalogService.class), new ObjectMapper());
        var latest = new OfficeCanvas(); latest.setProjectId("P"); latest.setRevision(10); latest.setMode("overview"); latest.setObjectsJson("[]"); latest.setFilesJson("[]");
        when(canvases.selectList(any())).thenReturn(List.of(latest));
        var request = new SaveCanvasRequest(); request.setProjectId("P"); request.setRevision(9); request.setMode("overview"); request.setObjects(List.of());
        assertThrows(BusinessException.class, () -> service.saveCanvas(request));
        verify(projects).lockWorkspace("P"); verify(canvases, never()).insert(any(OfficeCanvas.class));
    }
}
