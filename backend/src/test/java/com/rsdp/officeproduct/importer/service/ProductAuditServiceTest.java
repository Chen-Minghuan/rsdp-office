package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.BatchAuditResult;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductAuditServiceTest {

    @Test
    void approveBatchOnlyTouchesPendingProductsAndReportsSkipped() {
        RspuMasterMapper mapper = mock(RspuMasterMapper.class);
        ProductAuditService service = new ProductAuditService(mapper);
        when(mapper.update(isNull(), any())).thenAnswer(call -> {
            UpdateWrapper<?> wrapper = call.getArgument(1);
            assertTrue(wrapper.getSqlSegment().contains("audit_status"));
            assertTrue(wrapper.getSqlSegment().contains("rspu_id"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("pending"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("approved"));
            return 2;
        });
        BatchAuditResult result = service.approveBatch(List.of("A", "B", "C", "A"));
        assertEquals(2, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
        verify(mapper, times(1)).update(isNull(), any());
    }

    @Test
    void rejectBatchKeepsRemarkAndReportsSkipped() {
        RspuMasterMapper mapper = mock(RspuMasterMapper.class);
        ProductAuditService service = new ProductAuditService(mapper);
        when(mapper.update(isNull(), any())).thenAnswer(call -> {
            UpdateWrapper<?> wrapper = call.getArgument(1);
            assertTrue(wrapper.getParamNameValuePairs().containsValue("rejected"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("信息缺失"));
            return 1;
        });
        BatchAuditResult result = service.rejectBatch(List.of("A", "B"), "信息缺失");
        assertEquals(1, result.getProcessedCount());
        assertEquals(1, result.getSkippedCount());
    }

    @Test
    void approveAllPendingUpdatesEveryPendingProduct() {
        RspuMasterMapper mapper = mock(RspuMasterMapper.class);
        ProductAuditService service = new ProductAuditService(mapper);
        when(mapper.update(isNull(), any())).thenAnswer(call -> {
            UpdateWrapper<?> wrapper = call.getArgument(1);
            assertTrue(wrapper.getSqlSegment().contains("audit_status"));
            assertFalse(wrapper.getSqlSegment().contains("rspu_id"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("pending"));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("approved"));
            return 145;
        });
        BatchAuditResult result = service.approveAllPending();
        assertEquals(145, result.getProcessedCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    void batchAuditRejectsEmptySelection() {
        RspuMasterMapper mapper = mock(RspuMasterMapper.class);
        ProductAuditService service = new ProductAuditService(mapper);
        assertThrows(BusinessException.class, () -> service.approveBatch(List.of()));
        assertThrows(BusinessException.class, () -> service.rejectBatch(Arrays.asList(" ", null), null));
        verify(mapper, never()).update(any(), any());
    }
}
