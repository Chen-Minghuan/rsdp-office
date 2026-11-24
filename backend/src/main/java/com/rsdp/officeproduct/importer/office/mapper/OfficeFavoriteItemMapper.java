package com.rsdp.officeproduct.importer.office.mapper;

import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfficeFavoriteItemMapper {

    @Select("select pg_advisory_xact_lock(hashtext(#{userId}), hashtext(#{rspuId}))::text")
    String lockProduct(@Param("userId") String userId, @Param("rspuId") String rspuId);

    @Select("""
        select i.folder_id, i.rspu_id, i.created_at
        from office_favorite_item i
        join office_favorite_folder f on f.folder_id = i.folder_id
        where f.user_id = #{userId}
        """)
    List<OfficeFavoriteItem> findByUserId(@Param("userId") String userId);

    @Insert("""
        insert into office_favorite_item (folder_id, rspu_id)
        values (#{folderId}, #{rspuId})
        on conflict (folder_id, rspu_id) do nothing
        """)
    int insertIgnore(@Param("folderId") String folderId, @Param("rspuId") String rspuId);

    @Delete("delete from office_favorite_item where folder_id = #{folderId} and rspu_id = #{rspuId}")
    int delete(@Param("folderId") String folderId, @Param("rspuId") String rspuId);

    @Delete("delete from office_favorite_item where folder_id = #{folderId}")
    int deleteByFolderId(@Param("folderId") String folderId);
}
