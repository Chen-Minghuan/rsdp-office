package com.rsdp.officeproduct.importer;

import com.alibaba.excel.EasyExcel;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.util.ProductImportTemplateBuilder;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class SampleDataGenerator {

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "target/sample-products.xlsx";
        List<ProductImportRow> rows = new ArrayList<>();

        ProductImportRow chair = new ProductImportRow();
        chair.setProductName("人体工学办公椅");
        chair.setColorPrimaryName("黑色");
        chair.setPrimaryImageUrl("https://example.com/chair1.jpg");
        chair.setDetailImageUrls("https://example.com/chair1-a.jpg,https://example.com/chair1-b.jpg");
        chair.setVariantDisplayName("标准款/黑色");
        chair.setSizeText("680*680*1100");
        chair.setWidthMm(680);
        chair.setDepthMm(680);
        chair.setHeightMm(1100);
        chair.setColorText("黑色");
        chair.setMaterialText("网布");
        chair.setRetailPrice(new BigDecimal("1299"));
        chair.setFactoryPrice(new BigDecimal("899"));
        chair.setMoq(10);
        chair.setLeadTimeDays(15);
        rows.add(chair);

        ProductImportRow desk = new ProductImportRow();
        desk.setProductName("电动升降办公桌");
        desk.setColorPrimaryName("白色");
        desk.setPrimaryImageUrl("https://example.com/desk1.jpg");
        desk.setVariantDisplayName("1.2m/白色");
        desk.setSizeText("1200*600*750");
        desk.setWidthMm(1200);
        desk.setDepthMm(600);
        desk.setHeightMm(750);
        desk.setColorText("白色");
        desk.setMaterialText("钢木");
        desk.setRetailPrice(new BigDecimal("3499"));
        desk.setFactoryPrice(new BigDecimal("2499"));
        desk.setMoq(5);
        desk.setLeadTimeDays(20);
        rows.add(desk);

        ProductImportRow cabinet = new ProductImportRow();
        cabinet.setProductName("三抽文件柜");
        cabinet.setColorPrimaryName("灰色");
        cabinet.setPrimaryImageUrl("https://example.com/cabinet1.jpg");
        cabinet.setVariantDisplayName("三抽/灰色");
        cabinet.setSizeText("400*450*600");
        cabinet.setWidthMm(400);
        cabinet.setDepthMm(450);
        cabinet.setHeightMm(600);
        cabinet.setColorText("灰色");
        cabinet.setMaterialText("金属");
        cabinet.setRetailPrice(new BigDecimal("599"));
        cabinet.setFactoryPrice(new BigDecimal("399"));
        cabinet.setMoq(20);
        cabinet.setLeadTimeDays(10);
        rows.add(cabinet);

        File file = new File(path);
        file.getParentFile().mkdirs();
        EasyExcel.write(file, ProductImportRow.class)
            .sheet(ProductImportTemplateBuilder.TEMPLATE_SHEET_NAME)
            .doWrite(rows);
        System.out.println("已生成样例数据: " + file.getAbsolutePath());
    }
}
