package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.response.DataCleansingReport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DataCleansingService {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t ]+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    public void cleanse(ProductImportRow row, int rowIndex, DataCleansingReport report) {
        cleanString(row, "rspuId", rowIndex, report);
        cleanString(row, "externalCode", rowIndex, report);
        cleanString(row, "productName", rowIndex, report);
        cleanString(row, "description", rowIndex, report);
        cleanString(row, "colorPrimaryName", rowIndex, report);
        cleanString(row, "primaryImageUrl", rowIndex, report);
        cleanString(row, "detailImageUrls", rowIndex, report);
        cleanString(row, "variantDisplayName", rowIndex, report);
        cleanString(row, "sizeCode", rowIndex, report);
        cleanString(row, "colorCode", rowIndex, report);
        cleanString(row, "materialCode", rowIndex, report);

        report.setCleanedRows(report.getCleanedRows() + 1);
    }

    private void cleanString(ProductImportRow row, String field, int rowIndex, DataCleansingReport report) {
        try {
            Method getter = ProductImportRow.class.getMethod("get" + capitalize(field));
            String original = (String) getter.invoke(row);
            if (!StringUtils.hasText(original)) {
                return;
            }
            String cleaned = original.trim();
            cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");
            if (field.equals("description")) cleaned = HORIZONTAL_SPACE.matcher(cleaned).replaceAll(" ");
            else cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ");
            if (!cleaned.equals(original)) {
                Method setter = ProductImportRow.class.getMethod("set" + capitalize(field), String.class);
                setter.invoke(row, cleaned);
                addIssue(report, rowIndex, field, original, cleaned, "去除首尾空格、控制字符并规范化空白");
            }
        } catch (Exception ignored) {
        }
    }

    private void addIssue(DataCleansingReport report, int rowIndex, String field, String original, String cleaned, String message) {
        report.getIssues().add(new DataCleansingReport.CleansingIssue(rowIndex, field, original, cleaned, message));
        report.setIssuesFound(report.getIssuesFound() + 1);
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public Set<String> findDuplicateExternalCodes(ProductImportRow[] rows) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (ProductImportRow row : rows) {
            String code = row.getExternalCode();
            if (StringUtils.hasText(code)) {
                if (!seen.add(code)) {
                    duplicates.add(code);
                }
            }
        }
        return duplicates;
    }
}
