# 智能搜索和筛选器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Office 商品库实现八类真实数据筛选、后端统计后分页，以及桌面双栏/手机全屏的“全部筛选”弹层。

**Architecture:** Office 后端复用 RSDP 列表、详情和规格接口，在授权目录内计算直接与派生属性，先筛选和统计再分页。前端以 `appliedFilters` 驱动列表，以 `draftFilters` 编辑弹层，桌面和手机共享同一份状态。

**Tech Stack:** Java 21、Spring Boot、JUnit 5、Mockito、Nuxt 4、Vue 3、TypeScript、Playwright。

**Spec:** `docs/superpowers/specs/2026-11-24-smart-search-filter-design.md`

## Global Constraints

- 不回写 RSDP，不新增主库字段，不补录商品或字典，不修改收藏和自选组合行为。
- 19 个位类不并入桌类，2 个钢脚配件和 1 个屏风保持未归类。
- 后端必须先处理全部授权商品再分页，禁止只筛当前页。
- 弹层点击只改草稿；确定成功后才应用，取消必须回退。
- 数组参数统一使用同名重复参数；颜色、桌脚计数可重叠，空间和收纳计数互斥。
- 使用现有紫色主题与已确认渲染；手机 390×844 底部按钮必须完整可见。
- 不引入新依赖，不自动提交或推送；只有用户明确要求时才执行 Git 提交或推送。
- 保留 `docs/superpowers/specs/2026-11-23-self-select-combo-design.md` 和其他已有本地改动。

---

### Task 1: 商品派生属性与匹配规则

**Files:**
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductFilterSupport.java`
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductFilter.java`
- Test: `backend/src/test/java/com/rsdp/officeproduct/importer/service/ProductFilterSupportTest.java`

**Interfaces:**
- Consumes: 商品名称、主色、描述、规格材质和毫米宽度。
- Produces: `ProductFilterSupport.Source`、`ProductFilterSupport.Attributes`、`ProductFilter`、`ProductFilterSupport.attributes(...)` 与 `ProductFilterSupport.Attributes.matches(...)`，供 Task 3 使用。

- [ ] **Step 1: 写失败测试，覆盖映射、未归类、颜色和同规格匹配**

```java
package com.rsdp.officeproduct.importer.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ProductFilterSupportTest {
    @Test
    void derivesApprovedTaxonomyWithoutGuessing() {
        var source = new ProductFilterSupport.Source("B", "1.4米双人位", "蜜芽胡桃+雅士白+灯带", "", List.of());
        var row = ProductFilterSupport.attributes(source);
        assertEquals("UNCLASSIFIED", row.productType());
        assertEquals("UNSPECIFIED", row.spaceType());
        assertEquals(Set.of("蜜芽胡桃", "雅士白"), row.colorNames());
        assertEquals("UNSPECIFIED", row.storageType());
    }

    @Test
    void appliesStoragePriorityAndMultiLabels() {
        var source = new ProductFilterSupport.Source("B", "1.6米主管台", "纳柯", "支撑柜带抽屉，钢脚", List.of());
        var row = ProductFilterSupport.attributes(source);
        assertEquals("MANAGER", row.spaceType());
        assertEquals("DESK", row.productType());
        assertEquals("SUPPORT_CABINET", row.storageType());
        assertEquals(Set.of("STEEL", "SUPPORT_CABINET"), row.legTypes());
    }

    @Test
    void requiresMaterialAndWidthOnTheSameVariant() {
        var variants = List.of(
            new ProductFilterSupport.Source.Variant("PB", new BigDecimal("1200")),
            new ProductFilterSupport.Source.Variant("WO", new BigDecimal("1400")));
        var row = new ProductFilterSupport.Attributes("B", "STAFF", "DESK", Set.of(), Set.of(), "UNSPECIFIED", variants);
        var matching = new ProductFilter(List.of(), List.of(), List.of(), List.of("PB"), List.of(), List.of(1200), List.of(), List.of());
        var wrongPair = new ProductFilter(List.of(), List.of(), List.of(), List.of("PB"), List.of(), List.of(1400), List.of(), List.of());
        assertTrue(row.matches(matching));
        assertFalse(row.matches(wrongPair));
    }
}
```

- [ ] **Step 2: 运行测试并确认因类型不存在而失败**

Run: `mvn -f backend/pom.xml test -Dtest=ProductFilterSupportTest`  
Expected: 编译失败，提示 `ProductFilterSupport.Source`、`ProductFilterSupport.Attributes`、`ProductFilter` 不存在。

- [ ] **Step 3: 实现纯派生与匹配模块**

在 `ProductFilterSupport.java` 中定义以下公开契约，映射词表和优先级严格复制 Spec §5：

```java
public final class ProductFilterSupport {
    public record Source(String productLevel, String productName, String colorPrimaryName,
                         String description, List<Variant> variants) {
        public record Variant(String materialText, BigDecimal widthMm) {}
    }
    public record Attributes(String productLevel, String spaceType, String productType,
                             Set<String> colorNames, Set<String> legTypes, String storageType,
                             List<Source.Variant> variants) {
        public boolean matches(ProductFilter filter) {
            return filter.matches(this);
        }
    }
    public static Attributes attributes(Source source) {
        return new Attributes(source.productLevel(), spaceType(source.productName()),
            productType(source.productName()), colorNames(source.colorPrimaryName()),
            legTypes(source.productName(), source.description()),
            storageType(source.productName(), source.description()), List.copyOf(source.variants()));
    }
    public static String spaceType(String name) { return ProductFilterMappings.spaceType(name); }
    public static String productType(String name) { return ProductFilterMappings.productType(name); }
    public static Set<String> colorNames(String raw) { return ProductFilterMappings.colorNames(raw); }
    public static Set<String> legTypes(String name, String description) { return ProductFilterMappings.legTypes(name, description); }
    public static String storageType(String name, String description) { return ProductFilterMappings.storageType(name, description); }
}
```

同文件增加私有静态内部类 `ProductFilterMappings`，实现以下确定规则：

```java
private static String firstMatch(String text, String... rules) {
    for (String rule : rules) {
        String[] parts = rule.split("=", 2);
        if (text.contains(parts[0])) return parts[1];
    }
    return "UNSPECIFIED";
}
```

- 空间按 `职员台→STAFF`、主管台→MANAGER、高管台→SENIOR_MANAGER、总裁台→PRESIDENT、会议台→MEETING、洽谈台→NEGOTIATION、前台→RECEPTION、茶台/茶水柜→PANTRY 的顺序匹配。
- 产品类型按沙发→SOFA、椅→CHAIR、台/桌/茶几→DESK、柜→CABINET，最后 UNCLASSIFIED。
- 颜色只保留 Spec §5.5 的 13 个明确颜色词，按 `+` 拆分后取交集。
- 桌脚按描述与名称是否包含钢脚、支撑柜生成零到两个标签，空时仅 UNSPECIFIED。
- 收纳先名称后描述，优先级为活动小柜、长副柜、高柜、背柜、支撑柜、副柜、抽屉，未命中为 UNSPECIFIED。

新增 `ProductFilter.java`，字段依次为 `levels/spaceTypes/productTypes/materials/colors/widthsMm/legTypes/storageTypes`，类型分别为 `List<String>`（`widthsMm` 为 `List<Integer>`）。`matches` 必须执行：

```java
boolean variantMatches = attributes.variants().stream().anyMatch(variant ->
    (materials.isEmpty() || materials.contains(variant.materialText()))
        && (widthsMm.isEmpty() || widthsMm.contains(variant.widthMm().intValueExact())));
return (levels.isEmpty() || levels.contains(attributes.productLevel()))
    && (spaceTypes.isEmpty() || spaceTypes.contains(attributes.spaceType()))
    && (productTypes.isEmpty() || productTypes.contains(attributes.productType()))
    && (colors.isEmpty() || attributes.colorNames().stream().anyMatch(colors::contains))
    && (legTypes.isEmpty() || attributes.legTypes().stream().anyMatch(legTypes::contains))
    && (storageTypes.isEmpty() || storageTypes.contains(attributes.storageType()))
    && variantMatches;
```

空规格列表只在材质和宽度均为空时允许匹配；否则返回 false。

- [ ] **Step 4: 运行专项测试并补充边界用例**

Run: `mvn -f backend/pom.xml test -Dtest=ProductFilterSupportTest`  
Expected: 3 个初始用例通过。追加测试：单人位职员台仍为 DESK、1.4米双人位为 UNCLASSIFIED、长副柜优先于副柜、无效宽度为空时 PB+宽度不能匹配。

- [ ] **Step 5: 检查本任务差异**

Run: `git diff --check -- backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductFilterSupport.java backend/src/test/java/com/rsdp/officeproduct/importer/service/ProductFilterSupportTest.java`  
Expected: 无输出。只读复核，不提交。

---

### Task 2: 主库商品与规格快照

**Files:**
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/ProductListItemDto.java`
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/ProductDetailDto.java`
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/service/RsdpCatalogGateway.java`
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/service/CatalogFilterProduct.java`
- Test: `backend/src/test/java/com/rsdp/officeproduct/importer/service/RsdpCatalogGatewayTest.java`

**Interfaces:**
- Consumes: `RsdpCatalogGateway.listProducts(viewMode)`、`getProductDetail(rspuId)`、现有 30 秒缓存和 8 并发模式。
- Produces: `CatalogFilterProduct(summary, description, variants)` 与 `RsdpCatalogGateway.listFilterProducts(viewMode, refresh)`。

- [ ] **Step 1: 添加等级、描述和缓存失败测试**

```java
@Test
void loadsFilterSnapshotAndCachesPerView() throws Exception {
    RsdpClient client = mock(RsdpClient.class);
    var gateway = new RsdpCatalogGateway(client, new ObjectMapper());
    when(client.get("/api/v1/products/catalog-page?page=1&size=500&viewMode=full"))
        .thenThrow(new BusinessException(404, "missing"));
    when(client.get("/api/v1/products?page=1&size=500&viewMode=full&status=active")).thenReturn(json.readTree("""
        {"total":1,"rows":[{"rspuId":"P1","productName":"1.4米职员台","productLevel":"B","status":"active"}]}
        """));
    when(client.get("/api/v1/products/P1")).thenReturn(json.readTree("""
        {"rspu":{"rspuId":"P1","productName":"1.4米职员台","productLevel":"B","description":"钢脚","status":"active"}}
        """));
    when(client.get("/api/v1/products/P1/variants")).thenReturn(json.readTree("""
        [{"variantId":"V1","productLevel":"B","materialText":"PB","dimensions":"{\"w\":1400,\"unit\":\"mm\"}","status":"active"}]
        """));

    var first = gateway.listFilterProducts("full", false);
    var second = gateway.listFilterProducts("full", false);
    assertEquals("B", first.getFirst().summary().getProductLevel());
    assertEquals("钢脚", first.getFirst().description());
    assertEquals("PB", first.getFirst().variants().getFirst().getMaterialText());
    assertEquals(new BigDecimal("1400"), first.getFirst().variants().getFirst().getWidthMm());
    assertSame(first, second);
    verify(client, times(1)).get("/api/v1/products/P1");
}
```

- [ ] **Step 2: 运行并确认缺少 `listFilterProducts`**

Run: `mvn -f backend/pom.xml test -Dtest=RsdpCatalogGatewayTest#loadsFilterSnapshotAndCachesPerView`  
Expected: 编译失败，提示方法不存在。

- [ ] **Step 3: 补充 DTO 字段和快照读取**

给 `ProductListItemDto`、`ProductDetailDto`、`ProductDetailDto.Variant` 增加 `String productLevel`，并在 `toListItem` 与 `getProductDetail` 中映射 `productLevel`。给 `ProductDetailDto.Variant` 增加 `BigDecimal widthMm`，使用现有 `readMainDimensions(v.path("dimensions"))[0]` 映射，供筛选服务构造规格属性。

创建：

```java
public record CatalogFilterProduct(ProductListItemDto summary, String description,
                                   List<ProductDetailDto.Variant> variants) {
    public CatalogFilterProduct {
        variants = List.copyOf(variants);
    }
}
```

在 `RsdpCatalogGateway` 增加：

```java
public List<CatalogFilterProduct> listFilterProducts(String viewMode, boolean refresh) {
    if ("none".equals(viewMode)) return List.of();
    FilterSnapshotEntry cached = freshFilterSnapshot(viewMode, refresh);
    if (cached != null) return cached.rows();
    List<ProductListItemDto> summaries = listProducts(viewMode);
    List<CatalogFilterProduct> rows = loadFilterSnapshot(summaries);
    filterSnapshotCache.put(viewMode, new FilterSnapshotEntry(List.copyOf(rows), System.currentTimeMillis()));
    return rows;
}
```

`loadFilterSnapshot` 使用 `Executors.newFixedThreadPool(8, Thread.ofVirtual().factory())`，每个任务调用 `getProductDetail(summary.getRspuId())` 并返回 `CatalogFilterProduct`。按 `fetchOfficeProducts` 的 `CompletableFuture` 模式防止同一视图并发重复刷新，异常时完成异常并移除 in-flight。快照 TTL 使用现有 `OFFICE_PRODUCTS_CACHE_TTL_MS`，`refresh=true` 跳过旧缓存，公共库/工厂视图使用不同键。

- [ ] **Step 4: 运行网关专项测试**

Run: `mvn -f backend/pom.xml test -Dtest=RsdpCatalogGatewayTest`  
Expected: 新用例及已有兼容、权限、尺寸用例全部通过。

- [ ] **Step 5: 检查差异**

Run: `git diff --check -- backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/ProductListItemDto.java backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/ProductDetailDto.java backend/src/main/java/com/rsdp/officeproduct/importer/service/RsdpCatalogGateway.java backend/src/main/java/com/rsdp/officeproduct/importer/service/CatalogFilterProduct.java backend/src/test/java/com/rsdp/officeproduct/importer/service/RsdpCatalogGatewayTest.java`  
Expected: 无输出。只读复核，不提交。

---

### Task 3: 后端筛选、facets 和分页契约

**Files:**
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/dto/request/CatalogProductPageRequest.java`
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/CatalogFacetsDto.java`
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/dto/response/CatalogProductPageDto.java`
- Create: `backend/src/main/java/com/rsdp/officeproduct/importer/service/CatalogProductFilterService.java`
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/service/ProductQueryService.java`
- Modify: `backend/src/main/java/com/rsdp/officeproduct/importer/controller/ProductImportController.java`
- Test: `backend/src/test/java/com/rsdp/officeproduct/importer/service/CatalogProductFilterServiceTest.java`
- Test: `backend/src/test/java/com/rsdp/officeproduct/importer/controller/ProductImportControllerTest.java`

**Interfaces:**
- Consumes: Task 1 的派生匹配和 Task 2 的快照。
- Produces: `CatalogProductPageRequest`、`CatalogFacetsDto`、扩展后的 `/api/v1/products/page` 响应。

- [ ] **Step 1: 写筛选、统计和请求绑定失败测试**

```java
@Test
void filtersBeforePaginationAndCountsStorageWithoutOverlap() {
    var service = new CatalogProductFilterService(gateway);
    var request = new CatalogProductPageRequest();
    request.setPage(1);
    request.setSize(1);
    request.setStorageTypes(List.of("SUPPORT_CABINET"));
    var page = service.pageProducts("full", request, false);
    assertEquals(1, page.total());
    assertEquals(1, page.rows().size());
    assertEquals(2, page.facets().groups().get("storageTypes").stream().mapToLong(CatalogFacetsDto.Option::count).sum());
}

@Test
void rejectsInvalidWidthAndBindsRepeatedLevels() throws Exception {
    mvc.perform(get("/api/v1/products/page").param("widthsMm", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/products/page").param("levels", "S", "A"))
        .andExpect(status().isOk());
    verify(productQueryService).pageProducts(argThat(request -> request.getLevels().equals(List.of("S", "A"))));
}
```

服务测试准备两个 `CatalogFilterProduct`：一个支撑柜商品、一个副柜商品，分别带 PB/1200 与 PB/1400 规格；另加一组跨规格错误配对，验证 Task 1 的约束不会被分页绕过。

- [ ] **Step 2: 运行并确认契约不存在**

Run: `mvn -f backend/pom.xml test -Dtest=CatalogProductFilterServiceTest,ProductImportControllerTest`  
Expected: 编译失败，提示请求、facets 或筛选服务类型不存在。

- [ ] **Step 3: 实现请求、响应与筛选流程**

`CatalogProductPageRequest` 使用 `@Data`，字段为：

```java
private boolean managed = false;
private int page = 1;
private int size = 20;
private String search = "";
private String status = "";
private boolean refresh = false;
private List<String> levels = List.of();
private List<String> spaceTypes = List.of();
private List<String> productTypes = List.of();
private List<String> materials = List.of();
private List<String> colors = List.of();
private List<Integer> widthsMm = List.of();
private List<String> legTypes = List.of();
private List<String> storageTypes = List.of();
```

增加 `validate()`：页码≥1、大小 1–100、搜索≤120、状态仅空/active/other、每个数组≤50、颜色≤64、材质≤128、宽度为 1–100000 的整数。等级/空间/产品类型/桌脚/收纳仅接受 Spec 编码。增加 `toFilter()`：

```java
public ProductFilter toFilter() {
    return new ProductFilter(levels, spaceTypes, productTypes, materials, colors, widthsMm, legTypes, storageTypes);
}
```

`CatalogFacetsDto` 定义：

```java
public record CatalogFacetsDto(Map<String, List<Option>> groups) {
    public record Option(String code, String label, long count, boolean disabled) {}
    public static CatalogFacetsDto empty() { return new CatalogFacetsDto(Map.of()); }
}
```

`CatalogProductPageDto` 在 `rows` 后增加 `CatalogFacetsDto facets`，`empty` 使用 `CatalogFacetsDto.empty()`；更新网关中原有两个构造点。

`CatalogProductFilterService.pageProducts(view, request, refresh)` 依次执行：

```java
List<CatalogFilterProduct> snapshot = refresh
    ? rsdpCatalogGateway.listFilterProducts(view, true)
    : rsdpCatalogGateway.listFilterProducts(view, false);
List<CatalogFilterProduct> base = snapshot.stream()
    .filter(row -> matchesSearch(row, request.getSearch()))
    .filter(row -> matchesStatus(row, request.getStatus()))
    .toList();
CatalogFacetsDto facets = buildFacets(base);
List<CatalogFilterProduct> matching = base.stream()
    .filter(row -> ProductFilterSupport.attributes(toSource(row)).matches(request.toFilter()))
    .toList();
int start = (int) Math.min((request.getPage() - 1L) * request.getSize(), matching.size());
List<ProductListItemDto> rows = matching.subList(start, Math.min(start + request.getSize(), matching.size()))
    .stream().map(CatalogFilterProduct::summary).toList();
long active = snapshot.stream().filter(row -> "active".equals(row.summary().getStatus())).count();
return new CatalogProductPageDto(matching.size(), snapshot.size(), active, request.getPage(), request.getSize(), rows, facets);
```

`buildFacets` 使用 Spec §6 的基础集合，等级固定 S/A/B/C，其他选项从真实属性生成并补充 UNSPECIFIED/未标注；宽度按数值升序，其他选项按 Spec 顺序。`label` 使用中文显示名，`disabled=count==0`。快照行转换使用：

```java
private ProductFilterSupport.Source toSource(CatalogFilterProduct row) {
    var summary = row.summary();
    return new ProductFilterSupport.Source(summary.getProductLevel(), summary.getProductName(),
        summary.getColorPrimaryName(), row.description(),
        row.variants().stream()
            .map(variant -> new ProductFilterSupport.Source.Variant(variant.getMaterialText(), variant.getWidthMm()))
            .toList());
}
```

`ProductQueryService` 只负责 `resolveView`、调用 `request.validate()` 和筛选服务；`ProductImportController.page` 改为 `@ModelAttribute CatalogProductPageRequest request`。

- [ ] **Step 4: 运行后端契约测试**

Run: `mvn -f backend/pom.xml test -Dtest=CatalogProductFilterServiceTest,ProductImportControllerTest,RsdpCatalogGatewayTest`  
Expected: 全部通过。

- [ ] **Step 5: 检查差异**

Run: `git diff --check -- backend/src/main backend/src/test`  
Expected: 无输出。只读复核，不提交。

---

### Task 4: 前端筛选类型、API 与纯状态工具

**Files:**
- Modify: `frontend/app/types/imported-products.ts`
- Modify: `frontend/app/composables/useOfficeApi.ts`
- Create: `frontend/app/utils/productFilter.ts`
- Test: `frontend/scripts/test-product-filter.mjs`

**Interfaces:**
- Consumes: Task 3 的重复参数和 `facets.groups`。
- Produces: `CatalogProductFilters`、`CatalogFacets`、`emptyProductFilters`、`countProductFilters`、`cloneProductFilters`、`toggleProductFilter`。

- [ ] **Step 1: 写状态工具失败测试**

```javascript
import assert from 'node:assert/strict'
import { emptyProductFilters, countProductFilters, cloneProductFilters, toggleProductFilter } from '../app/utils/productFilter.ts'
const initial = emptyProductFilters()
assert.equal(countProductFilters(initial), 0)
const next = toggleProductFilter(initial, 'spaceTypes', 'STAFF')
assert.deepEqual(next.spaceTypes, ['STAFF'])
assert.equal(initial.spaceTypes.length, 0)
assert.equal(countProductFilters(next), 1)
assert.deepEqual(toggleProductFilter(next, 'spaceTypes', 'STAFF').spaceTypes, [])
const cloned = cloneProductFilters(next)
assert.notEqual(cloned.spaceTypes, next.spaceTypes)
```

- [ ] **Step 2: 运行并确认模块不存在**

Run: `node scripts/test-product-filter.mjs`（工作目录 `frontend/`）  
Expected: 找不到 `productFilter.ts`。

- [ ] **Step 3: 实现类型、重复参数和不可变状态工具**

在 `imported-products.ts` 增加：

```ts
export interface CatalogProductFilters {
  levels: string[]
  spaceTypes: string[]
  productTypes: string[]
  materials: string[]
  colors: string[]
  widthsMm: number[]
  legTypes: string[]
  storageTypes: string[]
}
export interface CatalogFacetOption { code: string; label: string; count: number; disabled: boolean }
export type CatalogFacetGroups = Record<keyof CatalogProductFilters, CatalogFacetOption[]>
export interface CatalogFacets { groups: CatalogFacetGroups }
```

`CatalogProductPage` 增加 `facets: CatalogFacets`，`ImportedProduct` 增加 `productLevel?: string | null`。

`productFilter.ts` 实现：

```ts
export const FILTER_KEYS = ['levels','spaceTypes','productTypes','materials','colors','widthsMm','legTypes','storageTypes'] as const
export function emptyProductFilters(): CatalogProductFilters {
  return { levels: [], spaceTypes: [], productTypes: [], materials: [], colors: [], widthsMm: [], legTypes: [], storageTypes: [] }
}
export function cloneProductFilters(value: CatalogProductFilters): CatalogProductFilters {
  return Object.fromEntries(FILTER_KEYS.map(key => [key, [...value[key]]])) as unknown as CatalogProductFilters
}
export function countProductFilters(value: CatalogProductFilters): number {
  return FILTER_KEYS.reduce((total, key) => total + value[key].length, 0)
}
export function toggleProductFilter<K extends keyof CatalogProductFilters>(value: CatalogProductFilters, key: K, option: CatalogProductFilters[K][number]): CatalogProductFilters {
  const copy = cloneProductFilters(value)
  const values = copy[key] as Array<CatalogProductFilters[K][number]>
  const index = values.indexOf(option)
  if (index >= 0) values.splice(index, 1)
  else values.push(option)
  return copy
}
```

`loadImportedProductPage` 的 options 增加 `filters?: CatalogProductFilters` 和 `refresh?: boolean`，对 `FILTER_KEYS` 逐项 `query.append(key, String(value))`，仅在对应数组非空时添加，`refresh=true` 时添加 `refresh=true`。

- [ ] **Step 4: 运行纯逻辑测试**

Run: `node scripts/test-product-filter.mjs`（工作目录 `frontend/`）  
Expected: 全部断言通过。

- [ ] **Step 5: 检查差异**

Run: `git diff --check -- frontend/app/types/imported-products.ts frontend/app/composables/useOfficeApi.ts frontend/app/utils/productFilter.ts frontend/scripts/test-product-filter.mjs`  
Expected: 无输出。只读复核，不提交。

---

### Task 5: 双栏弹层、主界面集成与 UI 回归

**Files:**
- Create: `frontend/app/components/ProductAdvancedFilterDialog.vue`
- Modify: `frontend/app/pages/products/index.vue`
- Modify: `frontend/app/utils/productFilter.ts`
- Create: `frontend/scripts/e2e-product-filters.mjs`
- Modify: `frontend/scripts/e2e-workflows.mjs`
- Modify: `frontend/package.json`

**Interfaces:**
- Consumes: Task 4 类型和工具、Task 3 响应。
- Produces: `ProductAdvancedFilterDialog` 的 `open/filters/facets/saving/error` props 与 `confirm/cancel` 事件；主页面 `appliedFilters`。

- [ ] **Step 1: 先写 UI 失败测试**

创建 `e2e-product-filters.mjs`，拦截 `/api/auth/me`、`/api/v1/products/page`、收藏、组合和项目接口。商品分页 mock 必须读取重复筛选参数，返回筛选后的 rows/total 和八类 facets。至少完成以下 Playwright 断言：

```javascript
await page.goto(baseUrl + '/products')
await page.getByRole('button', { name: '全部筛选', exact: true }).click()
await page.getByRole('dialog', { name: '全部筛选' }).getByRole('button', { name: '空间类型' }).click()
await page.getByRole('button', { name: /职员空间 61/ }).click()
await page.getByRole('button', { name: '取消', exact: true }).click()
assert.equal(await page.locator('.active-filter-chip').count(), 0)

await page.getByRole('button', { name: '全部筛选', exact: true }).click()
await page.getByRole('button', { name: '空间类型' }).click()
await page.getByRole('button', { name: /职员空间 61/ }).click()
await page.getByRole('button', { name: '确定（1）', exact: true }).click()
await page.getByText('职员空间', { exact: true }).waitFor()
assert.equal(await page.locator('.active-filter-chip').count(), 1)
assert.equal(new URL(page.url()).pathname, '/products')

await page.setViewportSize({ width: 390, height: 844 })
await page.getByRole('button', { name: /全部筛选/ }).click()
const footer = await page.locator('[data-testid="advanced-filter-footer"]').boundingBox()
assert.ok(footer.y + footer.height <= 844)
assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
```

再覆盖：确定失败保留草稿和旧列表、清空只改草稿、快捷下拉与弹层同步、Esc 取消、筛选后回到第一页、过期响应不覆盖新条件。

- [ ] **Step 2: 运行并确认入口不存在**

Run: `PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" node scripts/e2e-product-filters.mjs`（工作目录 `frontend/`）  
Expected: 找不到“全部筛选”按钮。

- [ ] **Step 3: 实现双栏弹层**

组件 props/emits：

```ts
interface Props {
  open: boolean
  filters: CatalogProductFilters
  facets: CatalogFacets
  saving: boolean
  error: string
}
const emit = defineEmits<{
  confirm: [value: CatalogProductFilters]
  cancel: []
}>()
```

使用原生 `<dialog ref="dialog" aria-label="全部筛选">`：打开时 `showModal()` 并克隆 props.filters 到 draft，关闭时调用 `dialog.close()` 并 emit cancel。实现 `role=dialog`、`aria-modal=true`、Esc、焦点返回、左侧八类导航、右侧选项卡、草稿已选徽标和固定底部按钮。

按钮语义：

```ts
function clearDraft() { draft.value = emptyProductFilters() }
function cancel() { emit('cancel') }
function confirm() { if (!props.saving) emit('confirm', cloneProductFilters(draft.value)) }
```

桌面 CSS 使用居中 `width:min(1040px,90vw)`、`height:min(700px,82vh)`、左 178px/右自适应；手机使用全屏、左 112px/右自适应、右侧两列、`grid-template-rows:58px minmax(0,1fr) auto`，底部增加 `data-testid="advanced-filter-footer"`。

- [ ] **Step 4: 集成主页面并统一后端筛选**

在 `index.vue`：

- 新增 `appliedFilters = ref<CatalogProductFilters>(emptyProductFilters())`、`advancedOpen/advancedSaving/advancedError`。
- 删除 `hasFacet` 驱动的前端过滤和当前页切片，`pageProducts` 固定为 `serverProducts`，`resultTotal` 固定为 `serverTotal`。
- `loadProducts(options?: { filters?: CatalogProductFilters; refresh?: boolean })` 把 filters 或 appliedFilters 传给 API，成功后才更新 appliedFilters、rows、total、facets 和关闭弹层。
- 快捷产品分类/主色使用 facets 选项；单选显示 code，多选显示 disabled 的“已选 N 项”占位，修改快捷下拉时替换该组为单选并请求。
- `applyAdvancedFilters(candidate)` 设置 `advancedSaving=true`，调用 `loadProducts({filters:candidate})`；成功后复制 candidate 到 appliedFilters 并关闭，失败保留 advancedError 和草稿。
- 已选标签使用 `.active-filter-chip`，单删和清空重新请求第一页。
- 刷新调用 `loadProducts({refresh:true})`，搜索、状态和分页继续使用 appliedFilters。
- 原 `facetProducts` 只保留为收藏可见商品数据源并改名 `visibleProducts`，不得再用于筛选。
- `catalog-filters` 增加全部筛选按钮，初始无标签；右侧组件结构不改。

在 `package.json` 增加 `"e2e:product-filters": "node scripts/e2e-product-filters.mjs"`。更新 `e2e-workflows.mjs` 的分页 mock，使原搜索、分类、主色、状态、分页和空/错误状态测试使用新 facets 契约。

- [ ] **Step 5: 运行 UI 专项与既有工作流**

Run: `PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:product-filters`（工作目录 `frontend/`）  
Expected: 全部专项断言通过。

Run: `PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:workflows`（工作目录 `frontend/`）  
Expected: 既有工作流通过。

- [ ] **Step 6: 浏览器检查已确认状态**

分别检查 1600×1000 主界面、1600×1000 弹层、390×844 弹层，确认紫色主题、三列/两列选项、右侧收藏不移动、无横向溢出、底部按钮完整可见，并检查浏览器 console 无 `pageerror`。

- [ ] **Step 7: 检查差异**

Run: `git diff --check -- frontend`  
Expected: 无输出。只读复核，不提交。

---

### Task 6: 文档、真实只读核对与全量回归

**Files:**
- Create: `docs/tasks/CAT-012-smart-search-filter.md`
- Modify: `docs/README.md`
- Modify: `docs/status.md`

**Interfaces:**
- Consumes: Task 1–5 的实际代码和测试输出。
- Produces: 可追溯的实现、验证和未覆盖范围记录。

- [ ] **Step 1: 运行全量后端与前端构建**

Run: `mvn -f backend/pom.xml test`  
Expected: 记录总测试数、失败数、错误数、跳过数，必须 0 失败、0 错误。

Run: `pnpm build`（工作目录 `frontend/`）  
Expected: 构建退出码 0；重复导入、大分块和弃用警告单独记录，不描述成独立 lint/typecheck。

- [ ] **Step 2: 运行相关 UI 回归**

依次在 `frontend/` 执行：

```bash
PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:product-filters
PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:workflows
PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:favorites-page
MOCK_API=1 PLAYWRIGHT_EXECUTABLE_PATH="C:/Users/34717/AppData/Local/ms-playwright/chromium-1228/chrome-win64/chrome.exe" pnpm e2e:smoke
```

Expected: 全部退出码 0。任何失败必须记录实际选择器和错误，不能标记验收通过。

- [ ] **Step 3: 对测试主库执行只读核对**

分别使用 RSDP 主库现有服务账号读取原始商品，以及 Office 当前开发会话调用 Office `/api/v1/products/page`；两套会话不得混用或输出凭据。只执行登录和 GET 请求。核对 185 个商品及规格的当前数量，并验证：

- 无筛选时返回八类 facets，等级 B 的数量为当前主档等级统计。
- `productTypes=DESK` 的结果与名称规则一致。
- `storageTypes=SUPPORT_CABINET` 的结果全部具有支撑柜主配置，facets 各收纳计数之和等于基础商品数。
- `widthsMm=1200&materials=PB` 的每个结果都有同一规格同时满足 PB 和 1200mm。
- 不执行 POST/PUT/PATCH/DELETE，不运行主库初始化或数据补录。

- [ ] **Step 4: 更新任务文档与状态**

`CAT-012-smart-search-filter.md` 写入：目标、已确认渲染、实际修改文件、派生规则、接口、真实测试命令与结果、UI mock 与真实接口边界、性能测量、未覆盖事项。`docs/README.md` 的商品资源改为八类筛选、双栏弹层和后端分页；`docs/status.md` 只记录已经实际完成和验证的内容。

- [ ] **Step 5: 最终差异检查**

Run: `git diff --check && git status --short --branch`  
Expected: 无 whitespace 错误；只包含本需求文件和原有本地文档，不包含截图、临时脚本、凭据或生成产物。只读复核，不提交或推送。
