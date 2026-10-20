# DEPLOY-001 Jenkins 流水线与服务器环境配置

状态：已编码待验证（文件、服务器配置、手工构建和实际 Jenkins 只构建流程已验证；正式发布待验证）
更新日期：2026-10-20
基线：`617bfd8`；交付提交说明：`feat(ci): add Jenkins pipeline and server deployment configuration`

## 目标与范围

用户要求新增 Jenkinsfile，并将 `.env` 配置放到已连接的云服务器。提供可验证的容器构建/部署流程，保留现有站点与本地开发入口；本次不默认触发正式部署、Git 推送或切换公网域名。

- [x] 新增根目录 Jenkinsfile，与服务器现有 `main` / `Jenkinsfile` 的任务配置一致。
- [x] 提供前端 Dockerfile、同源 API/上传代理和独立生产 Compose。
- [x] 在服务器创建 `/opt/rsdp-office/.env`，迁移已有百炼配置，随机生成数据库/会话/应用初始密码；只记录键的存在，不记录值。
- [x] 以 Jenkins 用户运行预检，确认文件可读、目录可写、Docker 可用和 Compose 可解析。
- [x] 完成实际镜像构建并记录结果。
- [x] 修复 Jenkins 插件依赖，实际 SCM 流水线以 DEPLOY=false 构建通过。
- [x] 更新验证结论与后续操作。

## 实现

生产数据绑定 `/opt/rsdp-office/data`，完整保留 uploads；Compose 项目名 `rsdp-office` 与本地 `backend` 隔离。仅前端对宿主机回环地址开放 3001，后端认证和 Secure Cookie 固定启用。部署脚本在已有数据库更新前保存备份，停止旧应用后在事务中执行基础/Spatial 迁移，再等待健康检查。

发现方案页图片地址使用 `||`，会把同源构建的空 API 地址替换为 localhost；改为与其他 API 客户端一致的 `??`，让公网图片使用同源路径。

前端容器代理核对发现默认绝对目录跳转丢失映射端口且可能退回 HTTP；已设置 `absolute_redirect off`，使 HTTPS 外层代理后的路径跳转保留来源。

后端原 Dockerfile 在服务器的 Alpine 软件源下载步骤持续等待；构建阶段改用官方 Maven 3.9.11 / Java 21 镜像，运行阶段使用基础镜像已有的 wget 做健康检查，不再构建时安装 Maven/curl。

预检最初直接检查 PostgreSQL 宿主目录，Jenkins 因数据目录权限拒绝访问。已移除此要求；仅在未配置引导密码时，通过已有 PostgreSQL 服务核验有效账号，不扩大数据目录权限。

服务器现有 Jenkins 任务读取 Git `main`。本轮文件已提交为 `956c411`，后续核对远端 `main` 已指向该提交。此前手工构建副本 `release/prepared-20261020` 不作为正式 release。

## 验证记录

- `pnpm build`：通过。
- 本地首次 `mvn -f backend/pom.xml test -q` 的报告汇总为 168 项，包含旧 `target` 中已删除的 `DemoAuthServiceTest` 两项；已发现并执行 `clean test` 重新核对，不以旧产物扩大当前源码测试数。
- 本地 `mvn -f backend/pom.xml clean test -q`：166 项，163 通过、3 项因缺少数据库集成环境跳过，与 Linux 镜像测试结果一致。
- `VITE_AUTH_ENABLED=1 VITE_API_BASE_URL= pnpm generate`：通过，生成静态 SPA 入口。
- 非敏感占位配置的 Compose 解析：通过；空配置明确拒绝缺失密钥。
- 服务器 `sudo -u jenkins ... scripts/deploy.sh check`：通过；`.env` 为 `root:jenkins 0640`。
- 服务器镜像构建：首次因 Docker Hub 连接超时失败；本机拉取官方 amd64 基础镜像，再经 SSH `docker load` 到服务器后重试。后端构建改为官方 Maven 镜像后，前端、Java 和 CAD 三个 `preflight-20261020` 镜像全部成功。没有改全局镜像源、DNS 或代理。
- 服务器前端容器核对：Nginx 语法、首页/画布/登录/方案路由、映射端口保持、API 401 状态转发、uploads 代理和 HTTPS 转发头均通过。后端为隔离 Node stub，不代表真实登录/上传业务验收；临时容器及网络已删除。
- 服务器 Java `test` 镜像阶段：166 项，163 通过、3 项缺少数据库集成环境跳过；与 Jenkinsfile 相同的 `docker create` / `docker cp` 报告提取通过。
- Jenkins 内置声明式校验端点未认证返回 `403`；未修改 Jenkins 权限或创建令牌，未声称该校验通过。
- 初次交付时未运行实际 Jenkins SCM 流水线；用户推送后已启动验证，详情见下节。正式数据库迁移、HTTPS 登录与业务验收仍未执行。
- Bash 语法、Compose 必填项/解析、168 个相关文档相对链接、修改文件空白与 `git diff --check`：通过；8 个服务器部署源码文件与本地 SHA256 一致。

## 后续

后续用户已授权实际发布，迁移、健康检查及公网 HTTP 验证结果见 [DEPLOY-002](DEPLOY-002-public-http.md)。下述未部署描述保留为初次交付时的历史结论。

本次用户要求的 Jenkinsfile 和服务器 `.env` 已交付，手工构建与代理核对通过，远程 main 的提交 `956c411` 已通过实际 Jenkins 第 3 次构建。配置 HTTPS 入口后，按需勾选 `DEPLOY` 发布。服务器现有站点尚未切换到本项目；备份、迁移和正式启动分支尚未实跑，不扩大为发布验收。

## Jenkins 启动故障修复（2026-10-20）

- 用户提供的第 1 次构建报 `No flow definition, cannot run`；检查时第 2 次已从 Git 获取 Jenkinsfile，实际报错为 `No such DSL method 'pipeline'`。
- 检查声明式插件的 48 项必需依赖链，发现 `joda-time-api` 缺失，仅有未完成的 `.jpi.tmp`。此前插件热加载时缺少依赖，后续补装其他依赖并未使失败插件全部恢复。原先仅检查插件文件存在，未覆盖运行时激活状态。
- 从 Jenkins 官方更新中心安装 `joda-time-api 2.15.0-208.v6b_084e7a_d9f0`，按官方更新元数据核验 SHA256、ZIP 完整性和插件标识。已备份任务 XML，确认没有进行中的构建后重启 Jenkins。
- 一次性启动校验脚本在修正换行转义和构建调度 API 参数后，确认声明式插件、Model API、Basic Steps、Joda 插件均 active，任务定义为 `CpsScmFlowDefinition`；触发 `DEPLOY=false` 的第 3 次构建，脚本已自行删除。未修改 Jenkinsfile、登录权限、沙箱或正式应用。
- 第 3 次实际 Jenkins 构建（main `956c411`）为 `SUCCESS`：预检、Java 测试报告提取与 JUnit 发布、三个镜像构建均通过。当前提交的测试为 180 项，177 通过、3 项数据库集成环境未配置而跳过；与前述手工核对基线的 166 项分别记录。三个镜像标签为 `956c41117698`。
- `DEPLOY=false`，Deploy 阶段明确 skipped；未执行正式数据库迁移或服务切换。原有 `H/30 * * * *` SCM 轮询保留，任务参数默认值仍为 false。任务配置备份保存在 `/opt/rsdp-office/backups/jenkins-config-20261020T160113.xml`。
