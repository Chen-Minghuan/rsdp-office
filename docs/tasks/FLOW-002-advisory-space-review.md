# FLOW-002 空间复核改为提示

状态：已实现并通过专项验证
更新日期：2026-10-15
依据：用户最新要求，不必逐个确认空间，系统提示需复核，用户按需修改。

## 范围

- 移除实际画布的逐空间确认按钮及全体确认门槛，改为“进入家具排版”。进入排版不自动填写人工确认记录。
- 识别候选、边界重叠等提示需复核，不拦截排版；编辑后仍更新几何与下游失效状态。
- 未知比例、无效/自交/退化轮廓、缺失或重复 ID 等无法可靠计算的问题仍拦截，并说明原因。保留图纸比例确认和家具排版确认。
- 前端地址直达、刷新、排版操作与后端画布保存/恢复使用同一新规则。
- 不重写尚未接入工作台的 DesignScene 历史确认 API；旧确认字段保留历史含义，不充当新门槛。

## 验证

| 检查 | 实际结果与范围 |
| --- | --- |
| `pnpm build`（frontend） | 通过；未运行独立 lint/typecheck。 |
| `mvn -f backend/pom.xml test` | 50 项通过，0 失败/错误/跳过。新增/调整阶段策略测试：confirmed=false/缺失、重叠仍可用；未知单位、自交、退化/缺失几何、重复 ID、无效所选空间拒绝。 |
| `pnpm e2e:space-review` | 通过；模拟 API。无逐个确认按钮、重叠提示不拦截、直接进入排版不改人工确认记录、刷新与全新地址直达、未知比例直达拒绝；保留毫米编辑/撤销、图层、异形、CAD 方向和移动回归。 |
| `pnpm e2e:dimensions` | 通过；模拟 API。应用图纸比例后可直接排版，confirmed 仍为 false；比例读取/单位重验/撤销/过期响应与编辑保留回归通过。 |
| `MOCK_API=1 BASE_URL=http://localhost:3000 OUT_DIR=/tmp/rsdp-flow2-smoke pnpm e2e:smoke` | 通过；当前开发前端、全部接口模拟，直接进入排版后仍可确认家具排版、加入方案及修改清单数量。 |
| 真实 8082 接口 + PostgreSQL | 一次性 `FLOW2-QA-4b504c10` 项目保存两间 confirmed=false 且重叠的毫米空间为 design，GET 恢复仍为 design 且未改写 confirmed；未知比例和自交保存被拒绝。随后删除测试画布与项目，未改变用户项目。当前 8082 已重载新代码。 |
| 差异及文档 | `git diff --check` 通过；12 份相关文档的 102 个本地链接有效，当前需求与历史确认契约已区分。 |

实现复用 `zoneLayoutProblem` / store `canEnterLayout` 和后端 `OfficeCanvasReviewPolicy.requireLayoutReady`；复核提示由 `zoneReviewMessage` 单独计算。历史 `confirmed` 数据不被批量填真，几何/比例修改仍使旧布局确认失效。

界面证据（模拟数据）：[重叠仅提示且可继续排版](../../frontend/screenshots/space-review/06-advisory-overlap.png)、[比例应用后直接排版](../../frontend/screenshots/dimension-calibration/02-confirmed-restored.png)。本次未调用真实模型重识别；未运行 CAD 金样本或完整识别质量验收。完整工作流脚本的既有商品详情选择器问题未在本轮处理，未声称全工作流通过。
