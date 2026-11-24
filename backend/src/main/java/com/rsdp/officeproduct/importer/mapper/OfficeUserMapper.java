package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface OfficeUserMapper extends BaseMapper<OfficeUser> {
    @Insert("""
        insert into office_user (user_id, username, password_hash, display_name, role, status)
        values ('LOCAL-DEV', '__rsdp_local_favorites__', '!', '本地开发收藏', 'USER', 'disabled')
        on conflict (user_id) do nothing
        """)
    int insertLocalFavoriteUser();
}
