package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkbookReferencePrice {
    static final String TEMPLATE_HEADER = "参考价（元）";
    static final Set<String> HEADERS = Set.of("参考价", "参考价元", "参考零售价", "参考零售价元", "零售价", "零售价元",
        "参考价格", "零售价格", "销售价", "销售价元", "销售价格", "市场价", "市场价元",
        "retailprice", "sellingprice", "referenceprice");
    static final Set<String> AMBIGUOUS_HEADERS = Set.of("价格", "价格元", "单价", "单价元", "price", "unitprice");
    private static final Set<String> EMPTY = Set.of("", "-", "—", "/", "N/A", "未注明");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:[¥￥]|CNY|RMB)?\\s*((?:[0-9]+|[0-9]{1,3}(?:[,，][0-9]{3})+)(?:\\.[0-9]{1,2})?)\\s*(?:元|CNY|RMB)?");

    private WorkbookReferencePrice() {}

    static boolean factoryHeader(String label) {
        return label.contains("出厂价") || label.contains("工厂价") || label.contains("供货价") || label.contains("批发价")
            || label.contains("成本") || label.contains("factoryprice") || label.contains("wholesale") || label.contains("exw");
    }

    static BigDecimal parse(String source) {
        String text = source.strip();
        if (EMPTY.contains(text)) return null;
        var match = AMOUNT.matcher(text);
        if (!match.matches()) throw new BusinessException("参考价须为人民币金额，最多两位小数");
        return LocalProductService.referencePrice(new BigDecimal(match.group(1).replace(",", "").replace("，", "")));
    }
}
