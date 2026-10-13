package com.rsdp.officeproduct.importer.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.write.handler.CellWriteHandler;
import com.alibaba.excel.write.handler.context.CellWriteHandlerContext;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.style.WriteCellStyle;
import com.alibaba.excel.write.metadata.style.WriteFont;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import org.apache.poi.ss.usermodel.IndexedColors;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ProductImportTemplateBuilder {

    public static final String TEMPLATE_SHEET_NAME = "产品导入模板";
    public static final String INSTRUCTION_SHEET_NAME = "填写说明";

    private static final Set<String> REQUIRED_HEADS = Set.of("商品名称");
    private static final Set<String> RECOMMENDED_HEADS = Set.of(
        "外部编码", "品类码", "定位标签", "主图URL", "零售参考价", "六维标签");

    private ProductImportTemplateBuilder() {
    }

    public static List<String> headerNames() {
        List<String> heads = new ArrayList<>();
        for (Field field : ProductImportRow.class.getDeclaredFields()) {
            ExcelProperty property = field.getAnnotation(ExcelProperty.class);
            if (property == null) {
                continue;
            }
            String[] value = property.value();
            heads.add(value.length > 0 && !value[0].isBlank() ? value[0] : field.getName());
        }
        return heads;
    }

    public static void write(OutputStream outputStream) {
        List<List<String>> head = headerNames().stream()
            .<List<String>>map(List::of)
            .toList();

        var excelWriter = EasyExcel.write(outputStream)
            .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
            .registerWriteHandler(new TemplateHeaderStyleHandler())
            .build();
        try {
            WriteSheet templateSheet = EasyExcel.writerSheet(0, TEMPLATE_SHEET_NAME)
                .head(head)
                .build();
            excelWriter.write(List.of(), templateSheet);

            WriteSheet instructionSheet = EasyExcel.writerSheet(1, INSTRUCTION_SHEET_NAME)
                .needHead(false)
                .build();
            excelWriter.write(instructionRows(), instructionSheet);
        } finally {
            excelWriter.finish();
        }
    }

    static List<List<String>> instructionRows() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("图例", "红色加粗 = 必填列；橙色加粗 = 建议填写列；其余 = 可选列"));
        rows.add(List.of("重要", "表头文字请勿修改；多值字段用英文逗号或分号分隔；CSV 使用 UTF-8 编码"));
        rows.add(List.of("多规格", "同一外部编码的多行归为一个商品，尺寸/颜色/材质不同则建立不同规格；共享商品信息必须一致，后续行可留空"));
        rows.add(List.of("报价更新", "报价绑定规格；更新同一规格报价时空值保留已有信息，不重复新增报价"));
        rows.add(List.of("更新模式", "updateIfExists=true 时，空单元格不覆盖已有数据"));
        rows.add(List.of());
        rows.add(List.of("列名", "填写要求", "说明", "示例值"));
        rows.add(List.of("品类码", "建议", "新商品为空时使用 OF（办公家具）；已有商品保留原品类；如 DK 办公桌 / CH 办公椅", "DK"));
        rows.add(List.of("商品名称", "必填", "新商品必填，同编码规格行可继承；更新已有商品可留空", "人体工学办公椅"));
        rows.add(List.of("六维标签", "建议", "合法 JSON 字符串", "{\"A\":[\"A1\"],\"B\":[\"B2\"]}"));
        rows.add(List.of("尺寸原文", "建议", "工厂尺寸/规格原文", "1200*600*750"));
        rows.add(List.of("零售参考价", "建议", "数字（元）", "1299"));
        rows.add(List.of("工厂价", "可选", "数字（元），单工厂报价", "899"));
        rows.add(List.of("MOQ", "可选", "最小起订量，默认 1", "10"));
        rows.add(List.of("交期天数", "可选", "整数天数", "15"));
        return rows;
    }

    private static class TemplateHeaderStyleHandler implements CellWriteHandler {

        @Override
        public void afterCellDispose(CellWriteHandlerContext context) {
            if (!Boolean.TRUE.equals(context.getHead())
                || context.getWriteSheetHolder().getSheetNo() != 0) {
                return;
            }
            String headName = context.getCell().getStringCellValue();
            if (REQUIRED_HEADS.contains(headName)) {
                applyStyle(context, IndexedColors.RED.getIndex());
            } else if (RECOMMENDED_HEADS.contains(headName)) {
                applyStyle(context, IndexedColors.ORANGE.getIndex());
            }
        }

        private void applyStyle(CellWriteHandlerContext context, short colorIndex) {
            WriteCellStyle cellStyle = context.getFirstCellData().getOrCreateStyle();
            WriteFont font = new WriteFont();
            font.setBold(true);
            font.setColor(colorIndex);
            cellStyle.setWriteFont(font);
        }
    }
}
