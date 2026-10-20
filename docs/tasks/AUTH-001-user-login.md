# AUTH-001 用户登录与认证

## 目标

提供真正的数据库用户登录闭环。首期只需要一个账号，但用户存储、密码哈希、状态和角色已经独立于演示配置，后续可以增加用户管理和项目归属。

## 数据模型

`office_user` 保存稳定用户 ID、唯一账号、PBKDF2 加盐密码哈希、显示名、角色、状态、最后登录时间和创建/更新时间。数据库不保存明文密码；登录会话只保存签名后的用户 ID 和过期时间，浏览器 Cookie 为 HttpOnly、SameSite=Lax。

## 首个用户初始化

部署首次启动时设置：

```bash
RSDP_AUTH_ENABLED=true
RSDP_AUTH_SECRET='至少 32 位随机字符串'
RSDP_BOOTSTRAP_USERNAME=admin
RSDP_BOOTSTRAP_PASSWORD='首次创建用户的密码'
RSDP_AUTH_COOKIE_SECURE=true
```

应用启动完成后，如果该账号不存在，就将密码 PBKDF2 加盐哈希后写入 `office_user`。后续启动不会覆盖已有用户密码；初始化完成后可以移除 `RSDP_BOOTSTRAP_PASSWORD`。

前端同源部署时设置 `VITE_AUTH_ENABLED=1` 和 `VITE_API_BASE_URL=` 后重新构建。`VITE_API_BASE_URL` 为空表示浏览器通过当前域名访问 `/api`；不能在公网构建中保留 `http://localhost:8082`。

## 接口与行为

- `POST /api/auth/login` 根据 `office_user` 校验账号、密码和 active 状态。
- `GET /api/auth/me` 返回当前用户基本资料。
- `POST /api/auth/logout` 清理会话。
- 认证开启时，所有 `/api/v1/**` 和 `/uploads/**` 请求需要有效会话；健康检查和认证接口保留公开。
- `CurrentUserContext` 在请求期间使用登录账号，商品、识别和修订记录不再以默认 `system` 作为当前登录用户。

## 部署边界

后端和数据库端口仍只能绑定本机，由 HTTPS 反向代理对外提供前端和 API。当前首期只提供账号登录，不代表已经完成多租户项目隔离或角色权限管理。

## 验证

- 后端 `mvn -f backend/pom.xml test`。
- 前端 `pnpm build`。
- 认证开启后核对用户表自动创建、未登录 API 返回 401、错误密码拒绝、正确密码可刷新恢复、禁用用户无法登录、退出后 API 再次返回 401。

## 本地验证记录（2026-10-20）

本轮补齐两处缺口后完成本地真实环境核对：

- 修复：`WebConfig` 的 CORS 映射原来只覆盖 `/api/v1/**`，补 `/api/auth/**`，否则前端跨源开发时认证接口被浏览器拦截。
- 修复：`app.vue` 在 `onMounted` 里 `navigateTo` 跳转后登录页不渲染，改为全局路由中间件 `frontend/app/middleware/auth.global.ts` 在导航前完成会话校验，未登录直接进入 `/login`。

实际执行：

- `pnpm build` 通过；`mvn -f backend/pom.xml test` 154 通过 / 3 跳过。
- 本地真实集成：前端 dev(:3000, `VITE_AUTH_ENABLED=1`) + 后端(:8082, `RSDP_AUTH_ENABLED=true` + 引导账号) + docker PostgreSQL。启动自动创建 `office_user` 表与引导用户；匿名访问 `/api/auth/me`、`/api/v1/**`、`/uploads/**` 均 401；错误密码 401；数据库置为 disabled 后登录 401、恢复 active 后 200；登录返回 HttpOnly 会话、刷新保持；退出后 `/api/auth/me` 回到 401。
- Playwright 真实接口端到端（一次性脚本，未入库）8 项通过：首页跳登录、表单渲染、错误密码提示、登录进首页、会话有效、顶栏退出按钮、退出后 401、再次访问重跳登录。

仍待执行：公网 HTTPS 反向代理部署与正式环境数据库迁移；多用户管理和角色权限不在首期范围；上述本地核对不等于正式业务验收。
