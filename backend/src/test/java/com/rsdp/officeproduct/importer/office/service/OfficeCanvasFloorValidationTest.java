package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import com.rsdp.officeproduct.importer.mapper.RspuVariantMapper;
import com.rsdp.officeproduct.importer.office.dto.SaveCanvasRequest;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAgentMessageMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class OfficeCanvasFloorValidationTest {
    private final OfficeWorkspaceLockMapper locks = mock(OfficeWorkspaceLockMapper.class);
    private final OfficeProjectService projects = mock(OfficeProjectService.class);
    private final OfficeCanvasMapper canvases = mock(OfficeCanvasMapper.class);
    private final OfficeService service = new OfficeService(locks, projects, canvases, mock(OfficeCartMapper.class),
        mock(OfficeAgentMessageMapper.class), mock(RspuMasterMapper.class), mock(RspuVariantMapper.class), new ObjectMapper());

    @Test void blankScopeContinuesToUseValidatedLegacyDefaultFloor() {
        var request = new SaveCanvasRequest();
        request.setMode("overview");
        request.setObjects(List.of());
        request.setFiles(List.of());
        service.saveCanvas(request);
        verify(projects).requireFloor("OFFICE-PROJECT-001");
        verify(locks).lockWorkspace("OFFICE-PROJECT-001");
        verify(canvases).insert(any(com.rsdp.officeproduct.importer.office.entity.OfficeCanvas.class));
    }

    @Test void unknownScopeIsRejectedBeforeCanvasWrite() {
        var request = new SaveCanvasRequest();
        request.setProjectId("UNKNOWN-WORKSPACE");
        request.setMode("overview");
        doThrow(new BusinessException("楼层不存在，请先创建项目和楼层")).when(projects).requireFloor("UNKNOWN-WORKSPACE");
        assertThrows(BusinessException.class, () -> service.saveCanvas(request));
        verifyNoInteractions(locks);
        verify(canvases, never()).insert(any(com.rsdp.officeproduct.importer.office.entity.OfficeCanvas.class));
    }
}
