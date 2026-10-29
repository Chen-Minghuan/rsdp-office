package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 管理员启用/停用用户：status 仅接受 active / disabled。 */
@Data
public class UpdateOfficeUserStatusRequest {
    private String status;
}
