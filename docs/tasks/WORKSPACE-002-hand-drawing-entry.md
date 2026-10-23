# WORKSPACE-002 删除手动布局沙盒并新增上传页手动画图入口

日期：2026-10-22。状态：已编码并通过专项验证；均为前端改动与 mock UI 验证。

## 用户要求与范围

用户反馈侧边栏「手动布局」已无用：空间设计的空间核对总览已内联「画新空间」（形状模块拖拽/逐点描，空白画布按毫米建空间），独立 `/manual-canvas` 沙盒页不再需要。要求删除手动布局，并在上传图纸界面新增「手动画图」入口，供没有图纸的设计师直接从空白画布自行绘制空间。

## 实现

- 删除 `app/pages/manual-canvas.vue`、`app/components/OfficeCanvas/ManualCanvas.vue`、`app/stores/manualCanvas.ts`、`app/utils/manualCanvasCamera.ts`，并移除 `AppSidebar` 的「手动布局」入口；`utils/sample.ts` 删除仅服务该沙盒的 `sampleManualLayout`。
- 保留共享部分：`types/manualCanvas.ts`、`ManualFurnitureGlyph`、`manualFurniture`、`manualLayoutGeometry` 继续服务排版目录与参数化图例；相关注释从「与手动布局共用」改为「共享家具模板目录」。
- store 新增 `startHandDrawing`：清空图纸文件、识别结果、空间、模板、方案与选品车，置 `blank` 并回到空间总览；先 `snapshotUndo`，原设计几何/确认可经画布撤销恢复，图纸文件不进快照需重新上传。
- 上传页底部操作条新增「手动画图」（`data-testid="start-hand-drawing"`），空状态新增「没有图纸？手动画图」；已有图纸/识别/空间时先弹确认，取消则保留原稿，确认后进入 `/canvas?mode=overview` 空白毫米画布，沿用「画新空间」工具绘制。
- `e2e:furniture-modules` 不再打开 `/manual-canvas` 比对目录线稿，改为断言 29 个目录项均带名称与参数化线稿（同源 `furnitureCatalogTemplates`，无需跨页比对）。

## 验证与结果

| 检查 | 实际结果与边界 |
| --- | --- |
| `cd frontend && pnpm build` | 通过 |
| `MOCK_API=1 BASE_URL=http://localhost:3210 pnpm e2e:smoke` | 通过；独立端口自建预览服务（3001 被用户既有服务占用），mock API |
| `pnpm e2e:drawing-entry` | 通过；新增手动画图断言：取消保留原稿、确认清空进空白画布、原稿进撤销快照、空白状态再进免确认。mock API |
| `pnpm e2e:space-review` | 通过；画新空间/旋转/改名等既有画区回归不受影响。mock API |
| `pnpm e2e:furniture-modules` | 未通过；基线（stash 后原构建）复测在同一位置（第 60 行等待「选择空间 办公家具测试空间」）同样超时，判定为既有问题，与本次改动无关；修改后的目录断言段未执行到 |
| `git diff --check` | 通过 |

## 2026-10-23 追加：手动画布常驻返回按钮

用户反馈手动画图界面缺少返回入口（突然有图纸要上传时）。在空间核对总览工具条新增「← 返回上传图纸」链接按钮（`data-testid="back-to-upload"`），位于画区 inert 工具区之外，画区/描边途中也可返回；返回仅导航回上传页，不清空手绘画布，已有手绘空间保留。空白态遮罩内的「返回图纸识别 →」保留。

验证：`pnpm build`、`pnpm e2e:drawing-entry`（新增返回按钮断言）、`pnpm e2e:space-review`、`MOCK_API=1 pnpm e2e:smoke` 通过；空白态与已画空间态两张截图已人工查看。

## 遗留与边界