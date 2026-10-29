package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 管理员创建用户：用户名/显示名/初始密码，角色缺省 USER。 */
@Data
public class CreateOfficeUserRequest {
    private String username;
    private String displayName;
    private String password;
    private String role;
}
