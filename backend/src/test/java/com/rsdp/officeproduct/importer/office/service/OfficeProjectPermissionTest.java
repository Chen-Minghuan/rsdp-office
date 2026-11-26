package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficeProjectPermissionTest {

    @Test void encodeAcceptsNullAndEmptyAsZero() {
        assertEquals(0, OfficeProjectPermission.encode(null));
        assertEquals(0, OfficeProjectPermission.encode(List.of()));
    }

    @Test void encodeRejectsOtherPermissionsWithoutRead() {
        for (OfficeProjectPermission permission : List.of(OfficeProjectPermission.EDIT,
            OfficeProjectPermission.QUOTE_READ, OfficeProjectPermission.MANAGE_MEMBERS,
            OfficeProjectPermission.EXPORT_PROJECT)) {
            assertEquals("其他项目权限需要先勾选项目查看", assertThrows(BusinessException.class,
                () -> OfficeProjectPermission.encode(List.of(permission))).getMessage());
        }
        assertDoesNotThrow(() -> OfficeProjectPermission.encode(List.of(OfficeProjectPermission.READ)));
    }

    @Test void encodeRequiresQuoteReadForMarginAndExportQuote() {
        assertEquals("毛利查看和报价导出需要先勾选报价查看", assertThrows(BusinessException.class,
            () -> OfficeProjectPermission.encode(List.of(OfficeProjectPermission.READ,
                OfficeProjectPermission.MARGIN_READ))).getMessage());
        assertEquals("毛利查看和报价导出需要先勾选报价查看", assertThrows(BusinessException.class,
            () -> OfficeProjectPermission.encode(List.of(OfficeProjectPermission.READ,
                OfficeProjectPermission.EXPORT_QUOTE))).getMessage());
        assertEquals(1 + 4 + 8 + 64, OfficeProjectPermission.encode(List.of(OfficeProjectPermission.READ,
            OfficeProjectPermission.QUOTE_READ, OfficeProjectPermission.MARGIN_READ,
            OfficeProjectPermission.EXPORT_QUOTE)));
    }

    @Test void encodeRejectsNullPermissionItem() {
        assertEquals("权限项不能为空", assertThrows(BusinessException.class,
            () -> OfficeProjectPermission.encode(Arrays.asList(OfficeProjectPermission.READ, null))).getMessage());
    }

    @Test void grantedRequiresReadBitAndAssignedRoundTrips() {
        // granted 要求同时含 READ 位与目标位
        assertFalse(OfficeProjectPermission.EDIT.granted(2));
        assertTrue(OfficeProjectPermission.EDIT.granted(3));
        assertFalse(OfficeProjectPermission.READ.granted(null));
        assertTrue(OfficeProjectPermission.READ.granted(1));

        // 全部七项编码为 127，assigned 完整往返
        int all = OfficeProjectPermission.encode(List.of(OfficeProjectPermission.values()));
        assertEquals(127, all);
        assertEquals(List.of(OfficeProjectPermission.values()), OfficeProjectPermission.assigned(all));
        assertTrue(OfficeProjectPermission.assigned(0).isEmpty());
        assertTrue(OfficeProjectPermission.assigned(null).isEmpty());
    }
}
