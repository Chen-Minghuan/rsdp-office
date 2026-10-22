# LAYOUT-020 通道作为排版约束与画布对象

日期：2026-10-22。状态：已实现并通过专项验证。基线：ce2f9e9，工作区起始有其他任务的未提交改动（已保留，未混入）。

用户参考外部"培训空间自动布局工具"，要求先实现其通道系统：横向/纵向通道是画布上可拖动定位、可设宽度的对象，同时作为自动排版约束；纵向通道支持「仅从二排起」使首排桌列保持完整。经用户确认：弧形模式斜向通道本期不做（系统无弧形布局）；通道宽度等数值为当前策略，不声称符合建筑规范。

## 行为契约

- 通道存于 `LayoutPreferences.channels`（`LayoutChannel { id, orientation, offsetMm, widthMm, fromSecondRow?, clearanceMm? }`，clearanceMm 见文末补记），随 `zone.layoutPreferences` 走既有 office_canvas JSON 持久化与撤销快照，不新增表和后端接口；旧数据无该字段按空数组读取。`offsetMm` 与家具 x/y 共用世界毫米坐标（纵向取 x、横向取 y），不是相对偏移。
- 课桌式网格排布（`planOfficeFurniture` 的 row/grid/backToBack/faceToFace/staggered/auto）由规划器原生切段：纵向通道切列轴、横向通道切排轴，每段独立拟合并居中；旋转候选把通道换算到局部坐标。「仅从二排起」只对未旋转的纵向通道生效，首排（对组模式的首个对组）按无该通道拟合，其深度以 `layoutSettings.firstBlockDepthMm` 随实例保存；无通道时原整体拟合逻辑逐行不变。
- 沿边、中央通道、会议居中和职员桌样式排布不切段，通道矩形作为硬阻挡并入既有避障（块间隙沿用原 aisleMm 语义，偏保守）；此时面板提示「仅从二排起」仅对课桌式生效。
- 确认校验 `confirmedLayoutProblem` 增加：家具外包压占通道矩形即拒绝；「仅从二排起」按已保存的 `firstBlockDepthMm` 豁免首排，无该记录的手动布局按全深从严。
- 通道增删改和画布拖动统一走 `store.updateZoneChannels`（`objectUpdate` 获得撤销与防抖持久化），使本空间家具 `layoutConfirmed` 失效并清空已提交方案，不影响其他空间；与禁放区变化使布局待核验的既有规则一致。
- 画布条带用蓝色斜纹区别于禁放区红框，只支持沿垂直轴向拖动（预览/提交分离，松手一次提交），宽度、位置数值与「仅从二排起」在排版面板编辑；面板通道直接读写 `zone.layoutPreferences`，不进面板本地副本，避免与画布拖动双写分叉。

## 验证结果（在 `frontend/` 执行）

| 检查 | 结果与边界 |
| --- | --- |
| `pnpm build` | 通过；无独立 lint/typecheck 配置，不作相应声称。 |
| `OUT_DIR=/tmp/rsdp-aisle-channels node scripts/e2e-aisle-channels.mjs`（新增） | 通过：加纵向通道预览分列且容量下降、应用后无压占；「仅从二排起」首排完整后续排断开且容量回升、`firstBlockDepthMm` 随实例保存；画布拖动松手提交方向正确；合法布局可确认、新增压占通道使确认失效且再确认被拒并提示；横向通道分排；撤销恢复通道列表；刷新后通道与条带保持。全部 API 模拟（含 `/api/auth/me`，与登录门禁后更新的既有脚本同一惯例）。 |
| `pnpm e2e:auto-layout` / `e2e-layout-planning` / `e2e-adaptive-layout` / `e2e-meeting-layout` | 通过；UI mock。四个脚本因登录门禁缺少 `/api/auth/me` mock 而无法进入，已按既有脚本惯例各补一行 mock（`e2e-meeting-layout.mjs` 无需补）。 |
| `pnpm e2e:single-space` | 未通过，为 LAYOUT-009/LAYOUT-019 已记录的既有失败（尺寸标注 line 数量断言，第 89 行 27!==0），发生在任何通道操作之前，与本任务无关；已同样补 auth mock 后确认失败点未变化。 |
| 纯函数抽查（esbuild 打包 `planOfficeFurniture` 直跑） | 16000×10000 空间、1400×600 模块：基线 66 件、全深通道 60 件 0 压占、「二排起」61 件仅首排 1 格跨通道、`firstBlockDepthMm=1550`。 |
| `git diff --check` | 通过。 |

测试过程发现并已修正：面板 `channels` 声明晚于 `request` 导致 TDZ 运行错误（专项脚本首轮捕获）；e2e 断言曾把设计副本负 Y 坐标当 0 起点分类首排，已改为相对包围盒换算。`e2e-adaptive-layout.mjs` 截图路径写死 Windows 临时目录，在 macOS 上生成相对路径垃圾目录 `frontend/C:/`，已清理；该脚本既有问题未在本任务修复。

本轮仅前端改动，未运行 Java/CAD 测试（无后端改动），未做真实数据库集成与真实识别验收；通道宽度等数值为可调整策略，不声称符合建筑疏散规范。弧形布局与斜向通道、通道通畅性的服务端核验不在本任务范围。

## 补记（2026-10-22）：通道两侧净距 clearanceMm

应用户"简洁优化操作"要求，每条通道新增可选 `clearanceMm`（两侧净距，毫米，缺省 0）：排版切段、阻挡矩形与确认校验统一按 `widthMm + 2×clearanceMm` 的禁放带处理，用于表达"桌子与通道之间再留空档"，无需用户手算加宽通道。`LayoutChannel` 加字段；`channelRects`/`axisSegments` 在垂直于走向的两侧各扩 `clearanceMm`；`validateChannels` 要求净距为不小于 0 的有限数；面板每条通道加「两侧净距 mm」输入；画布条带按扩展后禁放带渲染，标签在净距大于 0 时显示 `宽度+2×净距`。旧数据无该字段按 0 处理，行为不变。

验证：`pnpm build` 通过；专项脚本新增净距用例（设 300 后家具均不压占扩展带、还原后布局恢复贴近通道；原断言因净距不改变切段列数由"容量下降"改为位置断言）全部通过；自动排版/规划/自适应/会议四组回归通过；`git diff --check` 通过。测试修订：脚本相交判定改用与 `officeFurnitureFootprint` 一致的旋转占地（旋转 90° 时存储 x/w 与物理占地不同，原裸 x/w 判定会误报压占），确认校验此前已用旋转占地、无误判。未运行 Java/CAD 测试（无后端改动）；净距数值同为策略非规范。

同日再补（通道不得超出空间）：用户指出通道不应超出空间范围。新增 `clampChannelsToZone`/`channelOffsetRange`（layoutChannels.ts）：`updateZoneChannels` 统一把每条通道钳制到包围盒内——禁放带（含净距）不越界，拖动提交、面板位置输入、增大净距均走同一钳制；净距或宽度占满跨度时不强行挪动（留给校验报错）。画布拖动预览同步钳制，条带拖动过程中不再 visually 移出空间；`channelRects` 渲染/阻挡矩形最终再裁剪到包围盒，覆盖旧数据等越界残留。专项脚本新增用例：大幅向左拖出包围盒后位置钳制到左缘、含净距时钳制到 左缘+净距；构建与四组回归同步通过。凹形空间按包围盒钳制，条带在缺角处仍可能画出轮廓外（与禁放区矩形同一取舍），多边形裁剪留待后续。
