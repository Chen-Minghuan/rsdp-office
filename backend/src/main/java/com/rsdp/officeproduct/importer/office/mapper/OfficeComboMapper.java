package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeCombo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OfficeComboMapper extends BaseMapper<OfficeCombo> {

    @Select("select pg_advisory_xact_lock(hashtext('office_combo'), hashtext(#{userId}))::text")
    String lockUser(@Param("userId") String userId);
}
