package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 已校验的报价口径及参数。 */
record QuotationPricing(Mode mode, BigDecimal value) {

    static QuotationPricing of(String mode, BigDecimal value) {
        Mode pricingMode = Mode.parse(mode);
        return new QuotationPricing(pricingMode, pricingMode.normalize(value));
    }

    BigDecimal unitPrice(BigDecimal factoryPrice) {
        return mode.calculate(factoryPrice, value);
    }

    static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal subtotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    enum Mode {
        COEFFICIENT {
            @Override
            BigDecimal normalize(BigDecimal value) {
                BigDecimal coefficient = value == null ? new BigDecimal("1.20") : value;
                if (coefficient.signum() <= 0 || coefficient.compareTo(MAX_COEFFICIENT) > 0) {
                    throw new BusinessException("系数须大于 0 且不超过 100");
                }
                return coefficient;
            }

            @Override
            BigDecimal calculate(BigDecimal factoryPrice, BigDecimal coefficient) {
                return money(factoryPrice.multiply(coefficient));
            }
        },
        MARGIN {
            @Override
            BigDecimal normalize(BigDecimal value) {
                BigDecimal margin = value == null ? new BigDecimal("0.35") : value;
                if (margin.signum() <= 0 || margin.compareTo(BigDecimal.ONE) >= 0) {
                    throw new BusinessException("毛利率须大于 0 且小于 1（如 0.35 表示 35%）");
                }
                return margin;
            }

            @Override
            BigDecimal calculate(BigDecimal factoryPrice, BigDecimal margin) {
                return factoryPrice.divide(BigDecimal.ONE.subtract(margin), 2, RoundingMode.HALF_UP);
            }
        };

        private static final BigDecimal MAX_COEFFICIENT = new BigDecimal("100");

        private static Mode parse(String mode) {
            if (!StringUtils.hasText(mode) || "COEFFICIENT".equalsIgnoreCase(mode.trim())) {
                return COEFFICIENT;
            }
            if ("MARGIN".equalsIgnoreCase(mode.trim())) {
                return MARGIN;
            }
            throw new BusinessException("报价口径只能是 COEFFICIENT 或 MARGIN");
        }

        abstract BigDecimal normalize(BigDecimal value);

        abstract BigDecimal calculate(BigDecimal factoryPrice, BigDecimal value);
    }
}
