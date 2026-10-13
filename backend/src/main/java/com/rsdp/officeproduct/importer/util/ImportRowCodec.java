package com.rsdp.officeproduct.importer.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreview;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;

/** Excel 使用文本读取，数字格式错误保留到可编辑预览，不导致整份文件解析失败。 */
public final class ImportRowCodec {
    private ImportRowCodec() { }

    public static List<ProductImportPreview.Column> columns() {
        return Arrays.stream(ProductImportRow.class.getDeclaredFields())
            .filter(f -> f.isAnnotationPresent(ExcelProperty.class))
            .map(f -> new ProductImportPreview.Column(f.getName(), f.getAnnotation(ExcelProperty.class).value()[0],
                f.getType() == String.class ? "text" : "number"))
            .toList();
    }

    public static List<ProductImportPreviewRow> read(MultipartFile file) {
        List<ProductImportPreviewRow> rows = new ArrayList<>();
        Map<Integer, String> columnKeys = new LinkedHashMap<>();
        Map<String, String> headers = new HashMap<>();
        columns().forEach(c -> headers.put(c.label(), c.key()));
        try (InputStream stream = file.getInputStream()) {
            var reader = EasyExcel.read(stream, new AnalysisEventListener<Map<Integer, String>>() {
                @Override
                public void invokeHeadMap(Map<Integer, String> head, AnalysisContext context) {
                    Set<String> seen = new HashSet<>();
                    head.forEach((index, label) -> {
                        String key = headers.get(label == null ? "" : label.replace("\uFEFF", "").trim());
                        if (key != null) {
                            if (!seen.add(key)) throw new BusinessException("表头重复: " + label);
                            columnKeys.put(index, key);
                        }
                    });
                    if (!seen.contains("productName")) throw new BusinessException("缺少“商品名称”列，请使用标准模板");
                }

                @Override
                public void invoke(Map<Integer, String> data, AnalysisContext context) {
                    if (data.values().stream().allMatch(v -> v == null || v.isBlank())) return;
                    if (rows.size() >= 500) throw new BusinessException("单次导入不能超过 500 行");
                    ProductImportPreviewRow row = new ProductImportPreviewRow();
                    row.setRowIndex(context.readRowHolder().getRowIndex() + 1);
                    columnKeys.forEach((index, key) -> row.getFields().put(key, data.getOrDefault(index, "")));
                    rows.add(row);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) { }
            });
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
            if (name.endsWith(".csv")) reader.excelType(ExcelTypeEnum.CSV).charset(java.nio.charset.StandardCharsets.UTF_8);
            reader.sheet().doRead();
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof BusinessException business) throw business;
            }
            throw new BusinessException("无法解析文件，请检查格式、表头或 CSV 的 UTF-8 编码", e);
        }
        if (rows.isEmpty()) throw new BusinessException("文件没有可导入的商品行");
        return rows;
    }

    public static ProductImportRow decode(ProductImportPreviewRow source) {
        ProductImportRow row = new ProductImportRow();
        for (Field field : ProductImportRow.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(ExcelProperty.class)) continue;
            String raw = source.getFields().get(field.getName());
            if (raw == null || raw.isBlank()) continue;
            try {
                Object value = field.getType() == BigDecimal.class ? new BigDecimal(raw.trim())
                    : field.getType() == Integer.class ? new BigDecimal(raw.trim()).intValueExact() : raw;
                field.setAccessible(true);
                field.set(row, value);
            } catch (NumberFormatException | ArithmeticException e) {
                source.getErrors().add(field.getAnnotation(ExcelProperty.class).value()[0]
                    + (field.getType() == Integer.class ? "必须是整数" : "必须是数字"));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return row;
    }

    public static void encode(ProductImportRow row, ProductImportPreviewRow target) {
        for (Field field : ProductImportRow.class.getDeclaredFields()) {
            if (!field.isAnnotationPresent(ExcelProperty.class)) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(row);
                // 错误数字保留原文，供用户修改。
                if (value != null) target.getFields().put(field.getName(), value.toString());
                else target.getFields().putIfAbsent(field.getName(), "");
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
    }
}
