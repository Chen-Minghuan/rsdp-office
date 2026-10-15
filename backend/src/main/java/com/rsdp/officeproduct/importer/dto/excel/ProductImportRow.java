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

    @ExcelProperty("商品名称")
    private String productName;

    @ExcelProperty("主色")
    private String colorPrimaryName;

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

    /** Original supplier cell when one source line expands into exact size variants. */
    private String sourceSizeText;

    @ExcelProperty("宽度(mm)")
    private Integer widthMm;

    @ExcelProperty("进深(mm)")
    private Integer depthMm;

    @ExcelProperty("高度(mm)")
    private Integer heightMm;

    @ExcelProperty("包数")
    private Integer packageCount;

    @ExcelProperty("体积(CBM)")
    private BigDecimal packageVolumeCbm;

    @ExcelProperty("重量(原表值)")
    private BigDecimal grossWeight;

    @ExcelProperty("颜色码")
    private String colorCode;

    @ExcelProperty("颜色原文")
    private String colorText;

    @ExcelProperty("材质码")
    private String materialCode;

    @ExcelProperty("材质原文")
    private String materialText;

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

}
