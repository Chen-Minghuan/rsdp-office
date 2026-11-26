# CAT-012 商品库智能搜索与筛选器

## 目标

商品库在名称/编码搜索之外，提供产品等级、空间类型、产品分类、材质、颜色、尺寸、桌脚、收纳八类筛选；
快捷下拉与「全部筛选」双栏弹层共用同一应用态；筛选、计数与分页统一由后端
`/api/v1/products/page` 计算，前端不再本地过滤。

## 已确认渲染与交互

- 桌面 1600×1000：弹层居中（1040×700 内），左栏八组导航带已选计数徽标，右栏三列选项带计数，
  紫色主题；页脚「清空 / 取消 / 确定（N）」完整可见。
- 移动 390×844：弹层全屏、双栏（导航 112px），选项两列，零计数选项置灰禁用，
  页脚完整位于视口内且无横向溢出（e2e 断言 + 截图核对，截图为临时产物已删除）。
- 弹层为草稿态：仅「确定」成功才应用；取消/Esc/遮罩关闭回滚；确定失败保留草稿与旧列表并在弹层内 alert。

## 派生规则（后端 ProductFilterSupport）

- 空间类型/收纳：名称优先、描述兜底的关键字规则；收纳单标签按 活动小柜→长副柜→高柜→背柜→支撑柜→副柜→抽屉 优先级。
- 产品分类：沙发→SOFA、椅→CHAIR、台/桌/茶几→DESK、柜→CABINET，其余 UNCLASSIFIED（如「单人位」）。
- 颜色：`colorPrimaryName` 按 `+` 拆分，仅保留 13 个已核验色词。
- 桌脚：钢脚/支撑柜 多标签，无则 UNSPECIFIED。
- 材质与尺寸：必须同一变体同时满足（materialText 与 widthMm）。
- facets 计数基于「视图 + 搜索 + 在售状态」基础集，不随高级筛选收缩；零计数选项 disabled。

## 接口

- `GET /api/v1/products/page`：新增 `levels/spaceTypes/productTypes/materials/colors/widthsMm/legTypes/storageTypes`
  重复参数与 `refresh`；响应新增 `facets.groups`（code/label/count/disabled）。
- 校验：页码/页大小/搜索长度/状态枚举/各码白名单，非法返回业务错误（HTTP 200 + code 400）。

## 实际修改文件

- 后端：`CatalogProductPageRequest`、`CatalogFacetsDto`、`CatalogProductFilterService`、`ProductFilter`、
  `ProductFilterSupport`、`CatalogFilterProduct`（新增）；`ProductImportController`、`ProductQueryService`、
  `RsdpCatalogGateway`、`CatalogProductPageDto`、`ProductDetailDto`、`ProductListItemDto`（修改）；
  测试 `ProductFilterSupportTest`、`CatalogProductFilterServiceTest`、`ProductImportControllerTest`、`RsdpCatalogGatewayTest`。
- 前端：`utils/productFilter.ts`、`components/ProductAdvancedFilterDialog.vue`、`scripts/e2e-product-filters.mjs`、
  `scripts/test-product-filter.mjs`（新增）；`pages/products/index.vue`、`composables/useOfficeApi.ts`、
  `types/imported-products.ts`、`scripts/e2e-workflows.mjs`、`package.json`（修改）。

## 验证记录（2026-11-25）

| 命令 | 结果 |
| --- | --- |
| `node scripts/test-product-filter.mjs`（frontend） | 通过 |
| `mvn -f backend/pom.xml test` | 退出码 0，0 失败 0 错误 |
| `pnpm build`（frontend） | 退出码 0（Build complete in 31.9s） |
| `pnpm e2e:product-filters`（拦截 API） | 通过：草稿/取消、标签单删、快捷同步、失败重试、过期响应、refresh、移动布局 |
| `pnpm e2e:workflows`（拦截 API） | 通过（已迁移新筛选契约与分页 mock） |
| `pnpm e2e:favorites-page` | 首跑 line 326 偶发失败（0≠1），重跑通过 |
| `MOCK_API=1 pnpm e2e:smoke` | 通过 |

UI 回归与冒烟均为接口拦截/模拟，不作为真实接口验收证据。

## 未覆盖与阻塞

- 计划 Step 3 的真实只读核对未执行：本地 `mvn spring-boot:run` 启动失败（Maven MojoExecutionException，
  本地数据源/环境不可用），Office `/api/v1/products/page` 与测试主库的在线核对阻塞，待环境恢复后补做。
- 未配置独立 lint/typecheck 与统一 CI，未声称执行。
- 性能：筛选在授权目录快照上内存计算并缓存（refresh 可回源），未做大规模主库压测。

## 2026-11-25 追加：右侧抽屉 UI 与尺寸区间

- 「全部筛选」由居中双栏弹窗改为淘宝式右侧抽屉（桌面 400px、移动全屏）：横向分组页签（‹ › 翻页）、八组纵向堆叠、三列选项、超过 6 项的组支持「展开全部」。草稿态语义不变（确定成功才应用、失败保留草稿）。
- 按用户要求，筛选选项不再显示商品计数（含快捷下拉），零计数选项仍禁用；已选数量（确定（N）、按钮徽标）保留。
- 尺寸由 `widthsMm` 精确值改为 `widthRanges` 区间码：`LE_1000 / R1000_1200 / R1200_1600 / R1600_2000 / R2000_2400 / R2400_2800 / GT_2800 / UNSPECIFIED`，边界为大于下限、含上限；「材质+尺寸同一规格同时满足」规则不变。
- 区间依据 2026-11-25 测试主库实读（185 商品、23 种宽度）：1m及以下=12、1–1.2m=22、1.2–1.6m=51、1.6–2m=17、2–2.4m=27、2.4–2.8m=35、2.8m以上=18、未标注=3，合计 185。区间定义集中于 `ProductFilterSupport.WIDTH_RANGES`。
- 验证：后端区间边界与同规格匹配单测通过；`mvn -f backend/pom.xml test` 退出码 0；`pnpm build` 退出码 0；`node scripts/test-product-filter.mjs` 通过；`e2e:product-filters`（含尺寸区间新用例）、`e2e:workflows`、`e2e:favorites-page`、`MOCK_API=1 e2e:smoke` 全部通过。UI 回归均为接口拦截。

## 2026-11-25 交互调整：点选立即应用（取代草稿态）

- 按用户反馈，抽屉内点选/取消选项立即请求并刷新商品列表，抽屉保持打开可继续多选；底部改为「清空 / 完成」，不再有「确定（N）」草稿确认。清空同样立即生效。
- 应用失败时列表与筛选标签保持旧值，抽屉内 alert 提示并回滚该次点选；重试点选即可恢复。快捷下拉、标签单删与抽屉共用同一应用态。
- 验证：`e2e:product-filters` 按即时应用语义重写（点选出结果、再点恢复、回显、清空、失败回滚重试、区间、过期响应、移动端）全部通过；`e2e:workflows`、`e2e:favorites-page`、`MOCK_API=1 e2e:smoke` 通过；`pnpm build` 退出码 0。后端无改动，沿用上一轮全量测试退出码 0。

## 2026-11-25 非模态与中性样式

- 抽屉改为非模态（`dialog.show()`）：打开时商品库不遮罩、可直接点击商品进入详情；Esc 由组件监听关闭。
- 选中选项与分组页签去掉紫色高亮，改为与商品库一致的中性色（灰边框+浅米色底），仅保留已选数量与筛选标签作为状态提示。
- 验证：`e2e:product-filters` 新增"抽屉打开时点击商品卡进入详情"用例，全部通过；workflows/favorites/smoke 通过；`pnpm build` 退出码 0。

## 2026-11-25 隐藏「未归类 / 未标注」选项

- facets 不再输出 spaceTypes/legTypes/storageTypes/widthRanges 的 UNSPECIFIED 与 productTypes 的 UNCLASSIFIED 选项（前后端 mock 同步）；内部派生与参数校验保留，手工传码仍可匹配。
- 这些商品不隐藏：仍出现在未筛选列表与总数中，也可被其他条件命中；其余选项计数不变。
- 验证：`mvn -f backend/pom.xml test` 退出码 0（含 storageTypes/widthRanges 无 UNSPECIFIED 断言）；`e2e:product-filters`、`e2e:workflows`、`MOCK_API=1 e2e:smoke` 通过；`e2e:favorites-page` 首跑偶发失败、重跑通过；`pnpm build` 退出码 0。

## 2026-11-25 分组页签铺满

- 抽屉顶部分组页签由 3 个改为 4 个并均铺满整行（‹ › 仍可翻到其余组），消除右侧空位。`e2e:product-filters`、`e2e:workflows` 通过，`pnpm build` 退出码 0。

## 2026-11-25 修复搜索空编码 NPE

- 真实环境搜索报 `系统异常`：`matchesSearch` 用 `List.of` 组装商品名/rspuCode/externalCode/rspuId，主库部分商品编码为 null，`List.of` 不允许 null 直接抛 NPE。改为 `Stream.of`。
- 新增 `searchesProductsWithMissingCodes` 单测（编码为 null 的商品按名称搜索正常返回）。`mvn -f backend/pom.xml test` 全量退出码 0。

## 2026-11-25 嵌入式侧栏：内容左移不遮挡

- 打开「全部筛选」时 `.catalog-split` 增加 `margin-right:400px`（≤640px 为 0），商品区与收藏栏整体左移，不再被抽屉覆盖；抽屉上沿锚定工具栏/筛选标签行底部（`drawerTop` 随标签行与窗口尺寸变化），不再覆盖页面头部。
- dialog 高度改为 `top+bottom+height:auto` 锚定（修复 fit-content 把底部按钮撑出视口的问题）。
- E2E 新增"抽屉从工具栏下方开始、商品区右缘不超过抽屉左缘"断言；筛选/workflows/favorites/smoke 全部通过，`pnpm build` 退出码 0。

## 2026-11-25 页签滚动追踪与箭头样式

- 抽屉内容滚动时页签自动追踪当前分组（顶部阈值 16px；触底直接选中最后一组并保证页签可见），点击页签的平滑滚动期间暂停追踪避免抖动。
- 左右翻页按钮改为细线 SVG chevron + 轻量 hover 底色，风格对齐淘宝参考视频。
- E2E 新增"滚到底部页签=收纳分类、滚回顶部=产品等级"断言；筛选/workflows/smoke 通过，favorites 偶发后重跑通过，`pnpm build` 退出码 0。

## 2026-11-25 筛选面板与收藏侧栏互换 + 按钮开合

- 筛选面板从视口右侧固定抽屉改为 `.catalog-split` 内嵌网格列，顺序为 商品区 | 筛选面板(400px) | 自选组合+收藏夹，即两者位置互换；641–1099px 仍为右侧固定抽屉，≤640px 全屏。
- 面板上沿对齐筛选工具栏区域顶部（`--af-top` 决定高度并 sticky 跟随），补齐此前工具栏下方的空白。
- 「全部筛选」按钮改为开合切换：再点一次即收起。E2E 新增按钮二次点击收起断言；筛选/workflows/favorites/smoke 全部通过，`pnpm build` 退出码 0。

## 2026-11-25 合并 upstream/main：browse 链路整合

- 上游将商品库改为 `/products/browse`（公共库+本地商品混排、本地分类、quick 分类/颜色）。合并后八组高级筛选接入同一 browse 链路：`ProductCatalogBrowseService.page` 增加 `CatalogProductPageRequest`，有高级筛选时走 `rsdp.listFilterProducts` + `ProductFilter`（本地商品不参与高级筛选）；`/browse/facets?search&status` 返回 `advanced` 分组（`CatalogProductFilterService.facets` 复用基础集）。
- 页面保留上游 quick 分类/颜色与本地商品展示；高级面板、标签、即时应用/失败回滚、内嵌互换布局全部保留；resetFilters 同时清空 quick 条件。`/products/page` 旧入口保留未删。
- 冲突文件：RsdpCatalogGateway（双方方法并存）、index.vue（状态/监听/模板融合）、useOfficeApi、workflows、e2e-product-filters（重写为 browse mock）、README/status。
- 验证：后端全量 `mvn test` 退出码 0（browse 测试更新签名并新增高级筛选用例）；`pnpm build` 退出码 0；筛选/workflows/favorites/smoke 四套 E2E 全部通过。
