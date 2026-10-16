# GEOM-008 按用户标注修正开放分区

日期：2026-10-16。状态：当前批次棋牌室与开放分区/邻室共十处人工修正，最终保存 revision 458，保留用户新校准比例；浏览器刷新确认待用户核对。此前批次记录保留如下。

## 输入与边界

用户提供截图，要求共享过道对应中间横向长条，休闲区贴合左右墙体，门厅、吧区及右侧过道按重新标注的范围处理。这是用户指定的人工设计修正，不是模型自动识别验收；坐标仅存于本次修正资料，不加入通用解析器。

来源图为 2094×1269，SHA-256 `bb88227c94adf107e30ccef75478460c2beee5661e1c06fd08d4bf6a1c1e4202`；当前来源 `FPA-B3D8464F` / revision 1。读取当前保存稿后，目标五处仍为旧 AI 几何，因此将截图红线作为本次修正参考。其他空间已有人工改动，全部保留。

## 处理

- 共享过道改为横向长条，连接总经办上方与门厅/吧区交界，不再占门厅下方的纵向矩形。
- 休闲区左右边到实际墙内侧，包含靠墙柜体占地范围；底部两侧实体柱用凹角绕开，底边到外立面内侧。
- 门厅延伸至共享过道上边；吧区及右侧过道按标注调整，五处之间不重叠。
- 原稿读取时 revision 450、来源像素单位。期间用户已完成比例校准并生成模板，最新 revision 451、确认比例 12.098729123043457 mm/px。第一次只读校验因版本变化拒绝继续；对比确认只是统一比例转换且目标来源形状未改变后，按 revision 451 重新准备修正，不恢复旧单位或旧稿。
- 使用已有画布 API 保存为 revision 452；保留全部其他对象、空间 ID、`sourcePolygon`、`mmPerSourceUnit` 与 `calibrationEvidence`。目标记为 `user_annotation` / `manual_geometry` / `MANUAL`，确认置失效，目标家具的排版确认失效。旧模板快照不改写，几何比较会判定模板过期，后续需重新生成。
- 原稿备份 `/tmp/rsdp-open-zone-before-452.json`；保存后回读 `/tmp/rsdp-open-zone-after-452.json`；原图叠加预览 `/tmp/rsdp-open-zone-corrected.png`。
- Qwen 提示补充开放分区间的分界、通行方向和贴墙/避柱要求；没有为此重跑付费识别或声称该提示已通过真实识别质量验收。

## 工具与资料

- [本次修正包](../fixtures/GEOM-008-open-zone-corrections.json)：包含底图哈希、来源版本、基准画布版本、稳定空间 ID、预期旧轮廓和来源像素修正轮廓。
- [修正脚本](../../scripts/apply-space-contour-corrections.py)：默认只读校验。显式 `--apply --backup <新文件路径>` 才调用既有 API 保存；底图、来源、版本、旧轮廓、正交几何任一不符即停止，应用前再次读取当前画布检查并发变化，应用后回读比较。
- 本次实际执行：`python3 scripts/apply-space-contour-corrections.py docs/fixtures/GEOM-008-open-zone-corrections.json --apply --backup /tmp/rsdp-open-zone-before-452.json`。**已应用，不能对当前 revision 452 重复执行旧包**；未来修正需基于最新草稿重新核对。

## 实际验证

- 真实 API 回读确认 revision 452、五处轮廓一致；无关空间/对象逐项相等，目标原始轮廓、校准比例/证据未改变。
- 使用实际前端 `review.ts` / `geometry.ts` 的 `ringProblem` 和 `ringsOverlap` 校验保存后的五处几何：无自交、无邻室重叠。预览叠加图已查看。
- `mvn -f backend/pom.xml test`：102 项通过，0 失败/错误/跳过；日志 `/tmp/rsdp-open-zone-java.log`。
- 前端本次没有代码变化，未重新执行构建或 UI mock。计算机 UI 工具读取当前浏览器失败（native pipe closed），因此不声称已在用户浏览器刷新确认；刷新后现有初始化逻辑会读取较新的后端画布版本。
- 不启动/重启用户后端，不改写原始识别 revision，不把人工指定分区当作模型自动产出。用户刷新画布核对后重新生成空间模板。

## 休闲区红框追修（2026-10-16）

用户再次截图指明休闲区应为下方两墙之间的整块区域。读取发现当前已换为新的识别来源 `FPA-38D532FB` / revision 1（同一底图哈希），休闲区 ID 为 `FPR-20B59BA9`，旧候选是向上延伸的窄条；不能复用上一批次的空间 ID 或修改其他空间。

- 使用[本次单空间修正包](../fixtures/GEOM-008-lounge-followup.json)，按红框修正左右边、上边和底边，底部实体柱仍保留凹角。像素坐标仅为本次人工标注，不作为模型通用规则。
- 仅将当前休闲区从来源像素窄条改为左右墙间区域；当前比例未确认，继续保留 `reviewUnit=source`，不恢复上一批次的毫米校准。
- 通过既有脚本和真实画布 API 从 revision 452 保存到 453，并回读核对。其他空间/对象逐项未变，原始 `sourcePolygon`、当前来源版本及单位保留。目标确认和目标家具排版确认失效。
- 原稿备份 `/tmp/rsdp-lounge-before-453.json`，回读 `/tmp/rsdp-lounge-after-453.json`，已查看叠加图 `/tmp/rsdp-lounge-red-frame-corrected.png`。
- 实际前端几何模块校验本次轮廓无自交、无邻室重叠；未改运行代码，不重复运行构建或 Java 测试。本次是当前画布人工修正，不保证再次新识别自动得到相同边界；未进行用户浏览器刷新核对。

## 棋牌室及本批次整组追修（2026-10-16）

来源再次变为 `FPA-01A9127D` / revision 1，同一图像哈希。用户指出棋牌室在右侧独立房间，过道、吧区、休闲区仍不符。新识别 ID 导致此前按 ID 保存的修正未被继承；通用处理改动见 [RECOG-005](RECOG-005-label-grounding-and-correction-reuse.md)。

- 以最新草稿 revision 454 准备[十处修正包](../fixtures/GEOM-008-recognition-followup.json)，包括棋牌室、右侧纵向过道、吧区、下方休闲区，以及相邻的共享过道/门厅/开放办公区/包间/储物间/茶水间。相关邻室同时调整以避免保留旧错位框后仍相互覆盖；三间总经办、水和财务室保留。原图叠加核对 `/tmp/rsdp-accuracy-proposed.png`，15 空间全部有效且无两两重叠。
- 真实 API 首次保存 revision 455，原稿备份 `/tmp/rsdp-accuracy-before-455.json`。这是人工边界，不把它作为新模型识别结果。
- 随后浏览器从旧本地轮廓完成比例校准，保存 revision 456（12.098729123043457 mm/px），把整批旧轮廓写回。回读发现后不恢复像素稿或清除比例，而是基于当前毫米稿重新准备修正。
- [校准后棋牌室包](../fixtures/GEOM-008-chess-calibrated-followup.json)先保存 revision 457；[其余九处校准后包](../fixtures/GEOM-008-calibrated-open-zones-followup.json)再保存 revision 458。当前原始 `sourcePolygon`、比例证据、毫米单位、全部无关对象保留；仅目标几何与相关确认失效。每步应用前重新读取并检查版本与预期旧轮廓，不覆盖观察到的其他变化。
- 备份 `/tmp/rsdp-chess-calibrated-before-457.json`、`/tmp/rsdp-accuracy-calibrated-before-458.json`；最终真实回读 `/tmp/rsdp-accuracy-after-458.json`。实际前端 `ringProblem` / `ringsOverlap` 模块核验最终毫米稿：15 空间有效，无两两重叠。浏览器需刷新以加载修正后的新版本，旧模板需要重新生成。
- Java 全量 108 项、前端构建、同图继承纯函数和空间编辑 UI mock 通过；新真实模型请求只验证棋牌室等封闭区域的独立定位，开放分区纯模型仍有误差。前端/Java/真实识别/当前人工结果不混为同一验收。
