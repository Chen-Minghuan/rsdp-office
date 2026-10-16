package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OfficeProjectMapper extends BaseMapper<OfficeProject> {
    @Select("SELECT project_id FROM office_project WHERE project_id = #{projectId} FOR UPDATE")
    String lockProject(@Param("projectId") String projectId);
}
