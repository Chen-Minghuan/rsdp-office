package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

/**
 * 字典项（只读展示）。数据源为主项目字典接口，office 不再维护本地字典表。
 */
@Data
public class CategoryDictDto {

    private String dictType;
    private String dictCode;
    private String dictName;
    private String dictNameEn;
    private String parentCode;
    private Integer sortOrder;
    private String status;
    private String aliases;
}
