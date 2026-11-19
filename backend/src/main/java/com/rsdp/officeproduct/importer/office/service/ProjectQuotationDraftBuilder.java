package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 校验并重算用户编辑的报价，供导出和确认共用。 */
@Component
@RequiredArgsConstructor
class ProjectQuotationDraftBuilder {

    private static final int MAX_LINES = 2000;
    private static final int MAX_FEES = 50;
    private static final String UNASSIGNED_ZONE_NAME = "未分区商品";

    private final OfficeDesignFloorMapper floorMapper;
    private final QuotationCatalog catalog;

    Draft build(OfficeDesignProject project, ConfirmProjectQuotationRequest request) {
        if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
            throw new BusinessException("没有可确认的报价明细");
        }
        if (request.getLines().size() > MAX_LINES) {
            throw new BusinessException("报价明细不能超过 " + MAX_LINES + " 行，请拆分后再确认");
        }
        QuotationPricing pricing = QuotationPricing.of(request.getPricingMode(), request.getPricingValue());
        List<QuotationFeeDto> fees = normalizeFees(request.getFees());
        BigDecimal feeTotal = fees.stream().map(QuotationFeeDto::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, OfficeDesignFloor> floorsById = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
                .eq("project_id", project.getProjectId())).stream()
            .collect(Collectors.toMap(OfficeDesignFloor::getFloorId, floor -> floor, (first, second) -> first,
                LinkedHashMap::new));
        Map<String, OfficeProductDto> productsById = catalog.loadProducts();

        BigDecimal goodsTotal = BigDecimal.ZERO;
        List<OfficeQuotationLine> rows = new ArrayList<>();
        for (ConfirmProjectQuotationRequest.Line line : request.getLines()) {
            OfficeQuotationLine row = buildLine(line, rows.size(), floorsById, productsById, pricing);
            rows.add(row);
            if (row.getUnitPrice() != null) {
                goodsTotal = goodsTotal.add(QuotationPricing.subtotal(row.getUnitPrice(), row.getQty()));
            }
        }

        OfficeQuotation quotation = new OfficeQuotation();
        quotation.setProjectId(project.getProjectId());
        quotation.setProjectName(project.getName());
        quotation.setWorkspaceId(project.getWorkspaceId());
        quotation.setStatus("DRAFT");
        quotation.setPricingMode(pricing.mode().name());
        quotation.setPricingValue(pricing.value());
        quotation.setTotalAmount(QuotationPricing.money(goodsTotal.add(feeTotal)));
        return new Draft(quotation, rows, fees);
    }

    private OfficeQuotationLine buildLine(ConfirmProjectQuotationRequest.Line line, int lineNo,
                                          Map<String, OfficeDesignFloor> floorsById,
                                          Map<String, OfficeProductDto> productsById, QuotationPricing pricing) {
        if (line == null || !StringUtils.hasText(line.getProductId())) {
            throw new BusinessException("报价明细缺少商品");
        }
        OfficeDesignFloor floor = StringUtils.hasText(line.getFloorId()) ? floorsById.get(line.getFloorId()) : null;
        if (floor == null) {
            throw new BusinessException("报价明细包含不属于该项目的楼层：" + line.getFloorId());
        }
        int qty = line.getQty() == null ? 1 : line.getQty();
        if (qty < 0) {
            throw new BusinessException("数量不能为负数");
        }

        QuotationCatalog.ProductPrice pricedProduct = catalog.priceProduct(productsById.get(line.getProductId()));
        OfficeQuotationLine row = new OfficeQuotationLine();
        row.setLineNo(lineNo);
        row.setFloorId(floor.getFloorId());
        row.setFloorName(floor.getName());
        row.setZoneId(line.getZoneId());
        row.setZoneName(StringUtils.hasText(line.getZoneName()) ? line.getZoneName() : UNASSIGNED_ZONE_NAME);
        row.setProductId(line.getProductId());
        row.setQty(qty);
        row.setProductName(pricedProduct.productName());
        row.setFactoryPrice(pricedProduct.factoryPrice());
        row.setIssue(pricedProduct.issue());
        OfficeProductDto product = pricedProduct.product();
        if (product != null) {
            row.setRspuId(product.getRspuId());
            row.setVariant(product.getVariant());
            row.setImageUrl(product.getImageUrl());
        }
        if (row.getIssue() == null) {
            BigDecimal unitPrice = line.getUnitPrice() != null
                ? line.getUnitPrice() : pricing.unitPrice(pricedProduct.factoryPrice());
            if (unitPrice.signum() < 0) {
                throw new BusinessException("成交单价不能为负数");
            }
            row.setUnitPrice(QuotationPricing.money(unitPrice));
        }
        return row;
    }

    private List<QuotationFeeDto> normalizeFees(List<ConfirmProjectQuotationRequest.Fee> requestFees) {
        List<QuotationFeeDto> fees = new ArrayList<>();
        if (requestFees == null) {
            return fees;
        }
        if (requestFees.size() > MAX_FEES) {
            throw new BusinessException("费用项不能超过 " + MAX_FEES + " 条");
        }
        for (ConfirmProjectQuotationRequest.Fee requestFee : requestFees) {
            if (requestFee == null) {
                throw new BusinessException("费用项不能为空");
            }
            String name = requestFee.getName() == null ? "" : requestFee.getName().trim();
            BigDecimal amount = requestFee.getAmount() == null ? BigDecimal.ZERO : requestFee.getAmount();
            if (amount.signum() < 0) {
                throw new BusinessException("费用金额不能为负数");
            }
            amount = QuotationPricing.money(amount);
            if (!StringUtils.hasText(name)) {
                if (amount.signum() == 0) {
                    continue;
                }
                throw new BusinessException("请填写费用名称");
            }
            if (name.length() > 100) {
                throw new BusinessException("费用名称不能超过 100 字");
            }
            QuotationFeeDto fee = new QuotationFeeDto();
            fee.setName(name);
            fee.setAmount(amount);
            fees.add(fee);
        }
        return fees;
    }

    record Draft(OfficeQuotation quotation, List<OfficeQuotationLine> rows, List<QuotationFeeDto> fees) {
    }
}
