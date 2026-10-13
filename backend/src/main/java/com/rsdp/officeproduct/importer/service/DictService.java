package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.entity.CategoryDict;
import com.rsdp.officeproduct.importer.mapper.CategoryDictMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DictService {

    private final CategoryDictMapper categoryDictMapper;

    public List<CategoryDict> listByType(String dictType) {
        return categoryDictMapper.selectByType(dictType);
    }
}
