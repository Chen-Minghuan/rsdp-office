package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class RspuCodeService {

    private final RspuMasterMapper rspuMasterMapper;
    private final AtomicInteger sequence = new AtomicInteger(0);

    public String assignCode(String rspuId, String categoryCode, String positioningLabel, String sizeCode) {
        String prefix = "OF";
        if (categoryCode != null && !categoryCode.isBlank()) {
            prefix = categoryCode.toUpperCase();
        }
        String seq = String.format("%04d", sequence.incrementAndGet());
        String code = prefix + "-" + seq;

        RspuMaster update = new RspuMaster();
        update.setRspuId(rspuId);
        update.setRspuCode(code);
        rspuMasterMapper.updateById(update);
        return code;
    }

    public boolean isCodeExists(String rspuCode) {
        return rspuMasterMapper.selectCount(
            new QueryWrapper<RspuMaster>().eq("rspu_code", rspuCode)) > 0;
    }
}
