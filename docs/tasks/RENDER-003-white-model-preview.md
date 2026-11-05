# RENDER-003 3D 白模预览（2D 排版 → 可交互白模）

状态：已实现并通过本地验证
更新日期：2026-11-03

## 目标

用户要求效果预览从"固定视角 AI 生图"改为"2D 排版直接转换的可交互 3D 白模"：家具可拖动与替换，建筑约束（障碍标注）与设施标注一并渲染，不再是观察视角的静态出图。

## 实现

- 新增 [whiteModel.ts](../../frontend/app/utils/whiteModel.ts) 纯数据构建器：空间多边形 → 地板 + 沿边墙体；家具按模板/类别映射高度（仅渲染，不回写几何）；障碍按 obstacleRing 的世界毫米→画布约定翻转 Y（柱/井道到顶 2800、禁放区 2000 半透明、门洞净空 20 平贴、窗 900–2100 墙段）；设施标注（GEOM-018）按点/线/面渲染并显示人工净空圈；启用的布置区（GEOM-016）作地板着色。
- 新增 [WhiteModelPreview.vue](../../frontend/app/components/OfficeCanvas/WhiteModelPreview.vue)：three.js 场景 + OrbitControls 自由视角（非固定视角）；点选/列表选择家具；沿地面拖动并在松开时经 `store.moveObject` 写回同一份设计几何（2D 画布同步）；旋转 90°、替换家具模块（图例目录）、绑定/替换商品（真实商品库，尺寸同步）均经 `store.updateObject`，与 2D 排版共用数据与撤销/持久化链路。
- 首版墙体采用 1500mm 半高剖切（2800 全高墙会遮挡近墙家具，实测相机俯角 41° 时遮挡深度约 3.1m）；柱/井道保持到顶。2026-11-03 用户要求所有墙体同高，当前已统一为 2800mm，见 [RENDER-004 墙高统一追修](RENDER-004-3d-furniture-interaction.md)。
- 工具栏新增「3D 白模」入口（设计模式）；原「效果预览」更名「AI 效果图」保留可达，白模对话框内也有「AI 概念效果图」跳转；方案页的 AI 效果图流程不受影响。
- 新增依赖 three 0.187（+ @types/three）。

## 验证

- `pnpm build`、`pnpm test:white-model`（构建器：高度映射/障碍 Y 翻转/设施过滤/布置区/退化兜底）、`pnpm e2e:white-model`（场景统计、面板选择、旋转/替换/绑定写回、拖动同步 2D、AI 入口；mock API）通过；`e2e:render-preview`（AI 效果图新入口名）、`e2e:drawing-entry`、`e2e:single-space` 通过。
- 渲染目检（临时截图，未入库）：L 形空间半高墙、家具接地阴影、选中高亮、柱/禁放区/门洞净空/布置区/设施标注着色均可读。
- 既有缺口（与本改动无关，未修复）：`e2e:space-review`、`e2e:templates` 在当前 main 即失败（空间宽度标签更名、apply-layout 显隐条件变化所致，源自近期排版改版），已在干净工作树复现确认。
- WebGL 依赖浏览器能力；不支持时对话框显示明确降级提示。真实服务集成与多空间白模不属于本次范围。
