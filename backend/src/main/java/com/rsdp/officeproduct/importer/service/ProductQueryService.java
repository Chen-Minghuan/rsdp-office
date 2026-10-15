package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final RspuMasterMapper rspuMasterMapper;

    public List<ProductListItemDto> listProducts() {
        List<RspuMaster> entities = rspuMasterMapper.selectList(
            new QueryWrapper<RspuMaster>().orderByDesc("created_at"));
        return entities.stream().map(this::toDto).toList();
    }

    private ProductListItemDto toDto(RspuMaster e) {
        ProductListItemDto dto = new ProductListItemDto();
        dto.setRspuId(e.getRspuId());
        dto.setRspuCode(e.getRspuCode());
        dto.setExternalCode(e.getExternalCode());
        dto.setProductName(e.getProductName());
        dto.setPrimaryImageUrl(e.getPrimaryImageUrl());
        dto.setRetailPrice(e.getRetailPrice());
        dto.setStatus(e.getStatus());
        dto.setAuditStatus(e.getAuditStatus());
        dto.setAuditRemark(e.getAuditRemark());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
