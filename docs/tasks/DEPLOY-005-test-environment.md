# DEPLOY-005 Office 测试环境和测试商品源

## 部署结构

| 用途 | 运行实例 | 数据与配置 |
| --- | --- | --- |
| Office 测试 | Jenkins `rsdp-office-test`，`test` 分支的 [Jenkinsfile.test](../../Jenkinsfile.test)，HTTPS `office-test.rooom.vip` | Compose 项目 `rsdp-office-test`，PostgreSQL `office_test`，`/opt/rsdp-office-test/.env`，独立的会话密钥、数据库、上传目录、备份和发布目录 |
| 测试商品 | [RSDP 商品服务测试 Compose](../../deploy/compose-catalog.test.yaml)，HTTPS API `rsdp-test.rooom.vip` | Compose 项目 `rsdp-catalog-test`，PostgreSQL `rsdp_test`，独立的 Redis、MinIO 和应用密钥；从空库执行主项目迁移 |

Office 测试环境使用主机回环端口 3003（根路径）和 3004（子路径镜像），RSDP 测试 API 使用回环端口 3200。[Nginx 测试站点配置](../../deploy/nginx-office-test.conf) 将两个测试子域名转发到各自的实例。应用认证、数据库和上传目录按实例分开。

## 发布和本地联调

Jenkins 任务使用 Pipeline script from SCM，分支 `*/test`，脚本路径 `Jenkinsfile.test`。默认执行构建和测试。勾选 `DEPLOY` 后才发布到 Office 测试环境。首次安装先准备 RSDP 测试商品服务、测试账号、两个 HTTPS 入口和服务器专用配置，再发布 Office。

本地运行 Office 时，可以通过根目录 `.env` 指向同一个测试商品源，然后重启本地 Java 后端：

```dotenv
RSDP_MAIN_BASE_URL=https://rsdp-test.rooom.vip
RSDP_MAIN_USERNAME=office_test_reader
RSDP_MAIN_PASSWORD=<测试商品只读账号密码>
```

本地项目等 Office 数据仍由本地数据库保存。测试商品账号凭据通过团队内部渠道提供。连接验收使用测试库中的商品校验列表和详情，同时检查正式应用容器的状态。
