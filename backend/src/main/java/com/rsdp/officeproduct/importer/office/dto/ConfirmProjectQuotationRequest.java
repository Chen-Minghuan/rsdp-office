package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 确认报价请求：前端提交页面快照，服务端重算金额与校验归属，不信任前端合计。 */
@Data
public class ConfirmProjectQuotationRequest {

    private String pricingMode;
    private BigDecimal pricingValue;
    private List<Fee> fees = new ArrayList<>();
    private List<Line> lines = new ArrayList<>();
    /** 试算时各楼层依据的方案确认 ID；最终确认必须与当前快照一致。 */
    private java.util.Map<String, String> proposalConfirmationIds = new java.util.LinkedHashMap<>();

    @Data
    public static class Fee {

        private String name;
        private BigDecimal amount;
    }

    @Data
    public static class Line {

        private String floorId;
        private String zoneId;
        private String zoneName;
        private String productId;
        private Integer qty;
        /** 手填成交单价；为空时按口径用出厂价重算。 */
        private BigDecimal unitPrice;
    }
}
