package com.rsdp.officeproduct.importer.mapper.spatial;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanDesignScene;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FloorPlanDesignSceneMapper extends BaseMapper<FloorPlanDesignScene> {

    @Update("UPDATE floor_plan_design_scene SET current_revision = #{currentRevision}, scope_revision = #{scopeRevision}, updated_at = now() WHERE scene_id = #{sceneId}")
    int updateRevision(@Param("sceneId") String sceneId,
                       @Param("currentRevision") Long currentRevision,
                       @Param("scopeRevision") Long scopeRevision);
}
