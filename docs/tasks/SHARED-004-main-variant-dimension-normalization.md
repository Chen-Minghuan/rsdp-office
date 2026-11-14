# SHARED-004 主项目变体尺寸格式归一化

状态：已修复并完成本地真实推荐验证

日期：2026-11-14

## 问题与证据

办公区已确认的职员 L 桌为 `1400 × 1400 mm`，同规格 12 件，但款式推荐返回 0 个候选。真实推荐响应显示 `eligibleVariantCount=263`、`missingDimensions=263`。

主项目原始接口则显示：253 个产品的 263 个有效变体中，261 个包含尺寸，且存在 `1400 × 1400`、`1400 × 1200` 等可容纳商品。因此问题不在商品状态、报价、旋转开关或排版确认。

## 根因

主项目尺寸 JSON 使用短键：

```json
{"w":1400,"d":1200,"h":750,"unit":"mm"}
```

`RsdpCatalogGateway.applyDimensions()` 复用的 `OfficeProductMatching.dimensions()` 只识别 `widthMm/depthMm/heightMm` 或 `widthCm/depthCm/heightCm`。短键未被转换，导致 office 的 `widthMm/depthMm` 全为空，推荐服务将 263 个变体全部跳过。既有单测只覆盖长键，未覆盖主项目真实格式。

## 修复

在 `RsdpCatalogGateway` 增加主项目尺寸归一化：

- `w/d/h` 短键转换为匹配器可读取的长键；主项目短键按毫米契约解释，缺省 `unit` 的旧记录同样按毫米处理。
- `unit=cm` 转换为厘米长键，由既有匹配器换算为毫米。
- 未知单位仍拒绝，不根据商品名称或 `sizeText` 猜测尺寸。
- 既有 `widthMm/widthCm` 长键优先，保持原兼容行为；混合长短键不做隐式拼接。
- 推荐仍严格比较模块宽深，价格仍只影响能否加入方案，不改变候选过滤规则。

## 测试与真实验证

- `mvn -f backend/pom.xml -Dtest=RsdpCatalogGatewayTest test`：3 项通过。覆盖既有长键、真实短键毫米、缺省 `unit` 旧记录、厘米换算和非法单位。
- `mvn -f backend/pom.xml test`：265 运行、0 失败、0 错误、3 跳过，`BUILD SUCCESS`。
- 停止原 IDEA 启动的 Office 进程后，使用 `mvn -f backend/pom.xml spring-boot:run` 启动更新后的 `8082` 服务；主项目 `8081` 未重启。
- 第一次真实读取时，主项目并发变体请求出现 `Connection refused`，随后主项目接口恢复；未把该次失败计为修复失败或通过。
- 重试同一真实推荐请求：办公区画布修订 1173、模块 `1400 × 1400 mm`、12 件全部确认；候选由 0 恢复为 **146 个**，`eligibleVariantCount=263`、`missingDimensions=2`、有效尺寸变体 261 个。临时诊断会话已吊销。
- 未修改商品数据、空间几何或真实购物车；未执行前端构建（本次仅后端和文档变化），未部署生产环境，未提交代码。

## 使用

当前本地后端已运行修复版本。页面点击「重新匹配」即可读取候选；网关缓存为 30 秒，旧缓存也会自然过期。生产环境仍需另行构建部署并复测。

## 并发与瞬时网络失败补充修复（2026-11-14）

用户在真实页面继续遇到“主项目数据服务不可用”。日志证实两个后端均在运行，但同一批大量变体 GET 在几十毫秒内出现 `Connection refused`；原实现对约 253 个产品同时提交请求，任一失败都会使整个目录失败，且仅对 401 重试。

本轮新增：

- `RsdpCatalogGateway` 保留虚拟线程，以信号量把每批变体请求限制为最多 **8 个并发**；其余请求排队，仍读取全部产品和变体，不截断目录。
- `RsdpClient.get()` 对幂等 GET 的 `ResourceAccessException` 最多执行 3 次请求（首次 + 两次重试），退避 100ms/200ms；401 仍只重登一次，HTTP/业务错误不重试，持续失败仍明确报错，不静默漏商品。
- 新增 `RsdpClientTest` 覆盖前两次失败第三次成功、持续失败仅三次、业务错误不重试；`RsdpCatalogGatewayTest` 用 24 个产品实测最大并发不超过 8 且全部请求完成。
- 专项 `mvn -f backend/pom.xml -Dtest=RsdpCatalogGatewayTest,RsdpClientTest test`：7 项通过。
- 全量 `mvn -f backend/pom.xml test`：**269 运行、0 失败、0 错误、3 跳过**。
- 重启 Office `8082` 后连续三轮真实 API 验证均保持：263 个有效变体、261 个可靠尺寸、146 个适配候选、仅 2 个原缺尺寸变体排除。新进程日志未出现连接拒绝或重试记录。临时诊断会话均已吊销。
- 未修改主项目启动配置，未改商品/画布/购物车；未部署生产环境，代码未提交。
