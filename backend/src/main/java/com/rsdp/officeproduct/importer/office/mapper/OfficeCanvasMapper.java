package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface OfficeCanvasMapper extends BaseMapper<OfficeCanvas> {
    /** Latest correction per unique label on this exact raster, including older saved analyses. */
    @Select("""
        SELECT DISTINCT ON (regexp_replace(z->>'name', '[[:space:]]', '', 'g'))
          c.project_id, c.revision, jsonb_build_array(z)::text AS objects_json
        FROM office_canvas c
        CROSS JOIN LATERAL jsonb_array_elements(c.objects_json) z
        JOIN floor_plan_analysis a ON a.analysis_id = z->>'sourceAnalysisId' AND a.project_id = c.project_id
        JOIN floor_plan_scene_revision r ON r.analysis_id = a.analysis_id
          AND r.analysis_revision = CASE WHEN z->>'sourceAnalysisRevision' ~ '^[0-9]{1,6}$'
            THEN (z->>'sourceAnalysisRevision')::integer ELSE 1 END
        WHERE c.project_id = #{projectId} AND r.raw_result->>'imageSha256' = #{imageSha256}
          AND z->>'kind' = 'zone' AND z->>'geometrySource' = 'manual_geometry'
          AND (SELECT count(*) FROM jsonb_array_elements(c.objects_json) other
            WHERE other->>'kind' = 'zone' AND regexp_replace(other->>'name', '[[:space:]]', '', 'g')
              = regexp_replace(z->>'name', '[[:space:]]', '', 'g')) = 1
        ORDER BY regexp_replace(z->>'name', '[[:space:]]', '', 'g'), c.created_at DESC, c.canvas_id DESC
        """)
    List<OfficeCanvas> selectCorrectionSnapshots(@Param("projectId") String projectId, @Param("imageSha256") String imageSha256);
}
