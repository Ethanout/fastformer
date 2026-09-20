# 2026-09-16 client/render 审计

范围：`src/main/java/io/github/fastformer/client/render` 全目录及其测试。
本轮不修改 `client/input` 和 `client/operation`。
判定依据是当前工作树代码，不是上一轮模型结论。

## 已确认并已修复

### R-1 Gizmo 坐标系：MOVE 与 ROTATE 被错误地跟随局部坐标系

- 触发条件：工作区部件先旋转，再拖动或悬浮 MOVE 手柄。旋转角不为零时必然发生。
- 证据路径（修改前）：
  - `client/render/core/WorkspaceInteractionResolver.java:137-160`。`partGizmo` 把 `partTransformFrame` 交给 `AxisGizmo.inFrame`，MOVE、SCALE、ROTATE 共用同一个坐标系。旋转非零时该坐标系是局部坐标系。
  - `fastplace/geometry/AxisGizmo.java:83-85, 87-93, 210-287`。手柄位置、轴向量和命中都读 `frame`。因此命中与渲染同时偏移。
  - `client/render/core/OperationPreviewRenderer.java:303`（渲染）与 `WorkspaceInteractionResolver.java:95-101`（命中）都调用 `partGizmo`。两者一致，但都与需求不符。
  - 反证：同一文件的组 Gizmo 已按世界坐标系处理。`WorkspaceInteractionResolver.java:115-123` 对含棱柱的组只给 MOVE 和 ROTATE，且使用 `TransformFrame.world`。
- 需求依据：`docs/plan/current_todo.md` BUG-11“旋转之后 MOVE 必须使用世界坐标系；只有 SCALE 的操作轴和手柄渲染随局部坐标系旋转”。`docs/plan/bugs/2026-09-15-architecture-interaction.md:97-99` 已记录同一结论。
- 影响：旋转后 MOVE 箭头和 ROTATE 环指向局部轴。拖动距离按局部轴计算，视觉与位移不一致。
- 修复：`WorkspaceInteractionResolver` 新增 `worldPartGizmo`（MOVE+ROTATE，世界坐标系）与 `localScaleGizmo`（只含 SCALE，部件坐标系）。命中测试同时测试两个 Gizmo，再用公开的 `AxisGizmo.preferHit` 合并候选。命中结果携带获胜的 Gizmo。
- 渲染：`FastPlaceClientPreviewCore.renderWorkspaceGizmo` 先画世界 Gizmo 的轴、MOVE 箭头和 ROTATE 环，再只画局部 Gizmo 的 SCALE 实体手柄。未旋转时世界坐标系等于局部坐标系，画面与本轮之前一致。
- 测试：`WorkspaceInteractionResolverTest.rotatedPartKeepsWorldMoveAxesAndLocalScaleAxes`、`unrotatedPartKeepsScaleHandlesOnWorldAxes`。原测试 `rotatedPartGizmoUsesLocalAxesAndRetainsScaleHandles` 断言 MOVE 轴随局部坐标系旋转，与 BUG-11 冲突，已改写。

### R-2 异步 shell 构建失败后永久复用旧网格并且不再重试

- 触发条件：`BuildingShellCache` 带 executor 且部件数超过 `PreviewAsyncPolicy.meshSynchronously` 阈值时，后台 `ShapeShellMesh.build` 抛异常。
- 证据路径：
  - `client/render/cache/BuildingShellCache.java:135-145`。输入在构建之前写入字段，然后才调用 `buildOrSchedule`。
  - 同文件 `160-179`。后台失败只记录日志，`this.mesh` 保持上一个输入集合的网格。
  - 同文件 `72-79` 与 `86-94`。输入的同一性检查已通过，于是每帧都返回旧网格。失败永不重试。
- 影响：新输入集合持续显示上一份几何。世界坐标相同位置会画出错误形状。
- 生产可达性：当前生产路径 `FastPlaceClientPreviewCore.java:236,242` 使用不带 executor 的工厂，构建是同步的。同步路径在 `135-140` 之前抛异常，不写字段，所以不受影响。该缺陷当前只在测试可构造的异步路径上可达，属于潜在缺陷。
- 修复：`publishCompletedMesh` 在非取消的失败和 `InterruptedException` 上调用 `clear()`。丢弃已提交的输入集合和旧网格，下一帧重新构建。
- 测试：`BuildingShellCacheTest.failedAsyncBuildForgetsItsInputsInsteadOfReusingTheOldMesh`。

### R-3 棱柱面渲染对空底面除以零

- 触发条件：`OperationSelectionVolume.prism()` 的 `base()` 为空列表。
- 证据路径（修改前）：`client/render/core/OperationPreviewRenderer.java:590`。`scale(1.0 / base.size())`，空列表得到 `Infinity`，`center` 变成非有限值。紧随其后的 `base.getFirst()` 抛出 `NoSuchElementException`。
- 同一文件的 `renderOperationHighlightedFace` 侧棱分支也有同类风险：`base.get((index + 1) % base.size())` 在空列表上除以零。已一并加守卫。
- 影响：一帧渲染异常。渲染异常会中断该阶段的批次，可能留下未结束的批次。
- 修复：`base.size() < 3` 时直接返回。三角形需要三个底面点。
- 契约核对：面中心是底面顶点平均值加半个挤出。棱柱跨越 `base` 到 `base + extrusion`，中点就是该值。原算法不改，只加守卫。
- 测试：`OperationPreviewRendererTest.prismFaceCenterRejectsABaseThatCannotFormAFace`、`prismFaceCenterSitsHalfAnExtrusionAboveTheBaseCentroid`。第二个测试第一次运行时 x 期望写错为 `4/3`，实际是 `8/3`。证据 `build/test-results/test/TEST-io.github.fastformer.client.render.core.OperationPreviewRendererTest.xml`。生产算法正确，已改正测试数值。

### R-4 面顶点索引缓冲在排序失败时泄漏

- 触发条件：`MeshData.sortQuads` 抛异常。
- 证据路径：`client/render/cache/BuildingShellFaceBuffer.java:86-88`。`this.indexBytes` 先赋值，`sortForCamera` 的结果后赋值。异常时字段已非空但 `sortState` 为空，直到下一次 `clear()` 才释放。
- 影响：单个原生缓冲滞留。范围小，但与缓存生命周期要求不符。
- 修复：`sortQuads` 失败时关闭刚创建的 `indexBytes` 并向上抛出。

### R-5 双层 shell 同时存在时跳过全局预览淡出

- 触发条件：building 会话中 confirmed 层与 pending 层同时非空，且 `worldPreviewOpacity` 小于 1。后者在玩家准星接近可交互原版方块时发生。
- 证据路径：`client/render/core/FastPlaceClientPreviewCore.java:3412-3422` 与 `3426-3431`（修改前行号）。两条分支对同一个 alpha 的处理不同：
  - 双层分支把 `confirmedAlpha` 与 `pendingAlpha` 原样传给 `ShapeShellRenderer.renderFaces`。
  - 单层分支改用 `previewAlpha(alpha, worldPreviewOpacity)`。
  - 调用点 `3376-3384` 传入的是未缩放的 `0.80F` 与 `pendingFaceAlpha`。
- 需求依据：`client/input/InteractionContext.java:120-124` 的注释写明了设计意图：“Keeping this interpolation here makes every preview layer share the same transition instead of stepping independently.”。双层分支违反该契约。
- 影响：层数变化时预览不透明度跳变。准星接近原版方块时应淡出的双层预览保持全亮。这与“方块较多时预览仍闪动”的现象方向一致。
- 修复：在唯一调用点应用一次 `previewAlpha`，`renderBuildingFaces` 两条分支都原样使用已缩放的 alpha。这样两条路径按构造一致。
- 测试缺口：该缺陷无法用不依赖 GL 的单元测试覆盖。`previewAlpha` 本身已有测试（`FastPlaceClientPreviewCoreTest:13-14`）。需要实机确认：pending 层出现或消失时预览亮度不跳变。

## 已确认，未修改（需要实机决策）

### R-6 双层 shell 路径逐帧重新细分，绕过 GPU 缓存（已确认，未修改）

- 触发条件：confirmed 层与 pending 层同时非空。这是拖拽编辑期间的主要状态。
- 证据路径：
  - `client/render/core/FastPlaceClientPreviewCore.java:3412-3422`。该分支每帧清空两个 GPU 缓存，再对完整的两层全部面调用 `ShapeShellRenderer.renderFaces`。
  - `client/render/shell/ShapeShellRenderer.java:18-45`。该方法对面逐个调用 `FastPlaceClientPreview.addGhostQuad`，再经 `buffers.endBatch(GHOST_FACES)` 上传。没有任何缓存。
  - `client/render/cache/BuildingShellFaceBuffer.java:17, 111-122`。缓存路径才会复用 `VertexBuffer`，并且只重排相机相关索引。它存在的目的正是避免上面的逐帧细分。
- 影响：面数越多，逐帧开销越大。两层同时存在时正好是交互编辑期。另外两条路径的绘制顺序不同：缓存路径按相机距离排序，双层分支按网格顺序绘制。半透明面在同一帧集合上按不同顺序绘制会产生可见差异。层数变化时两种顺序切换，这是“方块较多时预览仍闪动”的第二个可疑来源。
- 未修改的理由：改为统一走缓存路径会改变两层之间的绘制顺序与混合结果。这需要实机 A/B 才能确认不会退化为另一种视觉问题。本代理无法启动客户端，按约束不盲目修改。
- 建议方案：把双层分支也改成两个缓存实例分别绘制，即 `CONFIRMED_SHELL_FACES.draw(...)` 后接 `PENDING_SHELL_FACES.draw(...)`，与单层分支完全一致。这样只保留一条绘制路径，同时去掉逐帧细分。两层相对顺序仍是 confirmed 先、pending 后，与现状一致。
- 建议方案：把双层分支也改成两个缓存实例分别绘制，即 `CONFIRMED_SHELL_FACES.draw(...)` 后接 `PENDING_SHELL_FACES.draw(...)`，与单层分支完全一致。这样只保留一条绘制路径，同时去掉逐帧细分。两层相对顺序仍是 confirmed 先、pending 后，与现状一致。

## 已检查，当前实现正确

这些项目在 `current_todo.md` 中仍标记未通过。本轮只记录代码证据，不勾选 TODO。

- 跨部件剔面（BUG-03）：每层网格由独立缓存生成，平面键包含剔除组。证据 `BuildingShellCache.java:23, 132` 与 `59`。常规方块共用一个剔除组，因此只在同一缓存内合并。独立颜色的控制点方块剔除组是自身坐标，`sharesCullGroup`（`148-150`）要求两侧都不是特殊方块。`BuildingShellCacheTest.controlPointAndRegularBlockDoNotCullEachOther` 断言 12 个面。工作区块的遮挡集合按部件传入，见 `OperationPreviewRenderer.java:254-260` 与 `blocksOwnedByPart`。当前代码不会跨部件剔除。
- 内部面剔除与内部块丢弃不误删外表面：`BuildingShellCache.java:116-131`。环境集合等于本缓存的渲染集合，六个邻居都命中才丢弃整块。特殊方块不走该路径，所以控制点不会被丢弃。
- 几何预览的异步 pending 网格在构建期间隐藏：`PendingGhostMeshCache.java:39-48` 立即发布空网格。`PendingGhostMeshCacheTest.asyncReplacementHidesThePublishedOldMesh` 显式断言该行为。这与“预览不得被旧结果覆盖”一致，是有意取舍，本轮不改。
- 渲染与命中的 Gizmo 缩放一致：`WorkspaceInteractionResolver.java:94` 与 `OperationPreviewRenderer.java:302` 都用相机到中心的距离。`InteractionContext.capture:37` 的 `camera` 就是 `gameRenderer.getMainCamera().getPosition()`，与渲染事件相同。
- 退化面外线框回落：`PreviewGeometrySupport.java:81-89` 保留已确认的首段线。`PreviewGeometrySupportTest.degenerateFaceRetainsItsConfirmedInitialLine` 断言该行为。这是有意设计，本轮不改。
- `OperationFaceHitInterpolator`、`HudFadeTimer`、`HoverDwellTracker`、`VisibilityInterpolator` 的时钟回退处理都有测试覆盖，未发现缺陷。

## 跨模块发现（本轮不改他人文件）

- X-1 旋转轴心不一致。渲染的 Gizmo 和选取外框使用选区边界中心，见 `client/render/core/WorkspaceInteractionResolver.java:352-364` 与 `client/operation/preview/WorkspaceSelectionBounds.java:92-108`。实际体素旋转使用占用方块包围盒中心。证据 `client/operation/preview/WorkspacePreviewComposer.java:53-65` 与 `client/operation/transform/VoxelRotation.java:73-75`，且每个轴之后重新计算中心。含空气的选区两者不同，这是“旋转后方块不再对齐方块网格”和“旋转首点线框错位”的主要嫌疑。`docs/plan/bugs/2026-09-15-architecture-transactions.md:128-130` 已记录同一结论。归属 `client/operation`。
- X-2 按住 Alt 后预览全部消失。渲染侧只把部件透明度乘 0.45，见 `OperationPreviewRenderer.java:252`。消失条件来自会话状态与输入门禁，归属 `client/input` 与 `client/operation`。
- X-3 确定面阶段准星靠近初始线时初始线消失。`client/render` 侧的外线框在该情形保留首段线（见上）。其余候选边界来自 `fastplace/geometry/generation/PlanarFaceGeometry` 与 `FastPlaceGeometry.guideLines`，归属 `fastplace`。
- X-4 确定体阶段外线框跟随方块吸附点。`PreviewGeometrySupport.java:92-94` 已把高度点交给 `FastPlaceGeometry.constrainedVolumeExtrusion` 约束。约束是否满足取决于 `fastplace` 实现，归属 `fastplace`。

## 输入模块线索核对（IN-5）

输入代理要求核对 `PreviewRenderOwner` 选择 `clipForPlacement` 或 `clip`，并确认空手选区保留草的轮廓命中。结论：当前实现正确，本轮不改。

- `client/render/core/FastPlaceClientPreviewCore.java:3304-3324`。只有 `BUILDING` 和 `NONE` 使用 `clipForPlacement`。
- 同文件 `3307-3312`。`select` 的第二个参数是 `PREVIEW_STATE.operation().active()`，即服务端 payload 的 active，不是客户端工作区。AABB 首点等待第二点的阶段因此属于 `OPERATION`，使用 `clip`。
- 同文件 `389-393`。payload 只有通过 `ClientOperationController.synchronize` 才写入 `PREVIEW_STATE`。已确认的 payload 同时设置 `serverPreview`。
- `client/operation/controller/ClientOperationController.java:104-114`。`active()` 是工作区非空，`selectionSessionActive()` 是服务端预览 active。把 `active()` 当第二个参数会破坏首点阶段。
- `fastplace/LongRangeBlockRaycast.java:29-34, 69-80`。`clip` 不跳过可替换方块，`clipForPlacement` 跳过。草因此只在前者命中。
- `NONE` 只会在没有 building、operation、workspace、geometry 时出现。此时没有 FastFormer 选区会话，该射线服务于近距原版让行门禁与放置预览。

## 本轮改动文件

生产代码：

- `src/main/java/io/github/fastformer/client/render/core/WorkspaceInteractionResolver.java`
- `src/main/java/io/github/fastformer/client/render/core/OperationPreviewRenderer.java`
- `src/main/java/io/github/fastformer/client/render/core/FastPlaceClientPreviewCore.java`
- `src/main/java/io/github/fastformer/client/render/cache/BuildingShellCache.java`
- `src/main/java/io/github/fastformer/client/render/cache/BuildingShellFaceBuffer.java`

测试与报告：

- `src/test/java/io/github/fastformer/client/render/core/WorkspaceInteractionResolverTest.java`
- `src/test/java/io/github/fastformer/client/render/core/OperationPreviewRendererTest.java`
- `src/test/java/io/github/fastformer/client/render/cache/BuildingShellCacheTest.java`
- `docs/plan/bugs/2026-09-16-render-audit.md`（本文件）

## 本轮测试

- `WorkspaceInteractionResolverTest`：新增 `rotatedPartKeepsWorldMoveAxesAndLocalScaleAxes`、`unrotatedPartKeepsScaleHandlesOnWorldAxes`。改写旧的 `rotatedPartGizmoUsesLocalAxesAndRetainsScaleHandles`。
- `BuildingShellCacheTest`：新增 `failedAsyncBuildForgetsItsInputsInsteadOfReusingTheOldMesh`。
- `OperationPreviewRendererTest`：新增 `prismFaceCenterRejectsABaseThatCannotFormAFace`、`prismFaceCenterSitsHalfAnExtrusionAboveTheBaseCentroid`。
- R-5 无新增测试。渲染路径需要 GL 上下文。已有 `FastPlaceClientPreviewCoreTest` 覆盖 `previewAlpha`。
- 未新增 `BuildingShellFaceBuffer` 的测试。该修复需要 GL 上下文，只做代码审查。
- 最近一次落盘结果（`build/test-results/test`，报告时间 `2026-09-16 01:56:38 +08:00`）：
  `BuildingShellCacheTest` 6 项全通过，`WorkspaceInteractionResolverTest` 14 项全通过，
  `OperationPreviewRendererTest` 6 项全通过。R-1、R-2、R-3 因此已有执行证据。
- R-5 与 R-6 没有执行证据。之后 Gradle 不可用（Wrapper 下载 Gradle 9.2.1 被网络拒绝；
  本机缓存离线解析缺 `gson-2.10.1` 元数据），无法重跑。
- 主代理统一运行测试。本轮没有本地运行 Gradle。

## 实机缺口

- 本轮没有启动客户端。R-1 的画面结论需要实机确认：旋转部件后 MOVE 箭头指向世界轴，SCALE 手柄沿部件轴，拖动距离与视觉一致。
- R-2 只在测试可构造的异步路径修复。生产同步路径不需要实机验证。
- R-3、R-4 是异常路径。建议实机记录渲染异常日志，确认没有新的 `NoSuchElementException`。
- BUG-03、BUG-13、BUG-11 的其余实机场景仍未验证，不能因为自动化通过而勾选。
