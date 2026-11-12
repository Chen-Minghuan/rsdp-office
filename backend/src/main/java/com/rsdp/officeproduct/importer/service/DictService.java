package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.dto.response.CategoryDictDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典查询。数据源为主项目（RSDP）字典，保证两边品类/风格/场景/材质编码一致。
 */
@Service
@RequiredArgsConstructor
public class DictService {

    private final RsdpCatalogGateway rsdpCatalogGateway;

    public List<CategoryDictDto> listByType(String dictType) {
        return rsdpCatalogGateway.listDicts(dictType);
    }
}
