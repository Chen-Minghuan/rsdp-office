package com.rsdp.officeproduct.importer.mapper.spatial;

import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanOperationReceipt;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FloorPlanOperationReceiptMapper {

    @Select("SELECT * FROM floor_plan_operation_receipt WHERE actor_id = #{actorId} AND scope_key = #{scopeKey} AND client_change_id = #{clientChangeId}")
    FloorPlanOperationReceipt selectByKey(@Param("actorId") String actorId,
                                           @Param("scopeKey") String scopeKey,
                                           @Param("clientChangeId") String clientChangeId);

    @Insert("INSERT INTO floor_plan_operation_receipt (actor_id, scope_key, client_change_id, action, request_hash, http_status, response_json, scene_id, scene_revision, created_at) " +
        "VALUES (#{r.actorId}, #{r.scopeKey}, #{r.clientChangeId}, #{r.action}, #{r.requestHash}, #{r.httpStatus}, #{r.responseJson, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, #{r.sceneId}, #{r.sceneRevision}, #{r.createdAt})")
    int insert(@Param("r") FloorPlanOperationReceipt receipt);

    /** 删除楼层时按场景批量清理操作回执。 */
    @Delete("""
        <script>
        DELETE FROM floor_plan_operation_receipt WHERE scene_id IN
        <foreach collection="sceneIds" item="sceneId" open="(" separator="," close=")">#{sceneId}</foreach>
        </script>
        """)
    int deleteBySceneIds(@Param("sceneIds") List<String> sceneIds);
}
