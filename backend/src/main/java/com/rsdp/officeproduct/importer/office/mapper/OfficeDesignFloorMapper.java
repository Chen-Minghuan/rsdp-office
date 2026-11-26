package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OfficeDesignFloorMapper extends BaseMapper<OfficeDesignFloor> {

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM office_design_floor WHERE project_id = #{projectId}")
    int nextSortOrder(@Param("projectId") String projectId);

    /** 只写名称列：与参考图等其它列的并发保存互不覆盖。 */
    @Update("""
        UPDATE office_design_floor
        SET name = #{name},
            updated_at = #{updatedAt}
        WHERE floor_id = #{floorId}
        """)
    int updateName(@Param("floorId") String floorId,
                   @Param("name") String name,
                   @Param("updatedAt") LocalDateTime updatedAt);

    /** 只写参考图列：与改名等其它列的并发保存互不覆盖。 */
    @Update("""
        UPDATE office_design_floor
        SET reference_images = #{referenceImages},
            updated_at = #{updatedAt}
        WHERE floor_id = #{floorId}
        """)
    int updateReferenceImages(@Param("floorId") String floorId,
                              @Param("referenceImages") String referenceImages,
                              @Param("updatedAt") LocalDateTime updatedAt);
}
