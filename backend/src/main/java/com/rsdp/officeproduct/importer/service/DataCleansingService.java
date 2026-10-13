package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.response.DataCleansingReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DataCleansingService {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    private final ObjectMapper objectMapper;

    public void cleanse(ProductImportRow row, int rowIndex, DataCleansingReport report) {
        cleanString(row, "rspuId", rowIndex, report);
        cleanString(row, "externalCode", rowIndex, report);
        cleanString(row, "categoryCode", rowIndex, report);
        cleanString(row, "positioningLabel", rowIndex, report);
        cleanString(row, "productName", rowIndex, report);
        cleanString(row, "description", rowIndex, report);
        cleanString(row, "colorPrimaryName", rowIndex, report);
        cleanString(row, "materialTags", rowIndex, report);
        cleanString(row, "sceneTags", rowIndex, report);
        cleanString(row, "productLevel", rowIndex, report);
        cleanString(row, "referencePriceBand", rowIndex, report);
        cleanString(row, "sixDimTags", rowIndex, report);
        cleanString(row, "keySpecs", rowIndex, report);
        cleanString(row, "primaryImageUrl", rowIndex, report);
        cleanString(row, "detailImageUrls", rowIndex, report);
        cleanString(row, "variantDisplayName", rowIndex, report);
        cleanString(row, "sizeCode", rowIndex, report);
        cleanString(row, "colorCode", rowIndex, report);
        cleanString(row, "materialCode", rowIndex, report);
        cleanString(row, "variantReferencePriceBand", rowIndex, report);
        cleanString(row, "variantProductLevel", rowIndex, report);

        cleanJson(row, "sixDimTags", rowIndex, report);
        cleanJson(row, "keySpecs", rowIndex, report);

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
            if (!field.equals("sixDimTags") && !field.equals("keySpecs")) cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ");
            if (!cleaned.equals(original)) {
                Method setter = ProductImportRow.class.getMethod("set" + capitalize(field), String.class);
                setter.invoke(row, cleaned);
                addIssue(report, rowIndex, field, original, cleaned, "去除首尾空格、控制字符并规范化空白");
            }
        } catch (Exception ignored) {
        }
    }

    private void cleanJson(ProductImportRow row, String field, int rowIndex, DataCleansingReport report) {
        try {
            Method getter = ProductImportRow.class.getMethod("get" + capitalize(field));
            String original = (String) getter.invoke(row);
            if (!StringUtils.hasText(original)) {
                return;
            }
            String cleaned = original.trim();
            try {
                objectMapper.readTree(cleaned);
            } catch (Exception e) {
                // 尝试修复单引号
                cleaned = cleaned.replace("'", "\"");
                try {
                    objectMapper.readTree(cleaned);
                    Method setter = ProductImportRow.class.getMethod("set" + capitalize(field), String.class);
                    setter.invoke(row, cleaned);
                    addIssue(report, rowIndex, field, original, cleaned, "JSON 单引号修复为双引号");
                } catch (Exception ex) {
                    addIssue(report, rowIndex, field, original, original, "JSON 格式异常，校验阶段会报错");
                }
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
