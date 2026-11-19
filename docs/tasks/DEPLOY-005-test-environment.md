# DEPLOY-005 Office 测试环境和测试商品源

## 部署结构

| 用途 | 运行实例 | 数据与配置 |
| --- | --- | --- |
| Office 测试 | Jenkins `rsdp-office-test`，`test` 分支的 [Jenkinsfile.test](../../Jenkinsfile.test)，HTTPS `office-test.rooom.vip` | Compose 项目 `rsdp-office-test`，PostgreSQL `office_test`，`/opt/rsdp-office-test/.env`，独立的会话密钥、数据库、上传目录、备份和发布目录 |
| 测试商品管理和 API | [RSDP 商品服务测试 Compose](../../deploy/compose-catalog.test.yaml)，HTTPS `rsdp-test.rooom.vip` | Compose 项目 `rsdp-catalog-test`，PostgreSQL `rsdp_test`，独立的 Redis、MinIO 和应用密钥；管理前端与后端使用同一镜像版本 |

Office 测试环境使用主机回环端口 3003（根路径）和 3004（子路径镜像），RSDP 测试 API 使用回环端口 3200，管理前端使用回环端口 3201。[Nginx 测试站点配置](../../deploy/nginx-office-test.conf) 将两个测试子域名转发到各自的实例；RSDP 的 `/api/` 仍直达测试后端，其他路径由管理前端提供 SPA 页面。应用认证、数据库和上传目录按实例分开。RSDP 与 Office 的测试账号分属各自的账号系统。

## 发布和本地联调

Jenkins 任务使用 Pipeline script from SCM，分支 `*/test`，脚本路径 `Jenkinsfile.test`。流水线每分钟检查一次 SCM，仅在 `test` 有新提交时触发构建，Java 测试和镜像构建通过后自动发布到 Office 测试环境，不再需要 `DEPLOY` 参数。手动构建同样自动发布。多个快速提交可能合并为一次构建，以实际检出的提交为准。首次安装先准备 RSDP 测试商品服务、测试账号、两个 HTTPS 入口和服务器专用配置，再发布 Office。

本地运行 Office 时，可以通过根目录 `.env` 指向同一个测试商品源，然后重启本地 Java 后端：

```dotenv
RSDP_MAIN_BASE_URL=https://rsdp-test.rooom.vip
RSDP_MAIN_USERNAME=office_test_reader
RSDP_MAIN_PASSWORD=<测试商品只读账号密码>
```

本地项目等 Office 数据仍由本地数据库保存。测试商品账号凭据通过团队内部渠道提供。连接验收使用测试库中的商品校验列表和详情，同时检查正式应用容器的状态。

## 2026-11-17 服务器验证

- 两个测试子域名已启用 HTTPS。测试商品服务使用服务器现有的 RSDP 镜像 `5129f2bb5a16`，在独立 `rsdp_test` 空库执行 27 条 Flyway 迁移。通过真实商品 API 创建了三件带尺寸和报价的样品。正式 RSDP 库查询结果中样品数为零。
- Jenkins `rsdp-office-test` 第 3 次构建部署成功，运行版本为 `d404ce0db24e`。Java 执行 277 项，0 失败、0 错误、3 跳过；Office 测试实例五个容器健康。首次构建继承了旧 Jenkins 任务的属性记录，已从测试任务移除。公网回归发现根路径部署时工作区请求错误回退到浏览器的 localhost，已修复并随第 3 次构建发布。
- `office_test` 完成数据库迁移及初始化，包含一个测试管理员、四个项目测试账号及示例项目。Chromium 已验证测试站匿名跳登录、管理员入口、经销商登录、示例项目和三件测试商品。正式 RSDP 与 Office 容器保持原运行实例，两个正式 HTTPS 入口返回 200。
- 本机 Office 的上游改为测试 RSDP HTTPS 服务。浏览器商品库展示三件样品，实际本机 Office API 验证三个商品的列表、详情和变体规格。测试商品读取账号的写入请求返回 403。

## 2026-11-17 商品图片代理返回 502

- 用商品 `OF-TBD-0016` 复现。RSDP 测试站直接读取两张图片均返回 200，Office 图片代理重复发送 `Transfer-Encoding: chunked`，测试站 Nginx 拒绝响应并返回 502。
- 二进制代理重新构造响应，仅转发适用的内容和缓存头，由 Office 发送自己的传输头。Java 回归 278 项、0 失败、0 错误、3 跳过。
- 从 `test` 分支部署 `99e00f385492`。公网验证两张 Office 代理图片均返回 200，响应字节与 RSDP 一致，未登录请求返回 401。Chromium 验证商品主图、两张缩略图和点击切换到原图均正常。

## 2026-11-18 测试分支自动部署

PR #2/#3/#4 已合入 `test`。测试任务每分钟检查新提交，构建成功后自动部署，不再需要手动发布参数。第 4 次 SCM 自动构建部署 `714b2e9628a8` 成功；容器健康、真实账号登录、工作台和侧栏拖拽验证通过。正式任务和服务保持原配置及容器。过程、已知问题和验证范围见 [DEPLOY-006](DEPLOY-006-test-auto-deploy.md)。

## 2026-11-19 RSDP 测试管理界面

此前 `rsdp-test.rooom.vip` 仅提供 `/api/`，浏览器首页按配置返回 404。现已增加与测试后端同版本的管理前端，首页、登录和商品列表可访问。真实账号登录、10 行商品展示及全库接口返回 185 件商品均通过，API 请求连接测试 RSDP 同源地址。原有运行容器均未重启。配置、备份和验证记录见 [DEPLOY-007](DEPLOY-007-catalog-test-web.md)。
