package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.BatchAuditResult;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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

    @Transactional
    public BatchAuditResult approveBatch(List<String> rspuIds) {
        return updatePendingStatus(rspuIds, "approved", null);
    }

    @Transactional
    public BatchAuditResult rejectBatch(List<String> rspuIds, String remark) {
        return updatePendingStatus(rspuIds, "rejected", remark);
    }

    @Transactional
    public BatchAuditResult approveAllPending() {
        int updated = rspuMasterMapper.update(null, new UpdateWrapper<RspuMaster>()
            .eq("audit_status", "pending")
            .set("audit_status", "approved")
            .set("audit_remark", null)
            .set("updated_at", LocalDateTime.now()));
        return new BatchAuditResult(updated, 0);
    }

    /**
     * 只更新仍处于待审核状态的商品；不存在或已审核的记录计入跳过数量，
     * 避免并发或其他客户端已处理时把重复提交当作成功。
     */
    private BatchAuditResult updatePendingStatus(List<String> rspuIds, String status, String remark) {
        List<String> distinctIds = rspuIds == null ? List.of()
            : rspuIds.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
        if (distinctIds.isEmpty()) {
            throw new BusinessException("请选择要审核的商品");
        }
        UpdateWrapper<RspuMaster> wrapper = new UpdateWrapper<RspuMaster>()
            .in("rspu_id", distinctIds)
            .eq("audit_status", "pending")
            .set("audit_status", status)
            .set("audit_remark", remark)
            .set("updated_at", LocalDateTime.now());
        int updated = rspuMasterMapper.update(null, wrapper);
        return new BatchAuditResult(updated, distinctIds.size() - updated);
    }
}
