package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

/** 经销商主号在本工作区内开户；角色固定为 USER，不接受请求指定。 */
@Data
public class CreateWorkspaceUserRequest {
    private String username;
    private String displayName;
    private String password;
    /** DEALER_STAFF（经销商员工，缺省）或 FACTORY_SALES（厂家业务员）；一期不参与鉴权。 */
    private String source;
}
