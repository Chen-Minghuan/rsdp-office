package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 确认报价请求：前端提交页面快照，服务端重算金额与校验归属，不信任前端合计。 */
@Data
public class ConfirmProjectQuotationRequest {

    /** PROJECT（默认）替换整项目报价；FLOOR 仅替换 floorId 对应楼层的已确认快照。 */
    private String confirmationScope;
    /** confirmationScope=FLOOR 时必填，且全部 lines 必须属于该楼层。 */
    private String floorId;
    private String pricingMode;
    private BigDecimal pricingValue;
    private List<Fee> fees = new ArrayList<>();
    private List<Line> lines = new ArrayList<>();

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
