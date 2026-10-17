# UPLOAD-002 当前图纸与空间入口一致

日期：2026-10-17。状态：已实现，专项自动验证通过；真实图纸业务复核待验证。基线：5976ebc 后工作区，保留 RECOG-007 既有未提交改动。

用户报告：上传 7.png 后进入空间，左侧标题为 7.png，实际底图、空间和模板仍为 5.png。

原因：更换上传只替换 files，保留旧设计以防丢失；底部“进入空间总览”和顶部流程入口直接打开 `/canvas`，没有关联新图纸识别批次，空间页继续读取旧 floorPlan/objects/spaceTemplate。

实现范围：

- 每次上传生成稳定 drawingId，同名同大小重传也不复用旧绑定。files 持久保存对应 analysisId/analysisPageNumber/appliedAnalysisId；来源响应保留本地 sourceDrawingId。旧无 ID 草稿保留有限的名称兼容；真实旧图响应名称与当前上传不匹配时拒绝应用。旧 sessionStorage 任务续查时补齐文件分析绑定。
- 上传页两个空间入口共用当前识别结果；未识别的新图不开放底部入口，直接访问空间也显示当前图纸待识别提示，不展示旧底图/空间。
- 空间入口按当前上传绑定查询。识别中显示新图预览和真实进度，完成后导入新空间；失败/无空间/查询错误显示当前图纸信息，保留旧设计，不回退展示旧图。离开页面或切换绑定后忽略过期查询。
- 新图导入前保存旧稿撤销快照；新图替换清除旧空间坐标中的家具/标注和旧模板，避免混用；上传阶段保留旧设计。同批次刷新保留编辑，不重复调用模型。
- 使用模板时显式绑定当前上传上下文。排版入口的 store 校验也阻止当前上传与编辑来源不匹配；不改数据库表或真实用户稿。

恢复细节：appliedAnalysisId 区分尚未导入的新任务与已应用后被用户删空的图纸。空稿恢复只补来源，不重新填入原始房间；同分析刷新保留轮廓/名称编辑。来源为空的旧稿无法仅凭文件名证明文件内容相同，不能把兼容分支称为全历史数据来源验收。

验证记录（2026-10-17）：

| 命令/入口 | 实际结果 |
| --- | --- |
| `cd frontend && pnpm build` | 通过；日志 `/tmp/rsdp-drawing-entry-build.log`，既有重复导入提示保留 |
| `pnpm e2e:drawing-entry` | 通过；5→7、未识别、旧地址/设计直达、后台预览/完成、旧家具和模板移除、旧稿撤销快照、刷新保留编辑、已清空空间不重填、同名同大小重传、失败/迟到查询保护；日志 `/tmp/rsdp-drawing-entry-ui.log` |
| `pnpm e2e:recognition-progress` | 通过；额外覆盖旧 sessionStorage 缺少文件分析关联时的续查迁移；日志 `/tmp/rsdp-drawing-entry-progress.log` |
| `pnpm e2e:vision` | 单图上传/选页/更换/持久化与错误模拟回归通过 |
| `OUT_DIR=/tmp/rsdp-drawing-entry-space-review pnpm e2e:space-review` | 空间编辑、比例、轮廓、复核提示及新来源恢复模拟回归通过 |
| `OUT_DIR=/tmp/rsdp-drawing-entry-dimensions pnpm e2e:dimensions` | 自动尺寸、比例确认/撤销/编辑保留与过期保护模拟回归通过 |
| `OUT_DIR=/tmp/rsdp-drawing-entry-templates pnpm e2e:templates` | 模板保存/历史/独立布局/撤销/刷新模拟回归通过 |
| `pnpm e2e:upload-hmr` | 独立 Nuxt dev 副本保留旧 store 实例，仅热更新页面的上传回归通过；没有新增上传 action 依赖 |
| 文档/差异 | 本轮相对链接及 `git diff --check` 通过 |

已查看模拟图的空间截图，图纸卡片、底图、房间均属于 7.png，不含旧模板与家具。截图在 `/tmp/rsdp-drawing-7-processing.png`、`/tmp/rsdp-drawing-7-current.png`。全部 UI 使用独立端口和隔离浏览器，API 拦截；未更改用户真实图纸/数据库、未重启 3000/8082、未额外调用付费模型。本次仅修改前端交接与本地来源字段，不重复运行 Java/CAD 测试；前次 RECOG-007 的 Java 结果单独保留。完整工作流、真实 7.png 识别质量与业务复核未执行。
