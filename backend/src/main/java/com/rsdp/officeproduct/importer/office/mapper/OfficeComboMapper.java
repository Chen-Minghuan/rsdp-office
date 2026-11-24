package com.rsdp.officeproduct.importer.office.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeCombo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OfficeComboMapper extends BaseMapper<OfficeCombo> {

    @Select("select pg_advisory_xact_lock(hashtext('office_combo'), hashtext(#{userId}))::text")
    String lockUser(@Param("userId") String userId);

    @Select("select pg_advisory_xact_lock(hashtext(#{userId}), hashtext(#{comboId}))::text")
    String lockCombo(@Param("userId") String userId, @Param("comboId") String comboId);

    // 改名与商品增删都只更新自己的字段，避免整实体写回互相覆盖。
    @Update("update office_combo set name = #{name}, updated_at = #{updatedAt} where combo_id = #{comboId}")
    int updateName(@Param("comboId") String comboId, @Param("name") String name, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("update office_combo set rspu_ids = #{rspuIds, typeHandler=com.rsdp.officeproduct.importer.office.mapper.VarcharArrayTypeHandler}, updated_at = #{updatedAt} where combo_id = #{comboId}")
    int updateRspuIds(@Param("comboId") String comboId, @Param("rspuIds") List<String> rspuIds, @Param("updatedAt") LocalDateTime updatedAt);
}
