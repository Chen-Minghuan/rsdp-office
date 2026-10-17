# UPLOAD-001 单张图纸上传

日期：2026-10-17。状态：已实现并通过专项验证；基于 6579995 后工作区，保留 WORKSPACE-001 既有改动。

用户要求：一次只上传一张图纸。文件选择禁用多选；多文件拖放整体拒绝并提示；已有图纸时再次上传替换文件，不追加。取消选择/格式不支持不影响原文件。上传入口和按钮明确标注单图、更换。

更换时更新文件元信息和本机二进制缓存，清除旧识别预览/页码/分析引用，不静默删除现有画布或历史识别记录。识别/上传/导入期间禁止换图、移除和恢复示例，防止异步结果混入。旧多文件草稿优先保留当前底图同名文件，否则保留首个；新示例仅一张图纸。后端保存画布拒绝多个 files，识别接口本身已按单文件处理，不改数据库结构。

验证范围：前端构建、扩展已有 vision mock 回归覆盖单图选择/批量拖放/非法格式/替换/刷新/删除/缓存、空间回归与冒烟；Java 测试验证绕过前端也不能保存多文件。真实服务与百炼不在本次 mock 验收范围，不重启用户 8082 服务。

## 验证结果

- `cd frontend && pnpm build` 通过，日志 `/tmp/rsdp-single-drawing-build.log`。
- `RSDP_TEST_DATABASE_URL=jdbc:postgresql://localhost:5434/office mvn -f backend/pom.xml test`：115 项，0 失败/错误/跳过，日志 `/tmp/rsdp-single-drawing-java.log`。数据库测试在唯一隔离 schema 执行，没有迁移当前业务数据。
- `OUT_DIR=/tmp/rsdp-single-drawing-space-review pnpm e2e:space-review` 通过，空间编辑/复核提示/排版/刷新等 mock 回归保留。
- `BASE_URL=http://localhost:3000 MOCK_API=1 OUT_DIR=/tmp/rsdp-single-drawing-smoke pnpm e2e:smoke` 通过；mock 无商品候选，不证明真实选品集成。
- `pnpm e2e:vision` 通过，覆盖单图选择、批量拖放整体拒绝、非法格式不污染原文件、换图清空旧预览和二进制缓存、刷新后继续识别 PDF、移除后单张拖入及旧多文件草稿恢复；日志 `/tmp/rsdp-single-drawing-vision.log`。前两次测试因未等待上传/删除缓存完成就模拟拖放而超时，补齐入口可用等待后通过。全部 API mock，未调用百炼。
- `e2e:workflows` 本轮未重复运行；WORKSPACE-001 已记录同一工作区既有商品详情选择器超时，不能声称全工作流通过。

文档相对链接及 `git diff --check` 通过。本轮不重启后端；前端单图规则可直接生效，服务端保存校验随用户下次启动新版 Java 生效。未改变数据库结构；真实文件上传/识别服务的完整集成验收仍待用户验证。

## 2026-10-17 旧开发页上传方法缺失修复

用户报告 `store.replaceDrawingFile is not a function`。此前将上传改为新的 store action；页面热更新先于 store 实例时，新页面会调用旧实例没有的方法。前次构建和新浏览器 mock 测试未覆盖这个更新窗口。隔离 Nuxt dev 实验已复现同样报错；标准 store 热更新完整到达后可以恢复，不能简单归因于缺少 Pinia HMR 注册（当前 Nuxt 模块已有自动注册）。

修复：文件格式校验与元信息生成移入 `drawingUpload.ts`；页面通过稳定的 `$patch({ files: [drawing] })` 更新唯一文件并调用已有持久化动作，不再新增或调用 replaceDrawingFile。保留单文件限制、二进制替换、预览清除和画布编辑。

新增 `cd frontend && pnpm e2e:upload-hmr`：复制应用到独立临时目录并启动真实 Nuxt dev；保持浏览器和旧 store 实例不变，只热更新上传页，验证缺少 replaceDrawingFile 时仍能单图替换、保持同一 store 和已有编辑，无页面错误。全部 API mock，测试后关闭自己的服务并删除副本，不修改用户源码或重启用户服务。

- 前端构建通过：`/tmp/rsdp-upload-store-fix-build.log`。
- 真实开发热更新专项通过：`/tmp/rsdp-upload-hmr-fix.log`。
- 图片/PDF 单图上传 mock 专项重跑通过：`/tmp/rsdp-upload-store-fix-vision.log`。
- 当前 localhost:3000 开发服务在独立浏览器中单图上传/替换通过，全部 API mock，无用户数据写入；截图 `/tmp/rsdp-upload-store-fix.png`。
- 本次仅前端修复，未重复运行 Java/CAD 测试；先前 Java 115 项属于单图保存校验基线。
