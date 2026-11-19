package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Office 使用 RSDP 目录读取账号查询商品和历史批次。
 * 本服务只负责带凭证调用；可见性上限由主项目服务端控制。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rsdp.main")
public class RsdpMainProperties {

    /** 主项目后端地址，如 http://localhost:8081 */
    private String baseUrl = "";

    /** 主项目目录读取账号用户名 */
    private String username = "";

    /** 主项目目录读取账号密码 */
    private String password = "";

    /** 已核对的 Office 工作区及工厂绑定。 */
    private String catalogWorkspaceId = "";
    private String catalogFactoryCode = "";

    /** 是否已配置可用的主项目连接。 */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !username.isBlank() && !password.isBlank();
    }
}
