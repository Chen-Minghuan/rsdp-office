package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OfficeDesignFloorMapper extends BaseMapper<OfficeDesignFloor> {

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM office_design_floor WHERE project_id = #{projectId}")
    int nextSortOrder(@Param("projectId") String projectId);
}
