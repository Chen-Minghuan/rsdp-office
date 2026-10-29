package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 管理员重置用户密码；重置后该用户既有会话全部失效。 */
@Data
public class ResetOfficeUserPasswordRequest {
    private String password;
}
