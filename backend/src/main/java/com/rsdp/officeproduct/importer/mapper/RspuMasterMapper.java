package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RspuMasterMapper extends BaseMapper<RspuMaster> {

    @Update("UPDATE rspu_master SET deleted_at = null, updated_at = now() WHERE rspu_id = #{rspuId}")
    int restoreById(@Param("rspuId") String rspuId);
}
