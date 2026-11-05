# RENDER-007 3D 白模实时画质增强（第一阶段）

状态：已实现并通过本地验证（模拟 API；真实浏览器人工目检待复核）
更新日期：2026-11-05

## 目标

用户反馈：希望在打开 3D 白模时直接看到接近效果图的画面，同时保持白模的全部交互（点选/拖动/旋转/替换写回设计几何），而不是只能看到灰白素模再依赖 AI 静态图。

方向：不引入后期通道/外部资源的第一阶段——把"白膜感"的最大来源（纯色地板、纯平背景、硬光、无暗角）替换为程序化材质与光照调优。2D 画布仍是唯一事实来源，本任务只改渲染层，不改任何几何/接口/确认规则。

## 实现（2026-11-05）

全部改动在 [WhiteModelPreview.vue](../../frontend/app/components/OfficeCanvas/WhiteModelPreview.vue)：

- **程序化木地板**：`floorFinishTextures()` 生成颜色 + 粗糙度两张 1024² CanvasTexture（150mm 板宽、770–2100mm 错缝板长、木纹丝、板缝、逐板明度/粗糙度微差、细噪点防色带）。一块纹理对应世界 3600mm 见方——ExtrudeGeometry 顶面 UV 即形状毫米坐标，`repeat = 1/3600` 按物理尺寸平铺，与缩放无关。模块级缓存共享、不随场景释放；`typeof document === 'undefined'` 退化 null（Node 单测/极端环境保持纯色地板）。`applyFloorFinish(renderer)` 在 init 后挂纹理并取 `min(8, getMaxAnisotropy())` 各向异性。
- **地板断面分色**：地板网格改材质数组 `[顶面木纹, 断面深色]`（ExtrudeGeometry 分组 0=顶/底面、1=侧面），强调楼板边界。
- **光照**：`PCFSoftShadowMap` 软阴影；阳光 2.4→2.6 且更暖（0xfff2e0）；半球光 0.9→1.0；新增 0.55 冷色补光（不投影，方向在 `frameCamera` 中与太阳相对），打开背光面暗部；曝光 1.0→1.06。
- **背景**：2×512 纵向渐变 CanvasTexture（#f6f3ed → #e2ddd2）替代纯色，不再是大片死平色。
- **CSS 暗角**：宿主层叠加径向渐变（transparent 58% → rgba(40,34,26,0.13)），`pointer-events-none`、z-index 4（UI 控件 z-10 之下）。暗角是 DOM 层，不进 WebGL 画布，AI 效果图截图（RENDER-006 的 toDataURL）不受污染。

## 验证（2026-11-05）

- `pnpm build` 通过；`pnpm test:white-model`、`pnpm test:furniture3d` 通过（纯函数，未受影响）。
- UI（模拟 API）：`pnpm e2e:white-model`、`pnpm e2e:proposal-3d` 通过；e2e 保存的场景截图已人工目检两轮——第一轮换 1800mm 块时逐板色差大，平铺周期形成明显人工条带，已调为 3600mm 块 + 更小色差后复查，条带感消除、木纹自然。
- 交互不变：点选/拖动/键盘移动/旋转/替换仍写回同一设计几何（e2e 覆盖）；AI 概念效果图入口与截图链路不变。

## 边界与后续

- 仍是程序化示意材质：家具造型/配色不对应真实商品，真实商品外观由商品 GLB（modelRegistry）与 AI 效果图（RENDER-006 白模截图引导）承担。
- 未引入 SSAO/后期通道（EffectComposer 会改动渲染与截图链路，留作第二阶段评估）；墙面仍为纯色，墙体贴图/踢脚线立体化、天花板与灯光遮挡未做。
- 无外部图片资源，WebGL 画布无跨域污染，AI 截图不受影响；性能变化为多两张静态纹理与一盏不投影的灯，阴影贴图仍 2048。
- 真实浏览器（非无头 SwiftShader）下的各向异性与色彩表现待人工复核。
