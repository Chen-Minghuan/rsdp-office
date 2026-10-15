# Spatial Scene 数据协议规范 V1.2

更新日期：2026-10-14。文件名保留，正文协议升级为 V1.2。
本文定义目标契约；现有 `FloorPlanAnalysisResponse` 和数据库尚未完整支持这些字段。
配套：[系统架构](Spatial_Canvas_Engine_空间解析编辑与无限画布系统架构设计_V1.1.md)、[实施方案](Spatial_Canvas_Engine_前后端开发实施方案_V1.0.md)。
详细规则：[几何操作](docs/spatial/geometry-rules.md)、[接口/存储](docs/spatial/api-storage-contract.md)、[确认失效](docs/spatial/confirmation-rules.md)。完整报文采用 [schema](docs/contracts/spatial-api.schema.json) 与 [示例](docs/contracts/spatial-api.examples.json)，本页片段只说明核心字段。

## 1. 范围与稳定关系

PNG、PDF、CAD 和人工编辑通过适配层进入统一协议。首期一个设计场景对应一个选定来源页/图，跨页拼接留待后续。

```text
SourceAsset → SourcePage → RecognitionRun → SourceScene（原始、不可覆盖）
                                            ↓
                                        DesignScene（可编辑）
                                            ↓
                                     ConfirmedSpaceSnapshot
```

文件名、房间名称、数组下标不能作为关联键。人工空间使用独立稳定 ID，来源房间 ID 可为空。
`schemaVersion` 是协议版本，`revision` 是设计稿修订；识别批次、校准版本和确认版本分别记录。
EMPTY_PAGE 模式允许 sourceSceneId=null，直接引用已处理 pageId 创建空稿，识别失败 runId 可单独保留；不用伪造成功识别。

## 2. 来源资产、页与识别

| 对象 | 必需信息 |
| --- | --- |
| SourceAsset | `assetId`、文件名、文件类型、内容哈希、原文件资源引用 |
| SourcePage | `pageId`、`assetId`、PDF 页号（从 1 开始；PNG 为 1）、目标范围、标准底图资源及像素宽高 |
| RecognitionRun | `runId`、`pageId`、输入哈希/预处理版本、服务/模型版本、任务状态及错误 |
| SourceScene | `sourceSceneId`、`runId`、来源坐标定义、原始轮廓、OCR/尺寸线索、质量问题 |

原文件 → 页面 → 裁剪/旋转/缩放 → 识别输入的每一步保留变换。
识别服务返回的轮廓必须明确坐标框架；标准适配后使用 `SOURCE_PAGE` 底图像素坐标。

来源与真实性分开：

- `origin`：`VISION` / `CAD` / `MANUAL` / `AGENT`，说明产生途径。
- `evidenceKind`：`REAL_SERVICE` / `ESTIMATED` / `DEMO` / `MANUAL`，说明证据类型。
- `confidence`：可为空；若存在则范围为 0–1，不表示用户确认。
- `issueDispositions`：人工处理记录，保留处理人、说明与修订，不能抹掉演示/估计来源；操作请求结构见接口 schema。

识别任务状态：`PENDING` / `PROCESSING` / `PARTIAL` / `SUCCEEDED` / `FAILED`。
识别任务状态与设计稿、校准及确认状态独立。

## 3. 坐标和几何格式

| 坐标框架 | 单位 | 方向 | 用途 |
| --- | --- | --- | --- |
| `SOURCE_PAGE` | `px` | 左上原点，X 右、Y 下 | 标准底图、原始轮廓、未校准编辑稿 |
| `WORLD_MM` | `mm` | X 右、Y 上 | 已校准设计几何与业务计算 |
| 屏幕 | `px` | X 右、Y 下 | 相机显示与指针事件，不写入业务几何 |

设计稿未校准时 `workingCoordinateSystem=SOURCE_PAGE`；校准后为 `WORLD_MM`。
同一设计稿的全部工作几何使用同一框架，不允许部分房间按 px、部分按 mm 混算。
未校准几何不能仅通过标记 `unit=mm` 被视为真实物理空间。

多边形统一采用：

```json
{
  "type": "POLYGON",
  "coordinateSystem": "WORLD_MM",
  "outer": [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
  "outerVertexIds": ["V-0", "V-1", "V-2", "V-3"],
  "holes": [],
  "holeVertexIds": []
}
```

环不重复首点，至少 3 个不同顶点；坐标必须为有限数值。
世界坐标外环逆时针、孔洞顺时针；来源坐标由适配层按其轴向规范化。
确认要求轮廓非退化、无自交、孔洞位于外环内且不互相交叉。
首期支持单一外环；多片不连通空间保留原始数据并标待处理，不静默只取其中一片。

`bounds`、尺寸、面积均为派生值。矩形宽深沿局部轴测量；异形空间展示边长和包围盒范围，后者不称为净宽深。
校准后 boundaryAreaM2 为外环扣孔洞面积；areaM2 再扣永久实心障碍占地并集，重复占地只扣一次，均以 mm²/1000000 转 m²。普通禁放区不扣净地面面积。
房间边界为墙内侧，boundaryBasis 区分 INNER_WALL_FACE、FUNCTIONAL_DIVISION 与 UNRESOLVED；只有已知 SHARED_PARTITION 关系检查裂缝，详见几何附件。
未校准时 `areaM2`、物理宽深和物理边长为 `null`；OCR 面积文字另存为线索。

## 4. 校准与底图变换

目标校准记录：

```json
{
  "sceneId": "SCENE-001",
  "revision": 1,
  "status": "CONFIRMED",
  "method": "TWO_POINT",
  "sourcePageId": "PAGE-001",
  "referencePoints": [[100, 500], [700, 500]],
  "referenceLengthMm": 6000,
  "mmPerSourceUnit": 10,
  "sourceToWorld": [10, 0, 0, -10, -1000, 5000],
  "confirmedBy": "user-001"
}
```

仿射矩阵 `[a,b,c,d,e,f]` 定义为：

```text
worldX = a × sourceX + c × sourceY + e
worldY = b × sourceX + d × sourceY + f
```

上述示例将来源点 `[100,500]` 映射到世界 `[0,0]`，600px 对应 6000mm。
校准状态为 `UNKNOWN` / `ESTIMATED` / `CONFIRMED`；未解决比例冲突仍不能确认空间。
所有校准记录带比例依据、操作者和时间；来源可靠的 CAD 单位也通过明确单位解析记录进入可靠世界坐标。

底图对象包含 `underlayId`、`sourcePageId`、资源引用、`widthPx/heightPx`、`imagePixelToWorld` 与变换版本。
未校准时世界变换为 `null`，在来源坐标中直接显示标准底图。
底图像素原点、外边距与房间包围盒不同，禁止用房间范围猜测图像映射。

世界 → 屏幕使用统一相机变换，其 X/Y 缩放绝对值相同，包含世界 Y 向上到屏幕 Y 向下的转换。
指针通过同一相机的逆变换回到工作坐标。相机状态单独保存，不更新几何修订。

重新校准按旧变换的逆与新变换组合转换当前编辑几何，先展示变化再由用户应用；不重新拷贝原始轮廓覆盖人工修改。
若用户已输入实际尺寸，重新校准可能影响这些尺寸，须展示影响并允许取消，不能静默保持旧确认。

## 5. DesignScene 与 Space 示例

以下为校准后、尚未确认的核心字段片段，省略若干必需字段，不作为完整接口请求/响应；底图范围可大于房间范围。完整 payload 以 schema 示例为准。

```json
{
  "schemaVersion": "1.2",
  "sceneId": "SCENE-001",
  "projectId": "PROJECT-001",
  "revision": 3,
  "sourceSceneId": "SOURCE-001",
  "sourcePageId": "PAGE-001",
  "workingCoordinateSystem": "WORLD_MM",
  "calibration": {
    "revision": 1,
    "status": "CONFIRMED",
    "sourceToWorld": [10, 0, 0, -10, -1000, 5000]
  },
  "underlays": [{
    "underlayId": "UNDERLAY-001",
    "sourcePageId": "PAGE-001",
    "assetId": "PREVIEW-001",
    "widthPx": 900,
    "heightPx": 700,
    "imagePixelToWorld": [10, 0, 0, -10, -1000, 5000],
    "transformRevision": 1
  }],
  "spaces": [{
    "spaceId": "SPACE-001",
    "kind": "ROOM",
    "parentSpaceId": null,
    "name": "会议室",
    "category": "MEETING_ROOM",
    "includedInDesign": true,
    "exclusionReason": null,
    "sourceRef": {
      "assetId": "ASSET-001",
      "pageId": "PAGE-001",
      "runId": "RUN-001",
      "sourceSceneId": "SOURCE-001",
      "sourceRoomId": "ROOM-001"
    },
    "origin": "VISION",
    "evidenceKind": "REAL_SERVICE",
    "confidence": 0.92,
    "geometryRevision": 3,
    "requirementRevision": 3,
    "boundaryBasis": "INNER_WALL_FACE",
    "designGeometry": {
      "type": "POLYGON",
      "coordinateSystem": "WORLD_MM",
      "outer": [[0, 0], [6000, 0], [6000, 4000], [0, 4000]],
      "outerVertexIds": ["V-0", "V-1", "V-2", "V-3"],
      "holes": [],
      "holeVertexIds": []
    },
    "derived": {
      "boundaryAreaM2": 24,
      "areaM2": 24,
      "rectangleDimensions": {"widthMm": 6000, "depthMm": 4000},
      "boundsMm": {"minX": 0, "minY": 0, "maxX": 6000, "maxY": 4000}
    },
    "confirmation": {"status": "UNCONFIRMED", "confirmedRevision": null},
    "qualityIssues": []
  }],
  "walls": [],
  "openings": [],
  "obstacles": [],
  "annotations": [],
  "layerAvailability": {
    "walls": "UNKNOWN",
    "openings": "UNKNOWN",
    "obstacles": "UNKNOWN"
  }
}
```

`sourceRef` 定位 `SourceScene` 中不可变的原始轮廓；完整场景查询应可取得原始数据或明确的查询引用。
人工创建空间没有来源房间，但有空间 ID、操作者与创建记录。
`kind` 区分 `ROOM` 与 `FUNCTION_ZONE`；功能分区通过 `parentSpaceId` 关联建筑房间。
`includedInDesign=false` 表示显式排除，保留空间、原始来源和原因，不通过删除掩盖识别范围。

`layerAvailability` 为 `AVAILABLE` / `PARTIAL` / `UNKNOWN` / `NOT_APPLICABLE`；空数组不能独自证明“没有门窗或柱”。
未解析图层由用户复核，必要限制带入后续排版。

## 6. 墙、开口、障碍与质量问题

墙使用 `wallId`、工作坐标中心线或轮廓、厚度、关联空间和来源；实际厚度未知时为空。
门窗统一为 `Opening`：`openingId`、类型、`wallId`（可空）、端点、方向、净宽及关联空间；门扇开启范围未知时明确标注。
障碍物使用 `obstacleId`、类型、轮廓和 `spaceId`；禁放区与柱不混作房间外包围盒。
这些对象跟随工作坐标框架及校准变换。首期保留来源已提供的内容，不能伪造已识别图层。

质量问题记录 `issueId`、`code`、`severity`、受影响对象 ID、位置、说明、处理记录及 `blocksConfirmation`。
自交、退化、未校准、未处理的同级空间冲突不能仅靠确认按钮绕过。
几何有效性问题必须修正；允许人工复核的语义/来源问题须有明确处理记录。

## 7. 编辑、保存与确认契约

编辑操作包括：创建、移动、设置边/顶点、矩形宽深、选中边长度、排除/恢复空间、校准应用。
每次操作修改同一份 `designGeometry`，重新计算派生值；不得独立提交“新面积”作为几何事实。
一次拖动/数值提交对应一个撤销命令；撤销/重做形成新业务修订，不回退服务器修订计数。

保存请求带 `sceneId`、`baseRevision`、`clientChangeId` 和完整工作几何/相关变更。
全稿必需图层缺失/null 为 400；空数组仅在原本无对象或全部对象已显式删除时合法。来源空间须显式排除；人工空间通过 deletions 表达删除，保留历史并处理引用。完整定义见接口附件。
后端校验有限坐标与框架、重新计算派生值、原子提交几何和失效状态；版本冲突明确返回，不静默覆盖。
草稿可保存可修复的几何问题，但必须标记；非有限坐标或无法解析的协议数据直接拒绝。

确认以 `spaceId + geometryRevision + requirementRevision + calibrationRevision + geometryPolicyVersion` 为条件。confirmedRevision 指需求版本，另存 confirmedGeometryRevision。
逐空间确认只检查目标及相关冲突；整体确认再检查所有纳入空间与 scopeRevision，不自动替用户确认未完成空间。
确认记录包含条件版本、操作者、时间和不可变几何/约束快照；完整操作—版本—失效表见确认附件。
几何、纳入状态、父子关系、用途、障碍/门窗和质量处理变化使受影响确认失效；校准变化影响全部依赖空间。仅名称与纯展示操作不取消确认，版本职责按确认附件执行。

演示结果不能进入正式确认；真实识别或人工描绘的结果须完成校准与人工核对。
后续布局输入须带空间 ID、确认版本、完整轮廓/孔洞、障碍及可用图层状态，不能仅提供名字和面积。
推荐在布局确认后进行，效果图绑定已确认方案快照。

## 8. 现有模型迁移

现有 `FloorPlanAnalysisResponse`、`FloorPlanRoom`、`ZoneObject` 与手动矩形布局是迁移来源，不是本协议已实现的证明。

- 统一多种 polygon 输入格式；保留未命名空间，不用包围盒替换轮廓。
- 原始结果和设计稿分开保存；既有确认接口须先补齐多边形、校准与版本核验。
- `scaleRatio` 现有注释为 px/mm，目标字段为明确的 `mmPerSourceUnit` 与变换；适配必须记录取倒数条件，禁止直接复用歧义比例。
- 旧 px 草稿无可靠比例时进入待校准；旧手动 Y 向下毫米几何须转换方向并保留原信息。
- 旧宽深/面积只有在能由真实多边形验证时作为派生缓存使用。
- 空间 ID 在重识别后需明确映射；不能按房间名称或数组顺序匹配覆盖。
