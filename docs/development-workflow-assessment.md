# vibe-coding-cn 对 RSDP 办公项目的适用性评估

本文保留首次调研时的现状快照和建议。后续已按用户明确的业务目标建立 [项目开发文档](README.md)、[项目规则](../AGENTS.md) 和 [进度表](status.md)；当前规则及进度以这些文档为准。下文“尚未建立”和“建议新增”描述调研当时的状态。

调研日期：2026-10-13。外部资料以当日读取的 `develop` 分支为准，未固定上游提交版本。
本项目代码基线：`3f8f1eb`。本次核对源码、文档和测试入口，没有启动服务或执行功能测试。
本文给出采用建议和模板；列出的 AGENTS、进度表、CI 等均为建议，未因本次调研自动启用。

## 1. 这个链接是什么

[vibe-coding-cn](https://github.com/tradecatlabs/vibe-coding-cn) 是中文 AI 辅助开发教程与资源仓库，
覆盖需求表达、任务拆解、上下文管理、工程验证及可复用技能。
它自身以文档和辅助工具为主，其检查命令主要服务于知识库维护。
对 RSDP 办公的价值是提供组织开发工作的参考方法，不能直接提供办公设计、CAD 解析或商品业务能力。

建议优先阅读：

| 上游入口 | 本项目的使用方式 |
| --- | --- |
| [开发流程](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/docs/gongfa/development-process.md) | 提炼任务从需求到验证、文档更新的顺序 |
| [工程实践与项目架构](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/docs/gongfa/project-architecture-template.md) | 明确前端、Java 后端、CAD 服务的职责和接口 |
| [质量门禁与常见坑](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/docs/gongfa/quality-gates-and-pitfalls.md) | 为关键行为设置可执行检查，按本项目实际情况筛选规则 |
| [状态转移闭环](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/docs/gongfa/vibe-coding-state-transition.md) | 用目标、现状和验收证据描述进度 |
| [仓库 AGENTS](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/AGENTS.md) | 参考项目背景、边界、命令、文档同步的组织方式 |
| [PR 模板](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/.github/PULL_REQUEST_TEMPLATE.md) | 每次变更记录问题、结果、验证和文档更新 |
| [Skills 入口](https://github.com/tradecatlabs/vibe-coding-cn/blob/develop/skills/README.md) | 高频任务稳定后，再判断是否需要专用技能 |

上述 AGENTS 和流程中的命令、分支、维护要求属于上游仓库，本文没有将它们作为本项目执行指令。

## 2. 本项目现状与缺口

### 功能状态快照

以下“已有”均指源码证据，不代表本次通过运行验收。

| 功能 | 代码现状 | 当前不能据此断言的内容 |
| --- | --- | --- |
| CAD 上传与识别 | 上传页接受 DWG/DXF；前端调用 Java `/api/v1/cad/parse`；Java 转发独立解析服务 | 所有办公图纸均能正确解析、端到端流程已验收 |
| 图片/PDF 识别 | 已有 `/api/v1/vision/recognize` 调用链；服务地址为空时返回演示结果 | 未配置外部服务时已获得真实识别结果 |
| 识别结果进入画布 | 已有 `applyRecognitionToCanvas`，将房间包围盒转换成功能区 | CAD 原始多边形、异形边界和完整底图已保真导入 |
| 商品导入 | 已有商品导入、预览等业务接口及前端页面 | 旧 E2E 已覆盖所有新增接口及数据库结果 |
| 项目与画布存储 | 已有项目、画布、购物车、会话的 Java 接口和数据库访问代码 | 多用户、权限、并发冲突及恢复策略已满足正式使用 |
| 设计 Agent | 当前后端 `generateBotReply` 按关键词生成回复 | 已接入真实 LLM，已完成智能布局或真实供应链推荐 |

主要证据入口：

- [上传页](../frontend/app/pages/index.vue)、[前端 API](../frontend/app/composables/useOfficeApi.ts)。
- [CAD 转发接口](../backend/src/main/java/com/rsdp/officeproduct/importer/controller/CadParseController.java)、[CAD 客户端](../backend/src/main/java/com/rsdp/officeproduct/importer/service/CadParseClient.java)。
- [图片/PDF 客户端](../backend/src/main/java/com/rsdp/officeproduct/importer/service/VisionRecognitionClient.java)：服务未配置时调用 `mockResult`，带 `MOCK_RECOGNITION` 警告。
- [办公业务服务](../backend/src/main/java/com/rsdp/officeproduct/importer/office/service/OfficeService.java)。

### 开发管理缺口

1. **说明与源码不同步。** 根 README、前端 README 和 CAD README 仍有“未接入 CAD”的旧说明，源码已存在调用链。
   根目录原型设计、抽取方案仍有参考价值，但应与当前已实现功能分开记录。
2. **缺少统一进度入口。** 未发现项目内 AGENTS、功能状态表、任务记录和架构决策目录。
   下一次开发需要重新从聊天和源码推断状态。
3. **验证命令分散。** 已有前端构建、E2E、Java 测试和 CAD 测试入口，未发现项目内统一 CI 配置。
   `frontend/package.json` 没有独立 lint/typecheck 脚本，不能在开发规范中将其写成已有门禁。
4. **界面回归与真实集成需要分开。** `e2e-workflows.mjs` 拦截 API，适合验证 UI；它的通过不能证明后端解析或数据库写入正确。
   当前 E2E 脚本未发现 CAD/vision 请求及识别结果导入画布的专项断言。
5. **测试通过也要核对覆盖。** CAD `GoldenFileTests` 在缺少图纸时输出 `SKIP` 文本并提前返回，测试可能仍呈现通过。
   记录验收时必须说明金样本是否实际存在、相关断言是否执行。

测试证据入口：[前端工作流测试](../frontend/scripts/e2e-workflows.mjs)、
[前端冒烟测试](../frontend/scripts/e2e-smoke.mjs)、
[CAD 金样本测试](../backend/cad-parser/tests/RsdpCadParser.Tests/GoldenFileTests.cs)、
[金样本定位](../backend/cad-parser/tests/RsdpCadParser.Tests/GoldenFile.cs)。

## 3. 建议在本项目建立哪些文件

先保持现有业务目录，补充以下少量文档。功能增加后再拆分。

| 文件（建议新增或完善） | 记录内容 | 更新时机 |
| --- | --- | --- |
| `AGENTS.md` | 项目背景、代码边界、真实/演示区分、验证入口、协作规则 | 长期规则、目录或命令改变时 |
| `README.md` | 项目入口、启动方法、文档导航 | 启动方法或入口改变时 |
| `docs/architecture.md` | 三个应用的职责、数据流、接口、坐标与单位 | 跨服务结构或契约改变时 |
| `docs/status.md` | 功能状态、验收证据、阻塞项、下一步 | 每个任务状态改变时 |
| `docs/tasks/<编号>-<主题>.md` | 单项需求、边界、实施步骤、验证记录 | 开始任务和结束任务时 |
| `docs/decisions/<编号>-<主题>.md` | 重要技术选择、原因与影响 | 出现需要长期保留的决策时 |

`AGENTS.md` 放稳定规则，`status.md` 放当前状态，任务文件放过程与证据。
通过链接引用同一事实，避免把功能清单复制到多处后分别维护。
原型文档保留为设计基线；最新实现与验收结论进入状态表。

本项目 AGENTS 建议包含：

- 技术边界：Nuxt/Vue 前端、Java 业务后端、独立 .NET CAD 服务；沿用现有目录和技术栈。
- 修改前读取对应文档和实现，保留当前工作区已有改动。
- CAD 的毫米几何坐标、预览范围和画布像素坐标必须有明确转换契约。
- 演示数据、接口接通、真实解析及人工复核分别表达；不得把演示响应作为真实验收证据。
- 根据改动范围选择下节验证，不要求文案修改运行全部系统测试。
- 对外报告具体变更、验证结果和剩余边界，及时更新相关 README 与状态记录。

## 4. 日常开发如何使用

建议每个功能按以下顺序处理：

1. 读取项目规则、状态表和相关源码，确认已有能力。
2. 写一份短任务记录：目标、范围、输入输出、验收标准。
3. 将任务拆成能独立验证的小步骤；先明确跨服务契约，再实现调用与界面。
4. 完成必要修改，按影响范围运行构建、测试或真实样本验证。
5. 检查差异，记录验证结果，并同步受影响文档。
6. 用清楚的提交记录保存阶段性成果；发布、远端推送沿用本项目与用户已有约定。

### 进度记录模板

建议状态：`待开始`、`进行中`、`已编码待验证`、`已验收`、`阻塞`。
演示实现额外注明“演示”；服务端完成但界面未打通时拆成两项记录。
不要只使用缺少统计口径的“完成 80%”。

```text
任务：CAD-001 图纸识别进入空间总览
状态：已编码待验证
目标：DWG/DXF 上传后能够识别、预览并应用到空间总览
已有证据：上传入口、Java 转发、独立解析服务、画布应用函数
已执行验证：待执行；不得填入未运行的命令或结果
剩余边界：异形房间目前转成包围盒功能区；底图和几何对齐需验证
下一步：选取授权使用的办公图纸，核对单位、房间、面积及画布位置
更新日期 / 代码提交 / 验证人：按实际填写
```

任务文档只记录有助于接续开发的信息：决策、异常、测试命令与结果、未完成项。
不需要保存整段聊天或逐次工具调用。

## 5. 验证应如何落地

以下是当前项目已有入口，本次未执行。采用规范时应确认相应环境和服务可用。

| 改动范围 | 现有验证入口 | 结果能够证明什么 |
| --- | --- | --- |
| 前端 | 在 `frontend/` 执行 `pnpm build` | 应用构建可完成；不等于业务验收或独立类型检查 |
| 页面交互 | 前端服务运行后，在 `frontend/` 执行 `pnpm e2e:workflows` | 已覆盖的 UI 行为在拦截 API 条件下成立 |
| 设计流程冒烟 | 前端服务运行后，在 `frontend/` 执行 `MOCK_API=1 pnpm e2e:smoke` | 已有设计页面流程可走通，调用真实服务需另验 |
| Java 后端 | 在根目录执行 `mvn -f backend/pom.xml test` | 当前 Java 测试覆盖范围内通过 |
| CAD 服务 | `dotnet test backend/cad-parser/tests/RsdpCadParser.Tests/RsdpCadParser.Tests.csproj` | 当前 CAD 测试实际执行的断言通过；需核对样本覆盖 |
| CAD 真实集成 | 通过 Java 上传入口上传已授权图纸，再从页面识别并应用 | 前端、Java、CAD 服务及画布在该样本上的行为成立 |
| 文档 | 检查本地链接、命令与源码的一致性 | 文档可导航，说明符合当前实现 |

CAD 项目和测试当前目标框架均为 `net7.0`，测试环境要按实际 csproj 配置。
E2E 测试需要浏览器及前端服务；使用独立测试环境做真实数据验证，避免与 UI 拦截测试混淆。

后续建议增加统一脚本和 CI，让相同检查在本地及远端运行。
先接入当前稳定、可复现的构建与测试，再补独立类型检查和重要集成测试。
缺少依赖、样本或测试环境时明确记为未验证，不能把跳过写成通过。

## 6. 以 CAD 功能为例，下一轮如何组织

建议按现有代码继续补齐，不重新实现 CAD 解析器。

| 顺序 | 任务 | 具体验收内容 |
| --- | --- | --- |
| 1 | 校正功能说明 | 文档区分 CAD 真实解析链、vision 未配置时的演示响应、待验收的画布行为 |
| 2 | 固定识别契约 | 明确 `units`、`drawingBounds`、`preview.bounds`、房间多边形、质量问题及解析状态；区分 CAD 与 vision 来源 |
| 3 | 确认画布精度目标 | 明确当前包围盒功能区是否满足阶段需求；如要求真实边界，则保留原始多边形并验证预览对齐 |
| 4 | 验证业务异常 | 空文件、错误格式、服务不可用、无房间、部分识别、需要人工复核，都有明确反馈 |
| 5 | 验证文件与草稿恢复 | 上传后刷新仍可取回原文件；应用结果与项目画布保存恢复一致；同名不同文件需单独检查 |
| 6 | 真实办公样本验收 | 记录图纸版本、来源、预期房间、面积与位置对照，不直接沿用住宅样本结论 |
| 7 | 更新状态 | 附执行命令、测试输出或截图、样本标识和代码提交；通过后才改为已验收 |

任务文件可使用以下请求模板：

```text
请先读取项目开发规则、docs/status.md 和 CAD 相关实现。
目标：验证并完善 CAD 上传到空间总览的现有流程。
范围：沿用现有解析服务与 Java 转发入口，先确认响应和画布坐标契约。
验收：真实 DWG/DXF 能显示识别结果；部分识别和失败能明确反馈；
      原始图纸及应用后的画布可按约定恢复；面积和位置有样本核对证据。
交付：代码差异、实际验证结果、剩余限制、更新后的任务记录和状态表。
```

## 7. 采用顺序与适配边界

第一阶段：校正文档、添加简短项目规则和功能状态表，用下一项真实功能试行任务模板。
第二阶段：固定关键 API/坐标契约，整理统一验证入口，补关键失败路径与集成验证。
第三阶段：接入 CI；有稳定的重复工作后再考虑专用技能，例如 CAD 样本核验或商品导入回归。

上游的 `make test` 服务于它的文档仓库，本项目没有同名入口，不能直接写入本项目规范。
其默认 `develop` 推送流程、知识库目录格式和全套配置也需要按本项目情况调整。
测试中的 mock 可用于隔离外部服务；产品中的演示响应需要清楚标明来源。
这些场景应分别定义，不采用一条笼统的“所有 mock 都禁止”规则。

阅读方法文档不需要克隆整个仓库、安装全部 Skills 或修改全局工具配置。
后续如复用具体模板或脚本，应在项目文档保留来源，并检查其适用条件和维护成本。
