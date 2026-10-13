package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FloorPlanSceneRevisionMapper extends BaseMapper<FloorPlanSceneRevision> {

    @Select("SELECT * FROM floor_plan_scene_revision WHERE analysis_id = #{analysisId} AND analysis_revision = #{analysisRevision}")
    FloorPlanSceneRevision selectByRevision(@Param("analysisId") String analysisId,
                                             @Param("analysisRevision") Integer analysisRevision);

    @Update("UPDATE floor_plan_scene_revision SET " +
        "parent_analysis_revision = #{r.parentAnalysisRevision}, " +
        "revision_trigger = #{r.revisionTrigger}, " +
        "parse_status = #{r.parseStatus}, " +
        "revision_status = #{r.revisionStatus}, " +
        "room_revision = #{r.roomRevision}, " +
        "furniture_revision = #{r.furnitureRevision}, " +
        "parser_version = #{r.parserVersion}, " +
        "parser_config_hash = #{r.parserConfigHash}, " +
        "source_profile_version = #{r.sourceProfileVersion}, " +
        "scene_version = #{r.sceneVersion}, " +
        "unit_resolution = #{r.unitResolution, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, " +
        "raw_result = #{r.rawResult, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, " +
        "preview_image_id = #{r.previewImageId}, " +
        "quality_issues = #{r.qualityIssues, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, " +
        "drawing_bounds = #{r.drawingBounds, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, " +
        "preview_bounds = #{r.previewBounds, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, " +
        "ready_at = #{r.readyAt}, " +
        "confirmed_at = #{r.confirmedAt}, " +
        "activated_at = #{r.activatedAt}, " +
        "activated_by = #{r.activatedBy} " +
        "WHERE analysis_id = #{r.analysisId} AND analysis_revision = #{r.analysisRevision}")
    int updateByRevision(@Param("r") FloorPlanSceneRevision revision);
}
