package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OfficeProjectMemberMapper extends BaseMapper<OfficeProjectMember> {
    @Select("select 1 from pg_advisory_xact_lock(hashtextextended(#{projectId}, 391706))")
    int lockProjectMembers(String projectId);
}
