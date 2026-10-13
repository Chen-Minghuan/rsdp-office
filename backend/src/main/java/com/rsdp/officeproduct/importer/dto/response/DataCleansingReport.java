package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入数据清洗报告。
 */
@Data
public class DataCleansingReport {

    private int cleanedRows;
    private int issuesFound;
    private List<CleansingIssue> issues = new ArrayList<>();

    @Data
    public static class CleansingIssue {
        private int rowIndex;
        private String field;
        private String original;
        private String cleaned;
        private String message;

        public CleansingIssue(int rowIndex, String field, String original, String cleaned, String message) {
            this.rowIndex = rowIndex;
            this.field = field;
            this.original = original;
            this.cleaned = cleaned;
            this.message = message;
        }
    }
}
