package com.rsdp.officeproduct.importer.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BatchAuditResult {

    /** 实际完成状态变更的商品数 */
    private int processedCount;

    /** 因不存在或已不是待审核状态而跳过的商品数 */
    private int skippedCount;
}
