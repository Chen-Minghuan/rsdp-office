package com.rsdp.officeproduct.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.officeproduct.importer.entity.FactoryProductPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface FactoryProductPriceMapper extends BaseMapper<FactoryProductPrice> {
    /** 同商品、规格、工厂原子更新；空值保留已有报价信息。 */
    @Insert("""
        insert into factory_product_price
            (rspu_id, variant_id, factory_id, factory_price, moq, lead_time_days, effective_date)
        values (#{rspuId}, #{variantId}, #{factoryId}, coalesce(#{factoryPrice}, 0),
            coalesce(#{moq}, 1), coalesce(#{leadTimeDays}, 0), #{effectiveDate})
        on conflict (rspu_id, variant_id, factory_id) do update set
            factory_price = coalesce(#{factoryPrice}, factory_product_price.factory_price),
            moq = coalesce(#{moq}, factory_product_price.moq),
            lead_time_days = coalesce(#{leadTimeDays}, factory_product_price.lead_time_days),
            effective_date = excluded.effective_date
        """)
    int upsert(FactoryProductPrice price);
}
