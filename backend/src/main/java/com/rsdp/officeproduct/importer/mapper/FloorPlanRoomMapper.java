package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.FloorPlanRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FloorPlanRoomMapper extends BaseMapper<FloorPlanRoom> {

    @Select("SELECT * FROM floor_plan_room WHERE analysis_id = #{analysisId} AND analysis_revision = #{analysisRevision} AND deleted_at IS NULL ORDER BY sort_order, created_at")
    List<FloorPlanRoom> selectByAnalysisRevision(@Param("analysisId") String analysisId,
                                                  @Param("analysisRevision") Integer analysisRevision);
}
