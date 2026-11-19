package com.rsdp.officeproduct.importer.office.mapper;

import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OfficeQuotationLineMapper {

    @Insert("""
        insert into office_quotation_line (
            quotation_id, line_no, floor_id, floor_name, zone_id, zone_name, product_id,
            rspu_id, product_name, variant, image_url, qty, factory_price, unit_price, issue
        ) values (
            #{quotationId}, #{lineNo}, #{floorId}, #{floorName}, #{zoneId}, #{zoneName}, #{productId},
            #{rspuId}, #{productName}, #{variant}, #{imageUrl}, #{qty}, #{factoryPrice}, #{unitPrice}, #{issue}
        )
        """)
    int insert(OfficeQuotationLine line);

    @Select("select * from office_quotation_line where quotation_id = #{quotationId} order by line_no")
    List<OfficeQuotationLine> findByQuotationId(@Param("quotationId") String quotationId);
}
