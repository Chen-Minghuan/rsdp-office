package com.rsdp.officeproduct.importer.office.mapper;

import com.rsdp.officeproduct.importer.office.entity.OfficeComboItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfficeComboItemMapper {

    @Select("select pg_advisory_xact_lock(hashtext(#{userId}), hashtext(#{comboId}))::text")
    String lockCombo(@Param("userId") String userId, @Param("comboId") String comboId);

    @Select("""
        select i.combo_id, i.rspu_id, i.sort_order, i.created_at
        from office_combo_item i
        join office_combo c on c.combo_id = i.combo_id
        where c.user_id = #{userId}
        order by i.combo_id, i.sort_order, i.rspu_id
        """)
    List<OfficeComboItem> findByUserId(@Param("userId") String userId);

    @Insert("""
        insert into office_combo_item (combo_id, rspu_id, sort_order)
        values (#{item.comboId}, #{item.rspuId}, #{item.sortOrder})
        """)
    int insert(@Param("item") OfficeComboItem item);

    @Delete("delete from office_combo_item where combo_id = #{comboId}")
    int deleteByComboId(@Param("comboId") String comboId);
}
