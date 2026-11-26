package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 最近一条报价记录（任意状态）的摘要：用于页面展示上一次报价时间，不含金额与价格信息。 */
@Data
public class ProjectQuotationLatestDto {

    private String quotationId;
    private String status;
    private Integer revision;
    private LocalDateTime createdAt;
}
