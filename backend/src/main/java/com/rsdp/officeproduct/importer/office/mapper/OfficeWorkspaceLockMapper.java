package com.rsdp.officeproduct.importer.office.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Shared transaction lock for canvas, product selection and preview writes; no project row is required. */
@Mapper
public interface OfficeWorkspaceLockMapper {
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{projectId}, 0))")
    Integer lockWorkspace(@Param("projectId") String projectId);
}
