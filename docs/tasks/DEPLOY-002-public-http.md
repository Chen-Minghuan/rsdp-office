# DEPLOY-002 正式启动与公网 HTTP 入口

状态：部分实现（公网 IP 入口已验证，域名旧 A 记录待清理）
更新日期：2026-10-20
发布版本：main `956c41117698c11c45e32de93876503a58694425`，镜像 `956c41117698`

## 目标与选择

用户要求部署应用并提供公网入口，优先考虑 `rooom.vip/office/`，随后明确允许没有证书时先用 HTTP，并允许改为域名加独立端口。实际采用 HTTP 8081，没有改造 Nuxt/API 为子路径，也没有占用原站点 80 或 Jenkins 8080。

## 实际执行

- Jenkins 第 3 次构建成功并产出本版本三个应用镜像，`DEPLOY=false`。正式部署是在服务器工作区以 Jenkins 用户手工运行 `IMAGE_TAG=956c41117698 bash scripts/deploy.sh deploy`，没有把只构建任务记作 Jenkins 自动发布成功。
- 首次云端空库已保存逻辑备份，执行基础/Spatial 迁移及 Java 启动迁移，初始化应用管理员。没有迁移本地商品、图纸、上传文件或方案。
- PostgreSQL、CAD、Java、前端四容器全部 healthy。前端只映射 `127.0.0.1:3001`，数据库和两个后端服务保持 Docker 内网访问。
- 备份：`/opt/rsdp-office/backups/20261020T081939Z-956c41117698.sql.gz`；部署日志：`/opt/rsdp-office/release/deploy-956c41117698.log`；迁移日志及配置快照位于 `/opt/rsdp-office/release/956c41117698/`；`current-image-tag` 为 `956c41117698`。
- 新增 [Nginx HTTP 模板](../../deploy/nginx-office-http.conf)，复制至服务器 `release/nginx-office-http.conf` 和 `/etc/nginx/sites-available/rsdp-office-http`，启用链接后通过 `nginx -t` 并 reload。现有站点配置保留。
- UFW 放行 TCP 8081；用户随后确认已在 ECS 安全组允许 TCP 8081、来源 `0.0.0.0/0`。
- HTTP 代理仅移除会话 Cookie 的 Secure 标记，保留 HttpOnly / SameSite。默认后端配置仍为 Secure；外层覆盖转发来源并清空不可信 Forwarded / X-Forwarded-Prefix。
- 直接访问 `/login/` 暴露现有路由守卫只识别 `/login` 的问题。入口将尾斜杠地址规范到 `/login`，该地址直接代理静态 SPA，避免目录重定向导致登录后回到登录表单。没有修改应用源码。
- 最初准备 HTTPS 时安装了 certbot；用户选择 HTTP 后没有签发证书或配置 HTTPS。

## 验证与边界

- 服务器健康、页面和 Nginx 语法检查通过；匿名私有 API 和 uploads 返回 401；伪造转发前缀/来源不能绕过认证。
- 先经 SSH 隧道完成真实 API 登录、会话、商品页、画布、退出验证，记录为隧道检查，不作为公网证据。
- 用户放行安全组前，公网 8081 返回空响应，服务器未收到对应访问日志；放行后公网 `http://101.200.81.123:8081/healthz` 返回 200。
- 放行后从本机启动隔离的 Playwright Chromium，直接访问公网 IP 根路径：真实登录、`/api/auth/me` 200、商品页和画布加载、退出后 me 401 均通过，无浏览器 pageerror。没有使用 mock，也没有使用 SSH 隧道执行这轮公网检查。
- 公网截图保存在本地 `output/deploy/public-canvas.png` 和 `public-login.png`，不包含账号密码或会话值，不纳入源码提交。
- 入口规范化后，再次通过公网直接访问 `/login/`：规范到 `/login`、登录返回首页、退出吊销会话均通过。最终四服务仍 healthy；172 个相关文档相对链接和 `git diff --check` 通过。
- 带域名 Host、强制连接本实例的入口返回 200；这不代表常规域名解析已稳定。
- 远端公共 DNS 查询仍返回 `101.200.81.123` 和 `120.77.15.115`。未向旧 IP 发送登录凭据。域名正常访问待删除旧 A 后重新验证。
- 云控制台浏览器操作未获 Microsoft Edge 使用许可，因此没有代改云端 DNS；安全组变更由用户完成。
- 本轮只新增代理配置和文档；未重跑 Java/前端全量构建，沿用实际 Jenkins 第 3 次构建结果。未执行真实模型识别、生图或完整业务验收。

## 交付与待办

当前可用入口：`http://101.200.81.123:8081/`。账号 `admin`，初始密码保存在服务器 `/opt/rsdp-office/.env` 的 `RSDP_BOOTSTRAP_PASSWORD`，文档不记录值。

2026-10-20 按用户后续要求，将现有 admin 密码更新为用户指定值（数据库保存随机盐 PBKDF2 哈希），撤销旧会话并同步服务器引导配置，文件权限保持 0640。通过 SSH 在服务器 HTTP 入口验证新密码登录、me 和退出成功；密码值不写入仓库。引导配置只在账号不存在时生效，后续部署不会覆盖现有账号密码。

域名目标入口：`http://rooom.vip:8081/`。删除 `rooom.vip` 指向 `120.77.15.115` 的旧 A 记录，保留本实例地址；DNS 生效后复核正常域名访问。HTTPS、子路径和本地业务数据迁移不属于本次已验证结论。
