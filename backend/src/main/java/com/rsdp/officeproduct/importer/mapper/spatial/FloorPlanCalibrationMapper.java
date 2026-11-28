package com.rsdp.officeproduct.importer.mapper.spatial;

import com.rsdp.officeproduct.importer.entity.spatial.FloorPlanCalibration;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FloorPlanCalibrationMapper {

    @Select("SELECT * FROM floor_plan_calibration WHERE scene_id = #{sceneId} ORDER BY calibration_revision DESC LIMIT 1")
    FloorPlanCalibration selectLatest(@Param("sceneId") String sceneId);

    @Select("SELECT * FROM floor_plan_calibration WHERE scene_id = #{sceneId} AND calibration_revision = #{revision}")
    FloorPlanCalibration selectByRevision(@Param("sceneId") String sceneId,
                                           @Param("revision") Long revision);

    @Insert("INSERT INTO floor_plan_calibration (scene_id, calibration_revision, page_id, reference_points, reference_length_mm, mm_per_source_unit, source_to_world, basis, status, created_by, created_at) " +
        "VALUES (#{c.sceneId}, #{c.calibrationRevision}, #{c.pageId}, #{c.referencePoints, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, #{c.referenceLengthMm}, #{c.mmPerSourceUnit}, #{c.sourceToWorld, typeHandler=com.rsdp.officeproduct.importer.config.JsonbTypeHandler}, #{c.basis}, #{c.status}, #{c.createdBy}, #{c.createdAt})")
    int insert(@Param("c") FloorPlanCalibration calibration);

    /** 删除楼层时按场景批量清理校准记录。 */
    @Delete("""
        <script>
        DELETE FROM floor_plan_calibration WHERE scene_id IN
        <foreach collection="sceneIds" item="sceneId" open="(" separator="," close=")">#{sceneId}</foreach>
        </script>
        """)
    int deleteBySceneIds(@Param("sceneIds") List<String> sceneIds);
}
