package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationRecordLineDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 在报价存储与接口记录之间转换。 */
@Component
@RequiredArgsConstructor
class QuotationRecordAssembler {

    private static final String SERVICE_FEE_NAME = "服务费";
    private static final String LOGISTICS_FEE_NAME = "物流搬运安装费";

    private final ObjectMapper objectMapper;

    String writeProposalSources(java.util.Map<String, String> sources) {
        try { return objectMapper.writeValueAsString(sources); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new BusinessException("报价方案版本无法保存"); }
    }

    ProjectQuotationRecordDto assemble(OfficeQuotation quotation, List<OfficeQuotationLine> rows,
                                       List<QuotationFeeDto> fees) {
        ProjectQuotationRecordDto dto = new ProjectQuotationRecordDto();
        dto.setQuotationId(quotation.getQuotationId());
        dto.setProjectId(quotation.getProjectId());
        dto.setProjectName(quotation.getProjectName());
        dto.setRevision(quotation.getRevision());
        dto.setStatus(quotation.getStatus());
        dto.setPricingMode(quotation.getPricingMode());
        dto.setPricingValue(quotation.getPricingValue());
        dto.setServiceFee(namedFeeTotal(fees, SERVICE_FEE_NAME));
        dto.setLogisticsFee(namedFeeTotal(fees, LOGISTICS_FEE_NAME));
        dto.setFees(fees);
        dto.setTotalAmount(quotation.getTotalAmount());
        dto.setLineCount(rows.size());
        dto.setCreatedBy(quotation.getCreatedBy());
        dto.setCreatedAt(quotation.getCreatedAt());

        BigDecimal goodsTotal = BigDecimal.ZERO;
        int missingLines = 0;
        for (OfficeQuotationLine row : rows) {
            QuotationRecordLineDto line = assembleLine(row);
            dto.getLines().add(line);
            if (line.getSubtotal() != null) {
                goodsTotal = goodsTotal.add(line.getSubtotal());
            }
            if (line.getIssue() != null) {
                missingLines++;
            }
        }
        dto.setGoodsTotal(QuotationPricing.money(goodsTotal));
        dto.setMissingPriceLines(missingLines);
        return dto;
    }

    private QuotationRecordLineDto assembleLine(OfficeQuotationLine row) {
        QuotationRecordLineDto line = new QuotationRecordLineDto();
        line.setFloorId(row.getFloorId());
        line.setFloorName(row.getFloorName());
        line.setZoneId(row.getZoneId());
        line.setZoneName(row.getZoneName());
        line.setProductId(row.getProductId());
        line.setRspuId(row.getRspuId());
        line.setProductName(row.getProductName());
        line.setVariant(row.getVariant());
        line.setImageUrl(row.getImageUrl());
        line.setQty(row.getQty());
        line.setFactoryPrice(row.getFactoryPrice());
        line.setUnitPrice(row.getUnitPrice());
        if (row.getUnitPrice() != null) {
            line.setSubtotal(QuotationPricing.subtotal(row.getUnitPrice(), row.getQty()));
        }
        line.setIssue(row.getIssue());
        return line;
    }

    private BigDecimal namedFeeTotal(List<QuotationFeeDto> fees, String name) {
        BigDecimal total = fees.stream()
            .filter(fee -> name.equals(fee.getName()))
            .map(QuotationFeeDto::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return QuotationPricing.money(total);
    }

    String writeFees(List<QuotationFeeDto> fees) {
        try {
            return objectMapper.writeValueAsString(fees);
        } catch (Exception failure) {
            throw new BusinessException("报价费用保存失败", failure);
        }
    }

    List<QuotationFeeDto> readFees(String feesJson) {
        try {
            return objectMapper.readValue(feesJson, new TypeReference<List<QuotationFeeDto>>() {});
        } catch (Exception failure) {
            throw new BusinessException("报价费用读取失败", failure);
        }
    }
}
