package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OfficeDesignProjectMapper extends BaseMapper<OfficeDesignProject> {
    @Update("""
        UPDATE office_design_project
        SET customer_requirements = #{customerRequirements},
            requirements_revision = requirements_revision + 1,
            updated_at = #{updatedAt}
        WHERE project_id = #{projectId}
        """)
    int updateRequirements(@Param("projectId") String projectId,
                           @Param("customerRequirements") String customerRequirements,
                           @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE office_design_project
        SET requirements_summary = CAST(#{summaryJson} AS jsonb),
            requirements_summary_revision = #{requirementsRevision},
            requirements_summary_model = #{model},
            requirements_summary_prompt_version = #{promptVersion},
            requirements_summary_generated_at = #{generatedAt}
        WHERE project_id = #{projectId}
          AND requirements_revision = #{requirementsRevision}
        """)
    int saveRequirementsSummary(@Param("projectId") String projectId,
                                @Param("requirementsRevision") int requirementsRevision,
                                @Param("summaryJson") String summaryJson,
                                @Param("model") String model,
                                @Param("promptVersion") String promptVersion,
                                @Param("generatedAt") LocalDateTime generatedAt);
}
