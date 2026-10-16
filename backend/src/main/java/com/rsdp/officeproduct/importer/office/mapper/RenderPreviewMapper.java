package com.rsdp.officeproduct.importer.office.mapper;

import com.rsdp.officeproduct.importer.office.entity.RenderPreview;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RenderPreviewMapper {
    @Insert("""
        INSERT INTO office_render_preview
        (preview_id, project_id, zone_id, zone_name, canvas_revision, purpose, model, style, view, instructions,
         status, client_change_id, content_hash, snapshot_json, prompt, provider_base_url, reference_url, created_at, updated_at)
        VALUES (#{previewId}, #{projectId}, #{zoneId}, #{zoneName}, #{canvasRevision}, #{purpose}, #{model}, #{style}, #{view}, #{instructions},
         #{status}, #{clientChangeId}, #{contentHash}, CAST(#{snapshotJson} AS jsonb), #{prompt}, #{providerBaseUrl}, #{referenceUrl}, #{createdAt}, #{updatedAt})
        """)
    int insert(RenderPreview preview);

    @Update("""
        UPDATE office_render_preview SET status=#{status}, image_url=#{imageUrl}, error_message=#{errorMessage},
        provider_task_id=#{providerTaskId}, provider_request_id=#{providerRequestId}, updated_at=#{updatedAt}
        WHERE preview_id=#{previewId} AND project_id=#{projectId}
        """)
    int update(RenderPreview preview);

    @Select("SELECT *, snapshot_json::text AS snapshot_json FROM office_render_preview WHERE project_id=#{projectId} AND client_change_id=#{changeId}")
    RenderPreview byChange(@Param("projectId") String projectId, @Param("changeId") String changeId);

    @Select("SELECT *, snapshot_json::text AS snapshot_json FROM office_render_preview WHERE project_id=#{projectId} AND preview_id=#{previewId} FOR UPDATE")
    RenderPreview lock(@Param("projectId") String projectId, @Param("previewId") String previewId);

    @Select("SELECT *, snapshot_json::text AS snapshot_json FROM office_render_preview WHERE project_id=#{projectId} AND zone_id=#{zoneId} ORDER BY created_at DESC, preview_id DESC LIMIT 30")
    List<RenderPreview> list(@Param("projectId") String projectId, @Param("zoneId") String zoneId);
}
