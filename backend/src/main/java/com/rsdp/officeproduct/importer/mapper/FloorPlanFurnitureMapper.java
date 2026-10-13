package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.FloorPlanFurniture;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FloorPlanFurnitureMapper extends BaseMapper<FloorPlanFurniture> {

    @Select("SELECT * FROM floor_plan_furniture WHERE analysis_id = #{analysisId} AND analysis_revision = #{analysisRevision} AND deleted_at IS NULL ORDER BY sort_order, created_at")
    List<FloorPlanFurniture> selectByAnalysisRevision(@Param("analysisId") String analysisId,
                                                       @Param("analysisRevision") Integer analysisRevision);
}
