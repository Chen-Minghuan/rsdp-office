package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductAuditService {

    private final RspuMasterMapper rspuMasterMapper;

    public List<RspuMaster> listPending() {
        return rspuMasterMapper.selectList(
            new QueryWrapper<RspuMaster>().eq("audit_status", "pending").orderByDesc("created_at"));
    }

    @Transactional
    public RspuMaster approve(String rspuId) {
        RspuMaster rspu = rspuMasterMapper.selectById(rspuId);
        if (rspu == null) {
            throw new BusinessException("商品不存在: " + rspuId);
        }
        rspu.setAuditStatus("approved");
        rspu.setAuditRemark(null);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.updateById(rspu);
        return rspu;
    }

    @Transactional
    public RspuMaster reject(String rspuId, String remark) {
        RspuMaster rspu = rspuMasterMapper.selectById(rspuId);
        if (rspu == null) {
            throw new BusinessException("商品不存在: " + rspuId);
        }
        rspu.setAuditStatus("rejected");
        rspu.setAuditRemark(remark);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.updateById(rspu);
        return rspu;
    }
}
