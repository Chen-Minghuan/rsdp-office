package com.rsdp.officeproduct.importer.office.product;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkbookMaterial {
    static final Set<String> DESCRIPTION_HEADERS = Set.of(
        "说明", "备注", "描述", "产品说明", "商品说明", "规格说明", "配置说明",
        "产品描述", "商品描述", "remark", "remarks", "description", "notes");
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_MATERIAL = 128;
    private static final Pattern MATERIAL = Pattern.compile(
        "高密度中纤(?:维)?板?|中密度纤维板|中纤板?|碳晶板|三聚氰胺板|三胺板|多层(?:实木)?板|实木颗粒板|"
            + "刨花板|颗粒板|密度板|胶合板|夹板|实木|木皮|岩板|大理石|石英石|乌金石|不锈钢|钢板|铝合金|亚克力|"
            + "钢化玻璃|玻璃|牛皮|真皮|生态皮|超纤皮|PU皮|布艺|海绵|(?<![a-zA-Z0-9])MDF(?![a-zA-Z0-9])",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED = Pattern.compile("(?:非|仿|不含|不是|并非|不使用|不采用|可选|选装)\\s*$");
    private static final Pattern NON_BODY = Pattern.compile("^\\s*(?:色|纹|木纹|茶盘|茶具|拉手|滑轨|插座|饰条配件)");
    private static final Pattern NON_MATERIAL_TERM = Pattern.compile("(?:[^\\s]{0,8}[灰白蓝绿红黄色]|油漆|喷粉|吸塑|石纹|木纹)");
    private static final Set<String> EMPTY = Set.of("-", "—", "/", "未注明", "无", "N/A");

    private WorkbookMaterial() {}

    static String description(String text) {
        String value = text.replaceAll("[\\p{Zs}\\t]+", " ").strip();
        return value.substring(0, Math.min(value.length(), MAX_DESCRIPTION));
    }

    static String explicit(String value) {
        String stripped = value.strip();
        return EMPTY.contains(stripped) ? "" : stripped;
    }

    static String fromDescription(String description) {
        var materials = new LinkedHashSet<String>();
        var matches = MATERIAL.matcher(description);
        while (matches.find()) {
            if (materialContext(description, matches.start(), matches.end())) materials.add(matches.group());
        }
        return join(materials);
    }

    static boolean supportedTerm(String description, String term) {
        if (term.length() < 2 || term.length() > 24 || !term.matches("[\\p{L}\\p{N} /-]+")
                || term.codePoints().noneMatch(Character::isLetter) || NON_MATERIAL_TERM.matcher(term).matches()) return false;
        for (int index = description.indexOf(term); index >= 0; index = description.indexOf(term, index + 1)) {
            if (materialContext(description, index, index + term.length())) return true;
        }
        return false;
    }

    private static boolean materialContext(String description, int start, int end) {
        var before = description.substring(Math.max(0, start - 6), start);
        var after = description.substring(end, Math.min(description.length(), end + 6));
        return !NEGATED.matcher(before).find() && !NON_BODY.matcher(after).find();
    }

    static String join(Set<String> terms) {
        var result = new StringBuilder();
        for (String term : terms) {
            int length = result.length() + (result.isEmpty() ? 0 : 1) + term.length();
            if (length > MAX_MATERIAL) break;
            if (!result.isEmpty()) result.append('、');
            result.append(term);
        }
        return result.toString();
    }
}
