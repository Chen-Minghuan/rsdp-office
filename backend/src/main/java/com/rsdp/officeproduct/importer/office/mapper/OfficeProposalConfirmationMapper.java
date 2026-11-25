package com.rsdp.officeproduct.importer.office.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OfficeProposalConfirmationMapper {
    record Row(String confirmationId, String floorId, int canvasRevision, String fingerprint, String snapshotJson,
               String confirmedBy, LocalDateTime confirmedAt, boolean retired) {}

    @Select("""
        SELECT confirmation_id, floor_id, canvas_revision, fingerprint, snapshot_json::text AS snapshot_json,
               confirmed_by, confirmed_at, retired
        FROM office_proposal_confirmation WHERE floor_id=#{floorId}
        ORDER BY confirmed_at DESC, confirmation_id DESC LIMIT 1
        """)
    Row latest(@Param("floorId") String floorId);

    /** 批量返回存在未失效(retired=false)确认记录的楼层 ID，用于项目阶段派生。 */
    @Select("""
        <script>
        SELECT DISTINCT floor_id FROM office_proposal_confirmation
        WHERE retired = false AND floor_id IN
        <foreach collection="floorIds" item="floorId" open="(" separator="," close=")">#{floorId}</foreach>
        </script>
        """)
    List<String> confirmedFloorIds(@Param("floorIds") List<String> floorIds);

    @Insert("""
        INSERT INTO office_proposal_confirmation(confirmation_id, floor_id, canvas_revision, fingerprint, snapshot_json, confirmed_by, confirmed_at)
        VALUES (#{confirmationId}, #{floorId}, #{canvasRevision}, #{fingerprint}, CAST(#{snapshotJson} AS jsonb), #{confirmedBy}, #{confirmedAt})
        """)
    void insert(Row confirmation);
}
