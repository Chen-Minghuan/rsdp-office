package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteFolder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OfficeFavoriteFolderMapper extends BaseMapper<OfficeFavoriteFolder> {

    @Insert("""
        insert into office_favorite_folder (folder_id, user_id, name, is_default)
        values (#{folderId}, #{userId}, '默认收藏夹', true)
        on conflict do nothing
        """)
    int insertDefaultFolder(@Param("folderId") String folderId, @Param("userId") String userId);
}
