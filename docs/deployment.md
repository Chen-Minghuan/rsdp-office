# Jenkins 与服务器部署

流水线入口为根目录 [Jenkinsfile](../Jenkinsfile)，生产 Compose 为 [compose.deploy.yaml](../compose.deploy.yaml)。本地开发继续使用原有 Compose；两者项目名和数据存储独立。

## 服务器约定

Jenkins 构建节点与 Docker、部署目录位于同一台 Linux 服务器。需要 Bash、Git、Docker Compose、Python 3、curl、gzip、flock，以及 Jenkins 的 Pipeline、Git 和 JUnit 插件。节点账号需要现有的 Docker 使用权限。

插件文件存在不代表已经加载。`No flow definition, cannot run` 表示当次任务未取得流水线定义；如果后续日志已显示 `Obtained Jenkinsfile`，但报 `No such DSL method 'pipeline'`，应检查 Pipeline: Declarative 及其依赖的实际加载状态。本轮发现 `joda-time-api` 下载未完成，补齐官方插件并重启后恢复流水线启动，插件安装和重启规则见 [Jenkins 插件管理文档](https://www.jenkins.io/doc/book/managing/plugins/)。

| 路径 | 用途 |
| --- | --- |
| `/opt/rsdp-office/.env` | 服务器配置，`root:jenkins`、`0640`，不加入 Git、不归档 |
| `/opt/rsdp-office/data/postgres` | PostgreSQL 持久数据，仅数据库容器需要读写 |
| `/opt/rsdp-office/data/uploads` | 商品图片、原图、CAD 与概念预览的完整上传目录 |
| `/opt/rsdp-office/release/<commit>` | 非敏感 Compose、迁移 SQL 与执行日志 |
| `/opt/rsdp-office/backups` | 更新前的数据库逻辑备份 |

上级目录 `root:jenkins 0750`，`release`、`backups` 由 Jenkins 账号持有且权限为 `0750`。不把 PostgreSQL 数据目录改成 Jenkins 所有。备份只覆盖数据库，上传文件保留在独立目录；异地备份和定时备份另行配置。

配置模板为 [deploy/.env.example](../deploy/.env.example)。百炼配置从已有后端配置迁移；数据库密码、会话密钥、首个应用账号密码分别生成随机值，不使用 SSH 登录密码。应用初始账号默认 `admin`，密码在服务器 `.env` 的 `RSDP_BOOTSTRAP_PASSWORD` 中。

首次部署必须提供引导密码；账号创建后不会在下次启动时覆盖其密码。可清空引导密码，但预检会通过正在运行的 PostgreSQL 检查已有 active 用户；数据库停止时需先恢复数据库或暂留引导配置。

## Jenkins 使用

服务器现有 `rsdp-office` 任务为 Pipeline from SCM，分支 `*/main`、Script Path 为 `Jenkinsfile`。将本轮文件提交并推送到任务读取的 `main` 后，Jenkins 才能获取流水线。

首次若还没有「Build with Parameters」按钮，先点一次「Build Now」加载参数定义；默认只构建。

流水线按顺序执行：

1. 拉取源码，检查 Docker、配置文件权限、必填变量和 Compose。
2. 构建 Java `test` 阶段，执行 Maven 测试并将成功生成的 JUnit 报告交给 Jenkins。镜像构建阶段失败时可能没有可提取的测试报告，构建仍失败。
3. 构建后端、CAD、前端镜像。后端依赖测试阶段，前端使用锁文件安装依赖并执行 Nuxt 静态生成；`VITE_AUTH_ENABLED=1`、`VITE_API_BASE_URL=` 固定为同源认证构建。
4. 默认 `DEPLOY=false`，只验证和构建。需要发布时在「Build with Parameters」勾选 `DEPLOY`；仅允许部署当前 `origin/main`。
5. 部署时获得文件锁，停止旧前后端，启动数据库/CAD；迁移前先 `pg_dump` 并检查压缩文件，首次空库也保留备份。备份失败会中止，需要排查后重新执行。
6. 在事务中执行基础表和两个需显式执行的 Spatial 迁移；应用其他已有启动迁移由 Java 执行。启动所有服务并等待健康检查，再检查前端与匿名认证 `401`。

镜像按提交短 SHA 标记；部署目录不依赖 Jenkins 工作区持续存在。没有自动删卷、清库或清理历史镜像；也不在数据库结构变化后自动回滚旧应用。更新失败时，先看失败阶段、`release/<commit>/migrations.log` 和容器日志，再决定重试或使用备份恢复。

Java 中依赖数据库的测试是否运行以实际报告为准；未提供数据库集成测试配置时仍会跳过。CAD 镜像构建不等于 CAD 金样本测试或业务验收。

## 当前公网 HTTP 入口（2026-10-20）

已部署 main `956c411`，镜像标签 `956c41117698`，四个容器健康。按用户允许的独立端口方案，应用入口为 `http://101.200.81.123:8081/`；域名入口计划使用 `http://rooom.vip:8081/`，未实现 `/office/` 子路径。域名旧 A 记录已于 2026-10-21 删除，`rooom.vip` 稳定指向本实例；`www.rooom.vip` 仍指旧服务器，按需另行处理。

服务器外层 Nginx 配置见 [HTTP 入口模板](../deploy/nginx-office-http.conf)，实际路径为 `/etc/nginx/sites-available/rsdp-office-http`，转发到 `127.0.0.1:3001`。UFW 已允许 TCP 8081，用户已在 ECS 安全组允许 TCP 8081、来源 `0.0.0.0/0`；公网健康检查、真实浏览器登录、商品页、画布和退出失效均通过。原有 80 端口网站和 8080 Jenkins 保留。

HTTP 入口仅在代理层移除 `RSDP_SESSION` 的 Secure 标记，保留 HttpOnly / SameSite；后端默认 Secure 配置保留。外层覆盖来源转发头并清空客户端 `Forwarded`、`X-Forwarded-Prefix`。同时将 `/login/` 统一到 `/login`，直接提供 SPA 入口，避免静态目录重定向触发现有路由守卫的登录页自跳转。切换 HTTPS 时应取消 HTTP Cookie 例外。按用户要求暂不申请证书；certbot 已安装，本轮没有签发证书或启用 HTTPS。

部署、备份、迁移及公网验证证据见 [DEPLOY-002](tasks/DEPLOY-002-public-http.md)。首次云端数据库为空，不包含本地已有商品、图纸和方案。

2026-10-20 追加域名子路径入口：现有 rooom.vip 80 站点内新增 `/office/` 代理段（模板 [nginx-office-subpath.conf](../deploy/nginx-office-subpath.conf)），由 `frontend-office` 容器（`/office/` 基座构建，127.0.0.1:3002）提供应用；IP 8081 根路径入口与原静态站不受影响。`X-Forwarded-Prefix /office` 使新识别的资源地址落在子路径下；历史识别写入的 8081 绝对地址由前端 `sameOriginAssetUrl` 归一为当前入口同源路径。详见 [DEPLOY-004](tasks/DEPLOY-004-domain-subpath.md)。

## HTTPS 入口（2026-10-21 已启用）

用户提供阿里云免费 DV 证书（DigiCert 签发，覆盖 `rooom.vip` 与 `www.rooom.vip`，有效期 2026-10-19 至 2027-01-16，到期前需在阿里云控制台重新申请并替换 `/etc/nginx/ssl/rooom.vip.{pem,key}`，key 保持 0600 root:root）。证书由用户提供文件部署，未使用 certbot 自动签发与续期。

已在 `rooom.vip` 站点追加 443 server 块（模板说明见 [nginx-office-subpath.conf](../deploy/nginx-office-subpath.conf) 末尾，改动前备份于服务器 `backups/nginx-rooom.vip-20261021-pre-https`）：原静态站保留在 `/`，`/office/` 代理到 `frontend-office`（127.0.0.1:3002）；HTTPS 下不再移除 Cookie Secure 标记，实测会话 Cookie 为 `Secure; HttpOnly; SameSite=Lax`。按用户要求 80 端口 HTTP 入口与 8081 入口继续保留，未配置 80→443 跳转。

HTTPS 终止在外层 Nginx，Docker 内网与后端配置不变；`X-Forwarded-Proto` 随 scheme 传递。实测 `https://rooom.vip/office/` 200、登录页 200、匿名 API 401、原站点 200，真实 DNS 全流程（登录/文件选择/画布/退出）复测通过。`www.rooom.vip` 证书已覆盖但 DNS 仍指旧服务器，启用前需先改解析。

## 手动检查与部署

在仓库根目录、使用有权限的服务器账号执行：

```bash
bash scripts/deploy.sh check
bash scripts/deploy.sh build
bash scripts/deploy.sh deploy
```

默认配置路径为 `/opt/rsdp-office/.env`。脚本不会 `source` 配置或打印其内容，也不会输出完整的 `docker compose config`。

2026-10-22 GitHub 访问修复：Jenkins 构建多次在拉取阶段失败（GnuTLS 中断 / 连接超时），定位为服务器直连 github.com 不稳定。root 的 git 此前已配置 `url."https://ghfast.top/https://github.com/".insteadOf` 镜像改写，因此手工验证一直正常而 Jenkins（jenkins 用户、无此配置）失败。已为 jenkins 用户写入同样的 insteadOf 规则（`/var/lib/jenkins/.gitconfig`），以 jenkins 用户实测 `ls-remote` 经镜像 2.5 秒成功。Jenkins 任务配置不变（insteadOf 透明改写，仅用于公共仓库只读拉取）；ghfast.top 为第三方公共加速服务，如日后失效或直连恢复，删除该规则即可。

本轮服务器直连 Docker Hub 超时，已从本机拉取官方 amd64 镜像并经 SSH 导入服务器缓存。缓存可供当前节点构建使用；换节点或清理基础镜像后，需要恢复官方仓库连通性或重新导入官方镜像。本轮未更改服务器全局镜像源、DNS 和代理。

2026-10-20 的实际执行记录和未验证项见 [DEPLOY-001](tasks/DEPLOY-001-jenkins-server-env.md)。流水线语法参考 [Jenkins 官方文档](https://www.jenkins.io/doc/book/pipeline/syntax/)，启动等待使用 [Compose `up --wait`](https://docs.docker.com/reference/cli/docker/compose/up/)。
