# DEPLOY-007 RSDP 测试管理站首页 404

日期：2026-11-19
状态：已部署并验证首页、登录和商品列表（不扩大为全部业务验收）

## 问题与原因

用户打开 `https://rsdp-test.rooom.vip/` 得到 Nginx 404。此前只部署测试 RSDP 商品 API、数据库及相关服务，尚未部署管理前端；Nginx 的 `/api/` 转发正常，`location /` 明确返回 404。API 可用不表示浏览器管理站已交付。

## 实现

- `deploy/compose-catalog.test.yaml` 添加 `web` 服务，复用与现有测试后端同版本的 `rsdp-web:${CATALOG_IMAGE_TAG}`，仅绑定 `127.0.0.1:3201`，共用测试网络并配置健康检查。
- `deploy/nginx-office-test.conf` 将测试 RSDP 的首页和 SPA 路由转发到管理前端，继续将 `/api/` 直达测试后端，保留 HTTPS 和来源转发头。
- 部署时备份运行中的 Compose 与 Nginx 配置，先启动并检查前端，再验证 Nginx 语法并平滑重载。只新增测试前端，不迁移数据库或升级现有商品服务。

## 验证

- 运行中的 Compose 与 Nginx 配置 SHA256 与仓库基线一致，部署前备份到服务器 `/opt/rsdp-catalog-test/backups/web-20261119T073810Z/`。Compose 渲染检查通过，前端镜像与测试后端均为 `5129f2bb5a16`。
- 仅新增 `rsdp-catalog-test-web-1`，健康检查通过；原有全部运行容器的名称、ID 和镜像与部署前基线一致，测试商品后端和数据库均未重启。
- `nginx -t` 通过并完成平滑重载。既有多个 443 虚拟主机有协议选项重复警告，本轮未扩大修改。重载刚提交时的两次请求仍命中旧配置，随后复测首页和 `/login` 均为 HTTP 200。
- 真实 Chromium（没有 API 模拟）验证：首页 200、登录页面可见、已有测试 RSDP 账号通过界面登录、`/products` 列表渲染 10 行、全库 API 返回 185 件商品。管理前端的 API 请求均为 `https://rsdp-test.rooom.vip` 同源，无页面脚本异常；验证后退出登录，未写入商品等业务数据。截图为 `/tmp/rsdp-catalog-test-login.png` 和 `/tmp/rsdp-catalog-test-products.png`。
- Office 测试站和正式 Office HTTPS 入口仍为 HTTP 200；RSDP 未登录的 `/api/v1/auth/me` 仍被拒绝（403），没有放开认证。
- `git diff --check` 和相关文档相对链接检查通过。本轮复用现有镜像，仅改部署配置，未重跑 Nuxt/Java/CAD 构建或测试；不将本次页面可用性验证扩大为全部业务验收。
