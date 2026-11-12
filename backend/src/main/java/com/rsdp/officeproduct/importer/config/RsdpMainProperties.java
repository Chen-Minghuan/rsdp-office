package com.rsdp.officeproduct.importer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主项目（RSDP）连接配置：office 以供应商服务账号读取主库商品数据。
 * 账号在主项目侧为工厂管理员角色 + 关联工厂码 + view_full_catalog=true，
 * 本服务只负责带凭证调用；可见性上限由主项目服务端控制。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rsdp.main")
public class RsdpMainProperties {

    /** 主项目后端地址，如 http://localhost:8081 */
    private String baseUrl = "";

    /** 主项目供应商服务账号用户名 */
    private String username = "";

    /** 主项目供应商服务账号密码 */
    private String password = "";

    /** 转发录入时的默认品类码（office 行无品类字段；主项目字典需存在该码） */
    private String defaultCategoryCode = "OF";

    /** 转发录入时的默认风格码（TBD=待识别，主项目编码降级段照常发号） */
    private String defaultStyleCode = "TBD";

    /** 转发录入时的默认产品等级（factory_level 字典码） */
    private String defaultProductLevel = "B";

    /** 转发录入时的默认材质码（行内材质无法归一为主项目字典时使用；办公家具多为板式） */
    private String defaultMaterialCode = "PB";

    /** 是否已配置可用的主项目连接。 */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !username.isBlank() && !password.isBlank();
    }
}
