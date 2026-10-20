# DEPLOY-004 域名子路径入口 rooom.vip/office/

状态：已实现并通过强制解析公网复测；DNS 旧 A 记录清理后生效
更新日期：2026-10-20
基线：main `b8f3288`，构建镜像 `rsdp-office-frontend-office:b8f3288`；入口见 [DEPLOY-002](DEPLOY-002-public-http.md)。

## 目标与方案

用户要求通过 `http://rooom.vip/office/` 访问，同时保留 IP 入口。静态 SPA 的基座在构建期固化，双基座需要两份产物；采用第二容器 `frontend-office`（`NUXT_APP_BASE_URL=/office/`、API 前缀 `/office`，127.0.0.1:3002），原 8081 根路径容器与应用、数据库不变。

服务器 80 端口已有 rooom.vip 静态站（`/var/www/html/show`）。仅在现有 server 块内追加 `/office/` 代理段（模板 [nginx-office-subpath.conf](../../deploy/nginx-office-subpath.conf)），原站点路径不拦截。UFW 与安全组此前已放行 80。

## 实现

- `frontend/Dockerfile`：`NUXT_APP_BASE_URL` / `VITE_API_BASE_URL` / `NUXT_PUBLIC_API_BASE_URL` 构建参数，默认与原行为一致；`nuxt.config.ts` 声明 `runtimeConfig.public.apiBaseUrl` 供 spatial 场景 API 读取。
- `compose.deploy.yaml` 新增 `frontend-office` 服务；`scripts/deploy.sh` 镜像清单同步，后续完整部署自动覆盖。
- 代理段：`/office/` 去前缀转发 3002；`/office/login` 直发 SPA 入口避免守卫自跳转（同 8081 的 `/login` 处理）；`proxy_redirect` 为容器相对 301 补前缀；`proxy_cookie_flags RSDP_SESSION nosecure`；`X-Forwarded-Prefix /office` 使后端新识别写入的资源地址落在 `/office/` 下。
- `SpatialCanvas` 底图原硬编码 `/uploads` 改 `withApiBase`；新增 `sameOriginAssetUrl`：识别按当时入口写入的绝对资源 URL 归一为路径并加当前入口前缀（`/uploads` 需会话 Cookie，跨入口绝对地址会 401），SpaceReview 与 CanvasBoard 底图接入。
- e2e:space-review 补 `/api/auth/me` mock：全局登录守卫后该脚本既有缺口，与本次改动无关。

## 验证记录

- 本地：`/office/` 基座 generate 产物资源与 API 前缀检查；默认构建 `pnpm build`、`e2e:drawing-entry`、`e2e:space-review` 通过。
- 服务器：main `b8f3288` 克隆构建 `frontend-office:b8f3288`；仅新建该容器，其余四容器不动；站点配置改动前备份于 `backups/nginx-rooom.vip-20261020-pre-office`，`nginx -t` 与 reload 通过。
- 强制 Host（公网 IP + Host rooom.vip）：`/office/` 200 且资源前缀正确、`/office/login` 200、`/office/login/` 302 规范化、`/office/api/auth/me` 401、`/office/healthz` 200、`/office/canvas` 301 到 `/office/canvas/`、裸 `/office` 301、原站点 `/` 200、IP 8081 200。
- 公网复测 `frontend/scripts/e2e-public-office-subpath.mjs`（`--host-resolver-rules` 强制解析，不入 package.json）：匿名落到 `/office/login`；真实登录 me 200；文件选择生成 v4 UUID（同为 HTTP 非安全上下文）；直达 `/office/canvas` 加载用户云端 9.png 工作区与 16 空间；旧数据底图经同源化 200（截图可见底图渲染）；原站点根路径非本应用；未触发识别；退出 401。截图 `output/deploy/public-office-subpath.png` 不含凭据。
- 8081 入口回归 `e2e-public-http-uuid.mjs` 再次通过；8081 前端仍为 `956c41117698-httpuuid`，同源化改动随下次完整部署生效，当前版本底图本就可同源访问。
- 复测会话均已退出吊销；触发测试未写云端设计、未调用识别/生图。

## 边界与待办

- DNS：`rooom.vip` 仍有 `120.77.15.115` 与 `101.200.81.123` 两条 A 记录，`www` 仅旧地址。用户需在云解析控制台删除旧 A 记录；生效前域名可能落到旧服务器。本轮复测均为强制解析，不代表 DNS 已稳定。
- 识别/生图业务验收不扩大：底图地址修复解决的是跨入口访问，不涉及识别精度。
- 8081 前端与后端完整对齐（含同源化、LAYOUT-019/CAT-006 合入）待下次 `deploy.sh deploy`；`release/current-image-tag` 仍为 `956c41117698`。
- HTTPS、证书申请仍未进行；两个 HTTP 入口切换 HTTPS 时取消 Cookie nosecure 例外。
