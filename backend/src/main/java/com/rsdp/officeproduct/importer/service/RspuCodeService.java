package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.mapper.RspuMasterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class RspuCodeService {

    private final RspuMasterMapper rspuMasterMapper;
    private final AtomicInteger sequence = new AtomicInteger(0);

    public String assignCode(String rspuId) {
        String prefix = "PR";
        int nextSeq = 1;
        List<RspuMaster> latest = rspuMasterMapper.selectList(
            new QueryWrapper<RspuMaster>().likeRight("rspu_code", prefix + "-").orderByDesc("rspu_code").last("limit 1"));
        if (!latest.isEmpty()) {
            String code = latest.get(0).getRspuCode();
            try {
                nextSeq = Integer.parseInt(code.substring(code.lastIndexOf('-') + 1)) + 1;
            } catch (Exception ignored) {
            }
        }
        String code = prefix + "-" + String.format("%04d", nextSeq);
        while (rspuMasterMapper.selectCount(new QueryWrapper<RspuMaster>().eq("rspu_code", code)) > 0) {
            nextSeq++;
            code = prefix + "-" + String.format("%04d", nextSeq);
        }

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
