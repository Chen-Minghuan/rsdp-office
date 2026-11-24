package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OfficeWorkspaceMapper extends BaseMapper<OfficeWorkspace> {
    @Select("select 1 from pg_advisory_xact_lock(20418025)")
    int lockDealerSlots();

    @Select("""
        select slot from generate_series(1, 20) as slot
        where not exists (select 1 from office_workspace where dealer_slot_no = slot)
        order by slot limit 1
        """)
    Integer nextDealerSlot();
}
