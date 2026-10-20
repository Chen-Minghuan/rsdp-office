# AUTH-002 登录安全加固与会话管理

## 目标

参考鱼皮用户中心的做法补齐登录安全与体验：失败限流防暴力破解、图形验证码、记住我、滑动续期、退出即吊销，会话落库支持多实例共享（参考鱼皮分布式 Session 的思路，用数据库代替 Redis）。

## 实现

### 失败限流与验证码（P0/P2）

- `LoginAttemptGuard`：按"账号+IP"计数，连续失败 2 次后登录必须携带图形验证码，连续失败 5 次锁定 15 分钟（`rsdp.auth.captcha-after-failures` / `lock-after-failures` / `lock-seconds` 可调）。
- `CaptchaService`：4 位随机字符 PNG 干扰线验证码，一次性、5 分钟过期；`GET /api/auth/captcha` 返回图片并通过 HttpOnly Cookie `RSDP_CAPTCHA` 关联。
- 计数与验证码保存在单实例内存，重启清零；多实例部署时各实例独立计数（已记录为已知边界）。

### 数据库会话（P2/P3）

- 新表 `office_user_session`（迁移 `db/migrations/20261020_user_session.sql`，`schema.sql` 同步）：会话 ID、用户、是否记住我、过期时间、最后使用时间、创建 IP。
- 登录签发 HMAC 签名令牌（`sessionId|过期时间`）写入 HttpOnly Cookie `RSDP_SESSION`；校验时除签名外必须命中数据库会话记录且用户 active。
- 记住我：勾选后会话 30 天且 Cookie 持久化；不勾选为 12 小时、浏览器会话 Cookie（无 Max-Age）。
- 滑动续期：剩余有效期不足一半时自动延长并重发 Cookie（`AuthFilter` 与 `/api/auth/me` 都会处理）。
- 退出登录删除服务端会话记录，旧令牌立即失效（解决 AUTH-001 无状态令牌无法吊销的问题）。
- 会话状态在数据库，多实例部署天然共享，无需引入 Redis。

### 前端

- 登录页新增"记住我"勾选与验证码区域（失败 2 次后自动出现，点击图片刷新）。
- `useAuth.login` 支持 `rememberMe` / `captchaCode`，失败响应携带 `captchaRequired` 时前端自动展示验证码。

## 验证

- `mvn -f backend/pom.xml test`：160 通过 / 3 跳过，含新增 `AuthServiceTest`（吊销、续期）、`LoginAttemptGuardTest`、`CaptchaServiceTest`。
- `pnpm build` 通过。
- 真实环境 Playwright（一次性脚本，未入库）：记住我 Cookie 带 30 天 Max-Age；普通登录无 Max-Age；退出后旧令牌立即 401；第 2 次失败出现验证码且图片可加载；错误验证码拒绝；连续失败 5 次锁定提示；锁定不影响其他账号。

## 边界与待办

- 限流计数和验证码为单实例内存，多实例时需换共享存储（Redis）才能全局一致。
- 会话表暂不清理过期记录，后续可加定时清理任务。
- 公网部署仍需 HTTPS 与 `RSDP_AUTH_COOKIE_SECURE=true`；注册功能用户明确暂不做。
