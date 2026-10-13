package com.rsdp.officeproduct.importer.util;

import com.rsdp.officeproduct.importer.entity.CategoryDict;
import org.springframework.util.StringUtils;

import java.util.List;

public final class DictNormalizes {

    private DictNormalizes() {
    }

    public static String normalize(String input, List<CategoryDict> dicts) {
        String matched = normalizeOrNull(input, dicts);
        if (matched != null) {
            return matched;
        }
        return StringUtils.hasText(input) ? input.trim() : null;
    }

    public static String normalizeOrNull(String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        for (CategoryDict d : dicts) {
            if (trimmed.equalsIgnoreCase(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        for (CategoryDict d : dicts) {
            if (StringUtils.hasText(d.getDictName()) && trimmed.equals(d.getDictName())) {
                return d.getDictCode();
            }
        }
        return null;
    }

    public static boolean isValid(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equals(d.getDictCode()));
    }

    public static boolean isValidIgnoreCase(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equalsIgnoreCase(d.getDictCode()));
    }
}
