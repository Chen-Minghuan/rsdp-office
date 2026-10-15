package com.rsdp.officeproduct.importer.mapper.spatial;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanSourceScene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FloorPlanSourceSceneMapper extends BaseMapper<FloorPlanSourceScene> {

    @Select("SELECT * FROM floor_plan_source_scene WHERE analysis_id = #{analysisId} AND analysis_revision = #{analysisRevision}")
    FloorPlanSourceScene selectByAnalysisRevision(@Param("analysisId") String analysisId,
                                                   @Param("analysisRevision") Integer analysisRevision);
}
