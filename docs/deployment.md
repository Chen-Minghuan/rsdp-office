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

已部署 main `956c411`，镜像标签 `956c41117698`，四个容器健康。按用户允许的独立端口方案，应用入口为 `http://101.200.81.123:8081/`；域名入口计划使用 `http://rooom.vip:8081/`，未实现 `/office/` 子路径。当前域名仍有 `120.77.15.115` 和 `101.200.81.123` 两条 A 记录，需要移除旧地址才能稳定落到本实例。

服务器外层 Nginx 配置见 [HTTP 入口模板](../deploy/nginx-office-http.conf)，实际路径为 `/etc/nginx/sites-available/rsdp-office-http`，转发到 `127.0.0.1:3001`。UFW 已允许 TCP 8081，用户已在 ECS 安全组允许 TCP 8081、来源 `0.0.0.0/0`；公网健康检查、真实浏览器登录、商品页、画布和退出失效均通过。原有 80 端口网站和 8080 Jenkins 保留。

HTTP 入口仅在代理层移除 `RSDP_SESSION` 的 Secure 标记，保留 HttpOnly / SameSite；后端默认 Secure 配置保留。外层覆盖来源转发头并清空客户端 `Forwarded`、`X-Forwarded-Prefix`。同时将 `/login/` 统一到 `/login`，直接提供 SPA 入口，避免静态目录重定向触发现有路由守卫的登录页自跳转。切换 HTTPS 时应取消 HTTP Cookie 例外。按用户要求暂不申请证书；certbot 已安装，本轮没有签发证书或启用 HTTPS。

部署、备份、迁移及公网验证证据见 [DEPLOY-002](tasks/DEPLOY-002-public-http.md)。首次云端数据库为空，不包含本地已有商品、图纸和方案。

2026-10-20 追加域名子路径入口：现有 rooom.vip 80 站点内新增 `/office/` 代理段（模板 [nginx-office-subpath.conf](../deploy/nginx-office-subpath.conf)），由 `frontend-office` 容器（`/office/` 基座构建，127.0.0.1:3002）提供应用；IP 8081 根路径入口与原静态站不受影响。`X-Forwarded-Prefix /office` 使新识别的资源地址落在子路径下；历史识别写入的 8081 绝对地址由前端 `sameOriginAssetUrl` 归一为当前入口同源路径。详见 [DEPLOY-004](tasks/DEPLOY-004-domain-subpath.md)。

## 后续 HTTPS 入口

生产 Compose 只将前端映射到 `127.0.0.1:3001`，数据库、Java、CAD 没有宿主机端口。后端默认登录 Cookie 启用 Secure；除上述用户指定的 HTTP 入口例外外，浏览器应通过 HTTPS 使用。

将下面配置加入已有且证书有效的 HTTPS `server` 中；示例不替换服务器现有站点，也不自行申请证书：

```nginx
client_max_body_size 150m;
location / {
    proxy_pass http://127.0.0.1:3001;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 300s;
}
```

外层代理应覆盖 `X-Forwarded-Proto`，容器 Nginx 将其传给 Java，确保图片绝对地址和会话使用公网 HTTPS 来源。`/uploads/` 经过 Java 的认证过滤器，不能直接由公网 Nginx 从磁盘提供。

## 手动检查与部署

在仓库根目录、使用有权限的服务器账号执行：

```bash
bash scripts/deploy.sh check
bash scripts/deploy.sh build
bash scripts/deploy.sh deploy
```

默认配置路径为 `/opt/rsdp-office/.env`。脚本不会 `source` 配置或打印其内容，也不会输出完整的 `docker compose config`。

本轮服务器直连 Docker Hub 超时，已从本机拉取官方 amd64 镜像并经 SSH 导入服务器缓存。缓存可供当前节点构建使用；换节点或清理基础镜像后，需要恢复官方仓库连通性或重新导入官方镜像。本轮未更改服务器全局镜像源、DNS 和代理。

2026-10-20 的实际执行记录和未验证项见 [DEPLOY-001](tasks/DEPLOY-001-jenkins-server-env.md)。流水线语法参考 [Jenkins 官方文档](https://www.jenkins.io/doc/book/pipeline/syntax/)，启动等待使用 [Compose `up --wait`](https://docs.docker.com/reference/cli/docker/compose/up/)。
