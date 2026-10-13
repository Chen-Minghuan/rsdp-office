package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.entity.CategoryDict;
import com.rsdp.officeproduct.importer.util.DictNormalizes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DictAliasService {

    private final DictService dictService;

    public String resolveAlias(String dictType, String input) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        List<CategoryDict> dicts = dictService.listByType(dictType);
        String normalized = DictNormalizes.normalizeOrNull(input, dicts);
        if (normalized != null) {
            return normalized;
        }
        String trimmed = input.trim();
        for (CategoryDict dict : dicts) {
            if (StringUtils.hasText(dict.getAliases())) {
                for (String alias : dict.getAliases().split("[,，]")) {
                    if (trimmed.equalsIgnoreCase(alias.trim())) {
                        return dict.getDictCode();
                    }
                }
            }
            if (trimmed.equalsIgnoreCase(dict.getDictNameEn())) {
                return dict.getDictCode();
            }
        }
        return null;
    }
}
