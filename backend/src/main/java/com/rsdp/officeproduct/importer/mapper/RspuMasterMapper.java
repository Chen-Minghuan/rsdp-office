package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;

@Mapper
public interface RspuMasterMapper extends BaseMapper<RspuMaster> {

    @Update("UPDATE rspu_master SET deleted_at = null, updated_at = now() WHERE rspu_id = #{rspuId}")
    int restoreById(@Param("rspuId") String rspuId);

    /** Physically removes a product and every database row owned by it. */
    @Delete("""
        <script>
        WITH ids(rspu_id) AS (
            VALUES
            <foreach collection="rspuIds" item="id" separator=",">(#{id})</foreach>
        ),
        variant_ids(variant_id) AS (
            SELECT v.variant_id FROM rspu_variant v JOIN ids ON ids.rspu_id = v.rspu_id
        ),
        deleted_cart AS (
            DELETE FROM office_cart c USING ids
            WHERE c.product_id = ids.rspu_id
        ),
        deleted_factory_price AS (
            DELETE FROM factory_product_price p USING ids
            WHERE p.rspu_id = ids.rspu_id
        ),
        deleted_images AS (
            DELETE FROM image_assets i USING ids
            WHERE i.rspu_id = ids.rspu_id
               OR i.variant_id IN (SELECT variant_id FROM variant_ids)
        ),
        deleted_styles AS (
            DELETE FROM rspu_style s USING ids
            WHERE s.rspu_id = ids.rspu_id
        ),
        deleted_scenes AS (
            DELETE FROM rspu_scene s USING ids
            WHERE s.rspu_id = ids.rspu_id
        ),
        deleted_variants AS (
            DELETE FROM rspu_variant v USING ids
            WHERE v.rspu_id = ids.rspu_id
        )
        DELETE FROM rspu_master m USING ids
        WHERE m.rspu_id = ids.rspu_id
        </script>
        """)
    int hardDeleteByRspuIds(@Param("rspuIds") Collection<String> rspuIds);
}
