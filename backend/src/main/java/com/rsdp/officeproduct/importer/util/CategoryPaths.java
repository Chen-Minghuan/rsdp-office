package com.rsdp.officeproduct.importer.util;

public final class CategoryPaths {

    private CategoryPaths() {
    }

    public static String resolve(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return null;
        }
        return switch (categoryCode) {
            case "OF" -> "[\"办公家具\"]";
            case "DK" -> "[\"办公家具\",\"办公桌\"]";
            case "CH" -> "[\"办公家具\",\"办公椅\"]";
            case "CB" -> "[\"办公家具\",\"文件柜\"]";
            case "SO" -> "[\"办公家具\",\"沙发\"]";
            case "TB" -> "[\"办公家具\",\"茶几\"]";
            case "MT" -> "[\"办公家具\",\"会议桌\"]";
            case "MC" -> "[\"办公家具\",\"会议椅\"]";
            case "PA" -> "[\"办公家具\",\"屏风/隔断\"]";
            default -> "[\"办公家具\"]";
        };
    }
}
