# DEPLOY-003 HTTP 下图纸上传 UUID 兼容性修复

状态：已完成（公网复测通过）
更新日期：2026-10-20
基线：main `956c411`；HTTP 入口见 [DEPLOY-002](DEPLOY-002-public-http.md)。

## 原因与范围

用户公网截图显示文件选择失败 `crypto.randomUUID is not a function`。在真实公网 IP HTTP 环境复现：`isSecureContext=false`，`randomUUID` 不存在，而 `getRandomValues` 可用。[Web Crypto 规范](https://www.w3.org/TR/webcrypto/#crypto-interface) 将 randomUUID 标为 SecureContext，getRandomValues 没有此限制。此前只检查页面加载和登录，没有覆盖文件选择，不能据此认为上传链路已经验证。

统一新增 `createUuid`：支持时沿用原生 randomUUID；HTTP 下使用 getRandomValues 生成标准 v4 UUID，保留随机盐来源、版本和 variant，不使用 Math.random。替换图纸选择、历史任务图纸绑定、模板捕获/保存及效果预览请求中的五处调用。既有 ID 格式、数据关联和幂等重试机制保持。

截图中的 401 与 UUID 报错分别核对：服务器日志显示用户最新登录和后续 me 请求均为 200；更改密码已撤销旧会话，匿名 me 返回 401 属于认证流程，不作为 UUID 的原因。

## 验证计划与记录

- 修复前公网真实浏览器已复现文件选择错误。测试拦截全部业务写请求，没有修改用户云端设计或触发模型任务。
- `pnpm test:uuid` 已通过：原生路径、HTTP 回退、RFC v4 格式/版本/variant、独立 ID、无随机源时明确失败。
- `pnpm build` 已通过。
- 已在图纸进入、模板和效果预览现有 UI 回归中通过 `addInitScript` 禁用 `Crypto.prototype.randomUUID`，覆盖 HTTP 下同样的接口缺失，并为匿名拦截补充 `/api/auth/me` mock。2026-10-20 复跑实际结果：`pnpm test:uuid`、`pnpm build`、`pnpm e2e:drawing-entry`、`pnpm e2e:templates`、`pnpm e2e:render-preview` 全部通过，无 pageerror。API 使用 mock，不能替代真实识别/生图验收。
- 后续仅发布前端容器，保留 Java、CAD、PostgreSQL、用户数据和当前密码。真实公网复测文件选择，拦截测试设计保存，避免覆盖用户工作区。

## 发布与复测记录（2026-10-20）

- 本机 Docker（linux/arm64 宿主）以 `docker buildx build --platform linux/amd64` 仅构建前端镜像 `rsdp-office-frontend:956c41117698-httpuuid`，`docker save | gzip | ssh docker load` 导入服务器；未经过 Jenkins，不记作 SCM 发布。
- 服务器以 `IMAGE_TAG=956c41117698-httpuuid docker compose ... -f release/956c41117698/compose.deploy.yaml up -d --no-deps --wait frontend` 仅重建前端容器。PostgreSQL、CAD、Java 容器与镜像保持 `956c41117698` 未动，无数据库变更、无备份需求；`release/current-image-tag` 仍指 `956c41117698`，下次完整 `deploy.sh deploy` 会统一回正式标签。
- 替换后四容器 healthy，本机 `127.0.0.1:3001/healthz` 200、匿名 `/api/auth/me` 401。
- 公网复测 `frontend/scripts/e2e-public-http-uuid.mjs`（不入 package.json，需 `RSDP_ADMIN_PASSWORD` 环境变量，密码从服务器 `.env` 读取、不落地）：真实 HTTP 入口确认 `isSecureContext=false`、`randomUUID` 缺失而 `getRandomValues` 可用；真实 admin 登录 me 200；文件选择经 HTTP 回退生成标准 v4 UUID，无"上传图纸失败"、无 pageerror；全程未调用识别接口、未保存设计，测试图纸记录仅写入浏览器本地并已清理；退出后 me 401。截图 `output/deploy/public-uuid-retest.png` 不含凭据，不入库。
- 复测使用的会话已退出吊销；服务器 `.env`、账号密码、其他三服务与 Nginx 配置未改动。
- 首轮复测曾因脚本用 `context.request` 发送相对地址退出请求而未实际退出（断言 me 401 失败）；改为页面内 `fetch` 后通过，与修复本身无关。
- 源码提交状态见本节下方 git 记录；推送后 Jenkins SCM 轮询会触发常规构建（DEPLOY 默认 false），不算本次热修复的发布动作。
