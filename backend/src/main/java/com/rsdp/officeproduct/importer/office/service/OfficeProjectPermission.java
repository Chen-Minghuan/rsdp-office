package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;

import java.util.Arrays;
import java.util.List;

/** 项目权限常量与成员表中的位掩码映射。具体授权由工作区主号保存。 */
public enum OfficeProjectPermission {
    READ(1), EDIT(2), QUOTE_READ(4), MARGIN_READ(8), EXPORT_PROJECT(16), MANAGE_MEMBERS(32), EXPORT_QUOTE(64);

    private final int bit;

    OfficeProjectPermission(int bit) { this.bit = bit; }

    public boolean granted(Integer flags) {
        return flags != null && (flags & READ.bit) != 0 && (flags & bit) != 0;
    }

    public static List<OfficeProjectPermission> assigned(Integer flags) {
        return Arrays.stream(values()).filter(permission -> flags != null && (flags & permission.bit) != 0).toList();
    }

    static int encode(List<OfficeProjectPermission> permissions) {
        int flags = 0;
        if (permissions != null) {
            for (OfficeProjectPermission permission : permissions) {
                if (permission == null) throw new BusinessException("权限项不能为空");
                flags |= permission.bit;
            }
        }
        if (flags != 0 && !READ.granted(flags)) throw new BusinessException("其他项目权限需要先勾选项目查看");
        if ((MARGIN_READ.granted(flags) || EXPORT_QUOTE.granted(flags)) && !QUOTE_READ.granted(flags)) {
            throw new BusinessException("毛利查看和报价导出需要先勾选报价查看");
        }
        return flags;
    }
}
