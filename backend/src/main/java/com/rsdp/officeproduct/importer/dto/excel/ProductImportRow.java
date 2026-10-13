package com.rsdp.officeproduct.importer.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品 Excel 批量导入行（表头为中文）。
 *
 * <p>一行对应一个商品规格，同一外部编码的多行共享 RSPU。</p>
 */
@Data
public class ProductImportRow {

    @ExcelProperty("RSPU ID")
    private String rspuId;

    @ExcelProperty("外部编码")
    private String externalCode;

    @ExcelProperty("品类码")
    private String categoryCode;

    @ExcelProperty("定位标签")
    private String positioningLabel;

    @ExcelProperty("商品名称")
    private String productName;

    @ExcelProperty("主色")
    private String colorPrimaryName;

    @ExcelProperty("材质标签")
    private String materialTags;

    @ExcelProperty("场景标签")
    private String sceneTags;

    @ExcelProperty("产品等级")
    private String productLevel;

    @ExcelProperty("保修年限")
    private Integer warrantyYears;

    @ExcelProperty("参考价格带")
    private String referencePriceBand;

    @ExcelProperty("六维标签")
    private String sixDimTags;

    @ExcelProperty("关键规格")
    private String keySpecs;

    @ExcelProperty("主图URL")
    private String primaryImageUrl;

    @ExcelProperty("详情图URLs")
    private String detailImageUrls;

    @ExcelProperty("变体显示名称")
    private String variantDisplayName;

    @ExcelProperty("尺寸码")
    private String sizeCode;

    @ExcelProperty("尺寸原文")
    private String sizeText;

    @ExcelProperty("颜色码")
    private String colorCode;

    @ExcelProperty("颜色原文")
    private String colorText;

    @ExcelProperty("材质码")
    private String materialCode;

    @ExcelProperty("材质原文")
    private String materialText;

    @ExcelProperty("变体参考价格带")
    private String variantReferencePriceBand;

    @ExcelProperty("变体产品等级")
    private String variantProductLevel;

    @ExcelProperty("描述/配置说明")
    private String description;

    @ExcelProperty("零售参考价")
    private BigDecimal retailPrice;

    @ExcelProperty("工厂价")
    private BigDecimal factoryPrice;

    @ExcelProperty("MOQ")
    private Integer moq;

    @ExcelProperty("交期天数")
    private Integer leadTimeDays;

    @ExcelProperty("数量")
    private Integer quantity;
}
