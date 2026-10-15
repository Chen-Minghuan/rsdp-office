package com.rsdp.officeproduct.importer.mapper.spatial;

import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanDesignRevision;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FloorPlanDesignRevisionMapper {

    @Select("SELECT * FROM floor_plan_design_revision WHERE scene_id = #{sceneId} AND revision = #{revision}")
    FloorPlanDesignRevision selectByRevision(@Param("sceneId") String sceneId,
                                              @Param("revision") Long revision);

    @Insert("INSERT INTO floor_plan_design_revision (scene_id, revision, parent_revision, client_change_id, action, snapshot_json, created_by, created_at) " +
        "VALUES (#{r.sceneId}, #{r.revision}, #{r.parentRevision}, #{r.clientChangeId}, #{r.action}, #{r.snapshotJson, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, #{r.createdBy}, #{r.createdAt})")
    int insert(@Param("r") FloorPlanDesignRevision revision);
}
