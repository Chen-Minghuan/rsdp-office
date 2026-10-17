# RECOG-015 移除未使用的 SegFormer 接入

日期：2026-10-17。状态：清理完成并通过相关验证。范围：用户要求删除当前未使用的 SegFormer。

## 变更与边界

- 删除独立 Python 服务、ONNX 依赖、模型目录占位、Dockerfile 和服务说明。
- 删除 Java 客户端、分割配置属性、调用与结果可用性字段、模型 mask 专用补线/膨胀代码及专用测试。
- 删除 Docker Compose 服务/依赖/环境变量、application.yml 与 .env.example 对应配置。
- Qwen 初始轮廓、独立房名定位、尺寸校准和 Java 墙网核查保留；现直接调用原 Java wallMask 路径。
- 诊断字段改为 contourRefinement.wallMaskSource=STRUCTURAL_PIXEL_MASK，首阶段为 STRUCTURAL_WALL_MASK；不再给出“未配置分割模型”提示。历史分析 JSON 不改写，画布接口没有使用删除字段。
- 更新架构、配置与进度；旧 RECOG-012 只留撤销记录，移除已失效的启动说明。

## 验证

- `mvn -q -f backend/pom.xml clean test`：152 项，149 通过、3 项可选数据库测试跳过、0 失败/错误。clean 清除已删除类的旧编译产物。
- 既有两份 8.png 真实响应回放均保留 22 命名候选；原 Java 墙线/同名房间/开放区测试通过。保留现有流程回归，不新增准确率结论。
- `docker compose -f backend/docker-compose.yml config --quiet`：通过。未发现对应运行或停止的分割容器，无需清理容器。
- 主代码、部署配置和环境模板不再引用分割客户端/模型；模型专用辅助方法和独立目录已删除。
- 相关文档相对链接与 `git diff --check`：通过。
- 本地后端确认无正在识别的任务后已重启，`/actuator/health` 返回 UP；只读查询既有 FPA-6D94153D 仍为 21 空间。
- 未新增真实模型调用，未改写识别记录或设计稿；启动沿用既有初始化流程，无新增数据库迁移。本轮未改前端/CAD 产品代码，未运行前端构建、UI 或 CAD 测试。
