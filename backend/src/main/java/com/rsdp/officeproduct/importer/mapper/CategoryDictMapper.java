package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.CategoryDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryDictMapper extends BaseMapper<CategoryDict> {

    @Select("SELECT * FROM category_dict WHERE dict_type = #{dictType} AND status = 'active'")
    List<CategoryDict> selectByType(@Param("dictType") String dictType);
}
