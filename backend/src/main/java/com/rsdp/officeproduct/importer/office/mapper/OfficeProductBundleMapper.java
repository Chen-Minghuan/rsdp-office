package com.rsdp.officeproduct.importer.office.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OfficeProductBundleMapper {
    record Row(String bundleId, String userId, String name, int revision, String itemsJson) {}

    @Select("""
        SELECT bundle_id, user_id, name, revision, items_json::text AS items_json
        FROM office_product_bundle WHERE user_id=#{userId} ORDER BY updated_at DESC, bundle_id
        """)
    List<Row> list(@Param("userId") String userId);

    @Select("""
        SELECT bundle_id, user_id, name, revision, items_json::text AS items_json
        FROM office_product_bundle WHERE bundle_id=#{bundleId} AND user_id=#{userId}
        """)
    Row findById(@Param("bundleId") String bundleId, @Param("userId") String userId);

    @Insert("""
        INSERT INTO office_product_bundle(bundle_id, user_id, name, items_json, revision, updated_at)
        VALUES(#{bundleId}, #{userId}, #{name}, CAST(#{itemsJson} AS jsonb), 1, now()) ON CONFLICT DO NOTHING
        """)
    int create(Row row);

    @Update("""
        UPDATE office_product_bundle SET name=#{name}, items_json=CAST(#{itemsJson} AS jsonb), revision=revision+1, updated_at=now()
        WHERE bundle_id=#{bundleId} AND user_id=#{userId} AND revision=#{revision}
        """)
    int update(Row row);

    @Delete("DELETE FROM office_product_bundle WHERE bundle_id=#{bundleId} AND user_id=#{userId} AND revision=#{revision}")
    int delete(@Param("bundleId") String bundleId, @Param("userId") String userId, @Param("revision") int revision);
}
