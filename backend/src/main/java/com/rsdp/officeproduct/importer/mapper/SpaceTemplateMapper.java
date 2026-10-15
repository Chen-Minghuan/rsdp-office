package com.rsdp.officeproduct.importer.mapper;

import com.rsdp.officeproduct.importer.entity.SpaceTemplate;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SpaceTemplateMapper {
    @Insert("INSERT INTO office_space_template (template_id, project_id, name, client_change_id, content_hash, source_canvas_revision, source_analysis_id, source_analysis_revision, space_count, area_m2, evidence_kind, scene_json, created_at) " +
        "VALUES (#{templateId}, #{projectId}, #{name}, #{clientChangeId}, #{contentHash}, #{sourceCanvasRevision}, #{sourceAnalysisId}, #{sourceAnalysisRevision}, #{spaceCount}, #{areaM2}, #{evidenceKind}, CAST(#{sceneJson} AS jsonb), #{createdAt}) ON CONFLICT (project_id, client_change_id) DO NOTHING")
    int insert(SpaceTemplate template);

    @Select("SELECT *, scene_json::text AS scene_json FROM office_space_template WHERE project_id=#{projectId} AND template_id=#{templateId}")
    SpaceTemplate get(@Param("projectId") String projectId, @Param("templateId") String templateId);

    @Select("SELECT *, scene_json::text AS scene_json FROM office_space_template WHERE project_id=#{projectId} AND client_change_id=#{changeId}")
    SpaceTemplate byChange(@Param("projectId") String projectId, @Param("changeId") String changeId);

    @Select("SELECT template_id, project_id, name, content_hash, source_canvas_revision, source_analysis_id, source_analysis_revision, space_count, area_m2, evidence_kind, created_at FROM office_space_template WHERE project_id=#{projectId} ORDER BY created_at DESC, template_id DESC LIMIT 50")
    List<SpaceTemplate> list(@Param("projectId") String projectId);
}
