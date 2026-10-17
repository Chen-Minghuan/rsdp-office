# RECOG-011 PNG 墙网拓扑识别

日期：2026-10-17。状态：第一阶段历史实现保留；当前采用混合候选与 Java 栅格墙线规则，跨图纸真实质量验收待完成。

2026-10-17 后续修正：8.png 暴露 labels-only 严重丢失空间，当前主请求已按 [RECOG-014](RECOG-014-space-completeness-recovery.md) 恢复初始候选轮廓，墙网作为保守核查；下文记录第一阶段历史实现，不再代表主请求当前目标。

## 目标

PNG 识别不再把 Qwen 输出的房间 polygon 当作边界真值。Qwen 只输出可见房名、功能类型、语义置信度和尺寸文字；房间边界由原图墙体候选、墙网、有限门洞闭合和连通区域多边形化生成。

## 本轮实现

- `QwenVisionClient` 的主提示改为 `labels` 语义输出，禁止输出 polygon、像素坐标、面积和宽深。
- 兼容旧供应商响应：旧响应仍可携带 `rooms[].polygon`，迁移期间不会被破坏。
- 语义响应转换为带空 polygon 的内部候选；空 polygon 表示等待墙网生成，不表示矩形或模型猜测。
- `RasterWallContours` 以独立房名框为种子，从结构墙掩码泛洪到封闭区域；轮廓阶段记录 `STRUCTURAL_WALL_MASK`、`WALL_GRAPH`、`DOOR_CLOSURE`、`POLYGONIZE`。
- 墙线校正的二次缩放从 2048 提升到标准图最长边 3072，避免细双线在第二次缩放时丢失。
- 无法闭合的语义候选进入 `unresolvedSpaces`，不伪造房间范围；原始模型响应和语义转换结果分别保存。

## 当前边界

当前 `STRUCTURAL_WALL_MASK` 由 Java 栅格结构线规则生成，包含长线、双线隔墙、柱块和有限门扇补线。未启用的分割服务已按 [RECOG-015](RECOG-015-remove-unused-segformer.md) 删除；真实跨图纸质量仍待验收。

开放办公区、吧区和共享走道没有连续实体墙时，自动结果仍只能标为低置信度估计，必须保留人工功能分界。

## 验证

- `mvn -f backend/pom.xml -Dtest=QwenVisionClientTest,RasterWallContoursTest test`：29 项通过。
- 新增语义-only 模型响应回归：确认 Qwen 不提供 polygon 时，封闭办公室仍可由房名锚点和墙网生成轮廓。
- 本轮未调用真实墙体分割网络，未完成跨样本 PNG/PDF 质量验收。
