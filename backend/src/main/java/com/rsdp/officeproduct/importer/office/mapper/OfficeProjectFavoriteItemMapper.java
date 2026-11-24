package com.rsdp.officeproduct.importer.office.mapper;

import com.rsdp.officeproduct.importer.office.entity.OfficeProjectFavoriteItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfficeProjectFavoriteItemMapper {

    @Select("""
        select project_id, user_id, rspu_id, created_at
        from office_project_favorite_item
        where project_id = #{projectId} and user_id = #{userId} and rspu_id = #{rspuId}
        """)
    OfficeProjectFavoriteItem findOne(@Param("projectId") String projectId,
                                      @Param("userId") String userId,
                                      @Param("rspuId") String rspuId);

    @Select("""
        select rspu_id from office_project_favorite_item
        where project_id = #{projectId} and user_id = #{userId}
        order by rspu_id
        """)
    List<String> findProductIds(@Param("projectId") String projectId, @Param("userId") String userId);

    @Select("""
        select project_id, user_id, rspu_id, created_at
        from office_project_favorite_item
        where project_id = #{projectId} and user_id = #{userId}
        """)
    List<OfficeProjectFavoriteItem> findItems(@Param("projectId") String projectId, @Param("userId") String userId);

    @Insert("""
        insert into office_project_favorite_item (project_id, user_id, rspu_id)
        values (#{projectId}, #{userId}, #{rspuId})
        on conflict (project_id, user_id, rspu_id) do nothing
        """)
    int insertIgnore(@Param("projectId") String projectId,
                     @Param("userId") String userId,
                     @Param("rspuId") String rspuId);

    @Delete("""
        delete from office_project_favorite_item
        where project_id = #{projectId} and user_id = #{userId} and rspu_id = #{rspuId}
        """)
    int delete(@Param("projectId") String projectId,
               @Param("userId") String userId,
               @Param("rspuId") String rspuId);
}
