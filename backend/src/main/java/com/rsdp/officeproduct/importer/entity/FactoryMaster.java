package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("factory_master")
public class FactoryMaster {

    @TableId
    private Long id;

    private String factoryCode;
    private String factoryName;
    private String contactInfo;
}
