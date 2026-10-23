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

## 2026-10-23 追加：已画空间旋转

用户反馈画好的空间应可旋转。总览选择工具条新增「旋转」输入（默认 90°，屏幕顺时针为正），`store.rotateZone` 绕面积质心旋转轮廓（`rotateRing` 纯函数），建筑约束（柱/门洞/禁放区）随轮廓同步旋转，`zoneLayoutProblem` 校验失败不落库，可撤销；旋转致矩形非轴对齐时宽/深数值编辑自动停用。

验证：`pnpm build`、`pnpm e2e:space-review`（新增 30°/-30° 逐顶点与撤销断言）、`MOCK_API=1 pnpm e2e:smoke` 通过；U 形 1400×400 旋转 30° 与回退两张截图已人工查看。

## 2026-10-23 追加：旋转实时预览与 Enter 应用

按用户反馈：旋转角度输入即时预览（渲染层覆盖选中空间，dirty 门控），未应用不落库；Enter 热键或「应用」提交，Esc/失焦/切换选中取消预览。

验证：`pnpm build`、`pnpm e2e:space-review`（新增预览不落库与 Enter 断言）、`MOCK_API=1 pnpm e2e:smoke` 通过；U 形 45° 预览截图已人工查看，并确认预览期间 localStorage 几何不变、Enter 后写入。

## 2026-10-23 追加：旋转仅 Enter/应用提交

用户最终明确：只有点「应用」或 Enter 才应用旋转，点空白处不应用。定稿行为：输入角度即时预览（不落库）；画布 pointer-down、选中空间、拖动变换或输入框失焦只放弃预览；Esc 放弃预览；角度值保留，放弃后点「应用」仍按当前角度提交。（曾短暂实现"点任意处自动保存"，按用户纠正回退。）

验证：`pnpm build`、`pnpm e2e:space-review`（点空白不应用、放弃后「应用」提交 15° 逐顶点比对、Esc 后点击不应用、撤销断言）、`MOCK_API=1 pnpm e2e:smoke` 通过。

## 2026-10-23 追加：一键旋转 90°

旋转表单新增「旋转 90°」按钮：显式动作点击即应用（绕质心顺时针，丢弃未提交预览，可撤销），与角度输入的 Enter/应用提交并存。

验证：`pnpm build`、`pnpm e2e:space-review`（一键 90° 逐顶点比对与撤销断言）、`MOCK_API=1 pnpm e2e:smoke` 通过；工具条与 U 形旋转结果截图已人工查看。

## 遗留与边界

- 本地浏览器 localStorage 中旧的 `rsdp-office-manual-canvas-v01` 沙盒数据不再被读取，无迁移逻辑。
- `e2e:furniture-modules` 的既有超时未修复，需单独排查。
- 手动画图清空图纸文件不进入撤销快照，与既有 `removeFile` 行为一致；UI 已通过确认弹窗提示。
