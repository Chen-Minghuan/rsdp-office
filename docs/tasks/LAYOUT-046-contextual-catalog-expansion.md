# LAYOUT-046 空间与家具驱动的目录展开

日期：2026-11-19
状态：已实现并完成聚焦验证

## 任务目标

进入产品选型时，根据当前空间和正在编辑的家具自动展开相关图例分类：主管室展开主管桌，总经办展开高管桌，员工区展开职员桌，前台、会议室等使用各自分类；选中椅子时展开座椅。

本次只改变目录展示，不自动选择图例、不进入摆放状态，也不修改家具几何、商品推荐接口或后端数据。

## 修改思路

复用现有 `furnitureCategories`、`furnitureById` 和原生 `<details>`，不新增目录组件、全局状态或平行分类数据。

分类采用两级判断：

1. 当前选中家具的分类优先，自定义模块读取 `moduleSnapshot`，内置家具复用 `furnitureById`。
2. 没有选中家具时，先匹配空间名称关键词，再以空间用途兜底。名称优先是为了让“主管室”在用途为 `EXECUTIVE_OFFICE` 时仍展开主管桌，而不是一律展开高管桌。

## 分类规则

| 空间或操作 | 展开分类 |
| --- | --- |
| 总经办、总经理、总裁、高管、董事 | 高管桌 |
| 经理、主管 | 主管桌 |
| 开放办公、员工、职员 | 职员桌 |
| 会议、洽谈 | 会议与洽谈 |
| 前台 | 前台 |
| 接待、休闲、会客、茶水 | 接待休闲 |
| 储物、储藏、收纳 | 收纳 |
| 打印、设备、机房 | 设备辅助 |
| 选中座椅类家具 | 座椅 |
| 名称未命中时的用途 | `MEETING_ROOM`、`EXECUTIVE_OFFICE`、`RECEPTION`、`COLLABORATION`、`STORAGE`、`PRINT_AREA`、`SERVER_ROOM` 分别映射对应分类 |
| 其他或未知空间 | 保留通用默认“职员桌”，不猜测特殊分类 |

## 核心逻辑

- `preferredFurnitureCategory(zone)` 只负责空间到分类的映射。
- `furnitureCategoryForItem(item)` 只负责家具实例到分类的映射。
- 产品选型组件计算“选中家具分类优先，否则空间分类”的推荐值。
- 空间 ID 或推荐分类变化时，组件在下一次 DOM 更新后设置一次对应 `<details>` 的 `open` 布尔属性并滚动到可见位置。
- 之后目录交还原生交互：用户可以手动展开多个分类，普通重渲染不会强制收起；下一次空间或家具上下文变化时再重新定位。
- 使用标准 `open` 属性，同时保证 DOM property 与 HTML 状态一致。专项测试也统一按真实 `open` property 判断，避免空字符串属性值造成误判。

## 文件与职责

| 文件 | 修改内容与意义 |
| --- | --- |
| `frontend/app/utils/manualFurniture.ts` | 新增两个小型纯函数，集中维护空间/家具到目录分类的规则；没有复制商品数据。 |
| `frontend/app/components/OfficeCanvas/ProductLayoutEditor.vue` | 用推荐分类替换“始终展开第一个分类”的旧逻辑；只负责展示联动，不修改任务一的摆放、拖拽和替换流程。 |
| `frontend/scripts/e2e-custom-modules.mjs` | 增加主管室、总经办、员工区、前台、会议室及选中椅子的浏览器断言，并检查默认展开不会自动开始摆放。 |
| `frontend/scripts/e2e-module-recommendations.mjs` | 目录展开判断改用真实 `open` property，覆盖会议室中选中职员桌并显式替换的回归。 |
| `docs/status.md` | 增加本任务的进度和验证入口。 |
| `docs/tasks/LAYOUT-046-contextual-catalog-expansion.md` | 本修改说明。 |

## 实际验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端生产构建 | `pnpm build` | 通过（10.5s） |
| 产品选型专项 | `PLAYWRIGHT_EXECUTABLE_PATH=<Edge> pnpm e2e:custom-modules` | 通过：五类空间映射、选中椅子展开座椅，以及点击/拖拽、连续放置、旋转、越界与重叠拒绝、撤销、自定义模块快照恢复 |
| 替换/推荐专项 | `PLAYWRIGHT_EXECUTABLE_PATH=<Edge> pnpm e2e:module-recommendations` | 通过：显式替换、画布与推荐双向联动、尺寸分档、旧响应拦截、筛选与空结果 |
| 浏览器 | 本机 Microsoft Edge（Playwright 默认浏览器版本不匹配时用 `PLAYWRIGHT_EXECUTABLE_PATH` 指定） | 上述 UI 测试全部使用模拟 API |
| 空白检查 | `git diff --check` | 通过 |

> 本机 Playwright 1.64 需要的 Chromium 版本未安装，验证时通过脚本已支持的 `PLAYWRIGHT_EXECUTABLE_PATH` 指向本机 Edge，未改动测试脚本的启动逻辑。

## 未覆盖与边界

- 未执行完整 UI 全量回归、真实数据库保存或真实业务验收，不能把本结果记为全量或真实业务验收。
- 名称映射只覆盖当前明确配置的关键词；未命名、命名异常空间使用用途或通用默认，不自动生成新分类。
- 未修改 L 型桌占地、自动排版算法、商品筛选和推荐接口。
