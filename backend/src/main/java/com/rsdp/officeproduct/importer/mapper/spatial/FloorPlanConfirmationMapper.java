package com.rsdp.officeproduct.importer.mapper.spatial;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanConfirmation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FloorPlanConfirmationMapper extends BaseMapper<FloorPlanConfirmation> {

    @Select("SELECT * FROM floor_plan_confirmation WHERE scene_id = #{sceneId} ORDER BY created_at DESC")
    List<FloorPlanConfirmation> selectByScene(@Param("sceneId") String sceneId);

    @Select("SELECT * FROM floor_plan_confirmation WHERE scene_id = #{sceneId} AND space_id = #{spaceId} ORDER BY created_at DESC LIMIT 1")
    FloorPlanConfirmation selectLatestBySpace(@Param("sceneId") String sceneId,
                                               @Param("spaceId") String spaceId);
}
