package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("factory_product_price")
public class FactoryProductPrice {

    @TableId
    private Long id;

    private String rspuId;
    private String variantId;
    private Long factoryId;
    private BigDecimal factoryPrice;
    private Integer moq;
    private Integer leadTimeDays;
    private LocalDate effectiveDate;
}
