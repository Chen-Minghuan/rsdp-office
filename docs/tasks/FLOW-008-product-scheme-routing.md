# FLOW-008 产品选型入口路由与预览工具归位

日期：2026-11-27
状态：已实现；当前范围验证通过

## 任务目标

1. 统一“进入家具排版”和“基于模板进入排版”的目标页面，避免同一流程落到不同页面。
2. 原设计页的“3D 白模”和“AI 效果图”入口移到产品选型工作区**底部预览区域**；顶部不重复显示，后续确认和记录按钮顺延。
3. 按截图将顶部“产品方案”入口改名为“产品选型”。

## 修改思路与逻辑

不复制页面或功能实现，只统一路由并恢复已有事件入口。

新增 `productSchemeRoute(floorId, zoneId)`，统一返回：

```text
/canvas?floorId={当前楼层}&mode=design&view=products&zoneId={当前空间}
```

- `SpaceReview` 页脚“进入家具排版”和空间模板事件都调用该函数。
- `AppTopbar` 的“查看空间模板 → 进入排版”也调用同一函数，补上此前缺失的 `view=products`。
- 顶部导航复用同一函数，不再单独拼一份产品选型 URL。
- 原有复核门禁不变；未满足复核条件时仍按现有规则处理，不用路由绕过。

## 3D 与 AI 入口归位

产品选型页使用 `previewInFooter`：顶部工具栏过滤 `white-model` 和 `render`，避免与底部重复；其他普通设计页仍显示原工具入口。

底部现有“3D 预览”位置改为“3D 白模”，并在其后新增“AI 效果图”：

- `3D 白模` → 已有 `showWhiteModel = true`。
- `AI 效果图` → 已有 `openRenderPreview()`。
- 后面的“生成方案效果图 / 去项目清单确认 / 生成记录”和生成图片依次顺延。

没有新增第二套 3D/AI 组件，也没有修改生成参数或业务数据。底部容器已有 `flex-wrap`，空间不足时后续内容自然换行，因此没有添加硬编码的 div 偏移样式。

## 文件清单

### 实现文件

| 文件 | 修改内容与意义 |
| --- | --- |
| `frontend/app/utils/projectBenchRoutes.ts` | 新增共享 `productSchemeRoute`，固定产品选型页路径和查询参数。 |
| `frontend/app/components/OfficeCanvas/SpaceReview.vue` | 页脚排版入口和模板排版事件统一调用共享路由。 |
| `frontend/app/components/AppTopbar.vue` | 顶部模板入口改用共享路由，不再漏掉 `view=products`。 |
| `frontend/app/composables/useWorkspaceNavigation.ts` | 顶部导航复用共享路由，文案改为“产品选型”。 |
| `frontend/app/components/OfficeCanvas/CanvasToolbar.vue` | 产品选型顶部过滤 3D/AI，其他设计页保留；使用 `previewInFooter` 区分。 |
| `frontend/app/pages/canvas.vue` | 在产品选型底部放置“3D 白模、AI 效果图”，复用现有事件，后续确认与记录顺延。 |

### 测试文件

| 文件 | 修改内容 |
| --- | --- |
| `frontend/scripts/e2e-product-scheme.mjs` | 更新产品选型文案，并增加底部 3D/AI 可见、顶部不重复的断言。 |
| `frontend/scripts/e2e-workflows.mjs` | 同步导航文案断言。 |
| `frontend/scripts/e2e-project-floors.mjs` | 同步项目/楼层导航文案。 |
| `frontend/scripts/e2e-project-refresh.mjs` | 同步刷新流程中的导航文案。 |

没有保留对旧模板专项的项目、缓存、canvas 或复核模拟扩展；那些尝试超出本次范围，已撤回。

## 实际验证

- 前端生产构建：通过。
- 共享路由函数直接执行：通过；实际输出 `/canvas` + `mode=design` + `view=products` + 正确楼层和空间 ID。
- `e2e:workflows`：通过；导航、上下文和响应式布局通过。
- `e2e:project-floors`：通过；项目/楼层进入产品选型工作区通过。
- 本地真实项目成员页面：顶部显示“产品选型”，底部“3D 白模、AI 效果图”均能实际打开；顶部无重复入口，未提交生图，未修改真实排版。
- `git diff --check`：通过。

## 截图

- [底部 3D/AI 入口与后续按钮顺序](../../output/temp/FLOW-008-footer.png)
- [3D 白模从底部实际打开](../../output/temp/FLOW-008-3d-entry.png)

## 未覆盖与既有专项状态

- `e2e:product-scheme` 旧脚本在进入本任务页面前，停在既有“自选商品组合”提示前置条件，因此本次不记为完整专项通过。
- `e2e:templates` 旧脚本也未完成既有 mock 链路的完整运行；本次没有继续扩展其测试数据，未将其标为通过。
- `e2e:white-model` 追跑时停在既有“生成预览包含四个视角”断言；本次入口位置复核由真实页面另行验证，不据此宣称白模全量回归通过。
- 未执行完整前端 UI 全量回归，未修改后端接口，也未新增持久化数据。

本次没有提交代码。
