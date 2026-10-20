package com.rsdp.officeproduct.importer.controller;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchAuditRequest {

    @NotEmpty(message = "请选择要审核的商品")
    private List<String> rspuIds;

    private String remark;
}
