# RENDER-012 提示词精简与参考图 A/B 实验

日期：2026-11-24
分支：`experiment/render-product-agent`
状态：E 已确认为正式默认生图方案；A/B/C/D 保留为实验回测入口。

## 目标

在不删除 Office 几何事实、F/P 绑定和独立审核的前提下，比较完整技术提示词、精简视觉任务、平面图贡献和 Prompt Extend。实验只改变明确声明的变量，不恢复带 F 编号的生图参考。

## 五组定义

- A：`TECHNICAL_V10 + GUIDE_PLAN_PRODUCT + prompt_extend=false`，当前完整提示词基线。
- B：`CONCISE_V11 + GUIDE_PLAN_PRODUCT + prompt_extend=false`，相同图片，删除坐标/多边形 JSON 和重复技术描述。
- C：`CONCISE_V11 + GUIDE_PRODUCT + prompt_extend=false`，只从生图输入移除平面图；平面图仍保存并用于审核。
- D：CONCISE_V11 + GUIDE_PLAN_PRODUCT + prompt_extend=true + prompt_extend_mode=direct。
- E：TECHNICAL_V10 + GUIDE_PLAN_PRODUCT + prompt_extend=true + prompt_extend_mode=direct，用于验证完整空间/商品技术约束与 Direct 改写能否同时保留 A 的数量优势和 D 的视觉表现。

实验协议为 `selected-products-multiview-v11-prompt-ab`；普通新任务默认记录 E，显式 A 仍记录完整技术提示词且关闭 Direct。每个任务在 `snapshotJson.renderProductReferences.generationExperiment` 保存组、模式、seed、样本序号，并在 `generationImages` 保存实际输入角色、URL 和 SHA-256，不新增数据库表。

## 使用方式

在 Agent 前端画布地址增加查询参数：

```text
http://localhost:3007/canvas?...&renderExperiment=1
```

打开 AI 效果图后可选择 A/B/C/D/E 和样本序号。seed 自动为 `1100 + sampleIndex`，同序号跨组一致。每次点击仍只创建一次收费生图，没有批量生成按钮。普通入口不传实验字段时默认使用 E；实验面板初始选中 E，显式选择 A/B/C/D 时仍按对应参数执行。

## 提示词与布局事实

`RenderPromptCompiler` 仍是唯一编译入口。A 使用原有 `RenderPreviewScene + bindings + physical + spatial + products` 完整链路；B/C/D 由 `RenderVisualTaskPrompt` 生成“图片职责→唯一渲染任务→数量与布局→商品外观→风格→输出边界”。

`RenderLayoutSemantics` 在原 `description` 外增加 `TWO_ROWS/SIDE_ROW/HEAD_SEAT` 结构化字段，精简版与完整版来自同一计算结果。完整世界坐标、地面轮廓和两套投影仍保存在 `spatialControl`，并继续供确定性审核使用。

## 请求与幂等

`QwenImageClient` 在默认 E 以及显式 D/E 组发送 `prompt_extend=true` 和 `direct`；seed 非空才发送。其他模型、画幅和 negative prompt 不变。实验参数进入内容哈希，不同组或 seed 不得复用同一 `clientChangeId`；普通默认请求也保存 E 参数并纳入内容哈希。

失败不自动重试收费生图。正式实验前先手动验证当前百炼服务接受 seed/direct，不能用旧 v9 结果替代 A 组，也不能在参数被服务忽略时宣称完成控制实验。
## 生成计时

每个任务在 `snapshotJson.renderProductReferences.generationTiming` 保存请求开始、提交开始、服务受理、首次运行、最近轮询、服务完成、图片就绪或失败的时间戳，不新增数据库字段。接口返回 `totalMs`、`preparationMs`、`submitMs`、`providerMs` 和 `downloadMs`；历史任务继续保留同一耗时。页面生成中每秒刷新“已等待”，结束后显示“本次耗时”和阶段明细。

`providerMs` 包含排队、Direct 改写和生图服务处理，当前服务商响应没有把 Direct 改写耗时单独拆出，因此不能把“服务生成”解释成纯模型扩散时间。`readyAt` 在图片下载保存成功后记录；其后的自动商品/几何审核不计入生图总耗时。

## 验收

先检查硬门槛：准确家具数量、无 F/P 编号、单一完整场景、无商品板/平面图拼贴、无明显房间破坏。再按房间35%、布局30%、商品25%、写实10%评分。自动审核仅作辅助，数量仍需人工复核。

建议同序号按 A/B/C/D/E 交错执行，每组先5张；5张只用于方向判断，接近时扩大样本。若所有组都频繁增加家具，应停止继续堆提示词，转向蒙版、局部编辑、深度控制或固定三维渲染。

## 自动化验证

覆盖精简提示词无坐标 JSON、数量/两排/头尾保留、A/C/D/E 参数约束、C 组审核平面图保留、实验幂等隔离、direct/seed 请求体、布局结构化事实和实验面板 mock 请求。测试不访问真实 VL 或收费生图。
实际执行：后端全量347项，0失败、0错误、3跳过；前端生产构建与本地Edge全mock回归通过；`git diff --check`通过。未调用真实VL或收费生图，A/B/C/D/E实际效果待手动对照。
