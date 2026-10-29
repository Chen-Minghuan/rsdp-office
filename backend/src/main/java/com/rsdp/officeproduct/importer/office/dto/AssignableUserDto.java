package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 可选为项目成员的系统用户，仅暴露有限字段。 */
@Data
public class AssignableUserDto {
    private String userId;
    private String username;
    private String displayName;
}
