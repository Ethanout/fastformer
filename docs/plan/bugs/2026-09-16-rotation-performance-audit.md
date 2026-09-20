# 2026-09-16 旋转几何帧性能与坐标消费者审计

范围：只读审计 `WorkspacePreviewComposer.geometryFrame` 与 `WorkspaceSelectionBounds` 的调用路径。
目标：确认是否引入每帧完整体素展开、缓存失效问题、坐标消费者遗漏。
本文只记录审计与架构建议。实际修复见本文末节。

## 结论

`geometryFrame` 每次调用都完整跑一遍体素管线（缩放 + 重复 + 逐轴旋转 + 平移），且**没有任何缓存**。
渲染路径每帧对每个部件会调用 `WorkspaceSelectionBounds.resolve(part)` 多次，因此每帧重复展开多次。
这是本轮旋转帧改动引入的真实性能回退，需要共用一份有界生命周期的已解析结果与几何帧。

## E-1 geometryFrame 的代价等于一次完整体素展开

- `WorkspacePreviewComposer.java:44-52`。`geometryFrame` 先做预算判断，随后调用 `resolveCore(source, transform).frame()`。
- `WorkspacePreviewComposer.java:64-112`。`resolveCore` 就是完整管线：`scaleValues`（逐目标格采样）、重复循环、
  `VoxelRotation.rotateStage`（逐轴重算中心并重建键）、平移重建键。
- 因此每次调用最多物化约十万个 `BlockPos` 条目。
- 预算判断不是缓存：`WorkspacePreviewComposer.java:205-211` 只做 `WorkspaceGeometryBudget.fits(100_000, cost)`。
- 代价模型：`fastplace/geometry/WorkspaceGeometryCost.java:60-81`。`projectedUpperBound = writtenUpperBound * repeatCells`，
  上界包含重复次数。因此展开规模有上界，但**每次调用都要重付**。

## E-2 每帧的调用扇出

`WorkspaceSelectionBounds.resolve(part)` 的两条分支现在都会请求几何帧：

- `WorkspaceSelectionBounds.java:87`（CUBOID 分支）
- `WorkspaceSelectionBounds.java:93`（非长方体且有变换的分支）

调用者分为四组：

1. 渲染部件循环，每帧每部件一次以上：
   - `OperationPreviewRenderer.java:208` → `interactionBounds` → `selectionBounds` → `resolve`
   - `OperationPreviewRenderer.java:337` → 多选组包围盒 → `selectionBounds` → `resolve`
2. 指针意图解析，每帧每个 provider 都遍历全部部件：
   - `WorkspaceInteractionResolver.java:266`（`resolveDraggedFace`）→ `selectionBounds`
   - `WorkspaceInteractionResolver.java:89`（`resolveGizmo`，每部件）→ `interactionBounds` → `selectionBounds`
   - `WorkspaceInteractionResolver.java:125`（多选组）→ `interactionBounds` → `selectionBounds`
   - `WorkspaceInteractionResolver.java:286`（`resolveFace`，每部件）→ `selectionBounds`
   - `WorkspaceInteractionResolver.java:234`（`resolvePartTarget`，每部件）→ `selectableBounds`
   - 入口：`WorkspaceInteractionResolver.java:46-66` 的 `providers()` 与 `currentGizmo()`，
     `FastPlaceClientPreviewCore.java:706-710` 每帧调用。
3. 输入路径：`client/input/RepeatStrideSemantics.java:55,66`（每次手势，但 `wholeSelectionBox` 对全部部件各展开一次）。
4. 控制路径：`client/operation/controller/ClientOperationController.java:694`。

结论：P 个已变换部件时，每帧约 3P 到 5P 次完整展开。原实现里这些分支都是 O(1) 的包围盒算术。

## E-3 缓存失效现状

- 渲染侧已有正确的有界模式：`WorkspaceInteractionResolver.java:39-41,368-375` 用
  `IdentityHashMap` + 工作区 `revision` 失效，并由 `OperationPreviewRenderer.java:185` 每帧 `pruneCache(parts)` 收敛规模。
  该缓存服务的是**已解析方块**，不服务几何帧。
- `geometryFrame` 不在任何缓存内，也不随 revision 失效。`WorkspacePreviewComposer` 没有缓存字段。
- 结论：几何帧绕开了既有的有界生命周期，属于“同一份纯函数被重复求值”。

## E-4 坐标消费者遗漏（正确性，非性能）

`WorkspaceSelectionBounds.java:85-88` 的第一分支在旋转恰好为零时传入 `frame = null`：

```java
if (part.selection() != null && part.axisAlignedCuboid() && transform.rotation().equals(Vec3.ZERO)) {
   return axisAligned(part.selection().bounds(), transform, null);
}
```

- 该分支只覆盖旋转为零，但缩放与重复仍然生效。
- 传 null 后 `axisAligned` 用**选区**中心与**选区宽度**做锚点与步距（`WorkspaceSelectionBounds.java:118-143`），
  体素管线用的是**占用**中心与**实际**步距。
- 因此“不含旋转的缩放或重复”仍会外框与方块分离。这与 E-1/E-2 的旋转分歧同源，只是没有旋转掩盖。
- 这条分支本身是 O(1)，修复它必须引入帧；因此正确性修复与性能修复必须同时做，否则修好 E-4 会放大 E-2。

## E-5 预先存在的热点（非本轮引入）

- `WorkspaceSelectionBounds.java:97` 的非长方体回退分支调用 `WorkspacePreviewComposer.resolve(part)`
  （`WorkspacePreviewComposer.java:194-196`），它**不做预算判断**且无缓存。
- 该分支在本轮之前就存在。本轮修复应顺带共用同一份结果，而不是再算一次。

## 架构建议（本次实施）

一条规则：**纯函数只求值一次，结果按部件实例共享，生命周期有界。**

1. 在 `WorkspacePreviewComposer` 内建两个按部件实例校验的缓存：一个只存小型
   `GeometryFrame`，另一个存 `resolved`（等价 `resolve(part)`）。任一查询首次展开后会填入另一项，
   所以帧和方块消费者可复用同一份解析结果。
2. 两个缓存都以 `part.id()` 索引，命中要求**实例同一**。条目只用 `WeakReference` 保存部件，
   所以帧缓存不会经由部件强持有巨大的方块表。`ClientSelectionPart` 是记录且方块表在构造时复制为不可变
   （`ClientSelectionPart.java:26-33,77`），因此新变换或方块表会成为新实例且不会命中旧数据。
3. 帧缓存按最近使用项逐条淘汰，上限为 256 项。已解析方块另有 64 项和 100,000 方块的预算，
   逐条淘汰且不清空帧缓存。一个超出方块预算的解析结果会返回给当前调用者，但不会缓存。
4. 帧只在它能改变答案时才求值：旋转非零、缩放非 1、或存在重复。
   纯平移保持 O(1) 分支，不触发展开。
5. 提交权威路径不动：`resolveValues` 与 `OperationWorkspaceValidator` 的调用照旧，缓存只服务几何消费者。
6. 既有的渲染 `RESOLVED_CACHE` 不动；它继续按 revision 失效并每帧裁剪。

## 本次实施

- `src/main/java/io/github/fastformer/client/operation/preview/WorkspacePreviewComposer.java`
  - 新增有界部件几何缓存与 `frameForPart`、`resolvedForPart`、`invalidatePartGeometry`。
- `src/main/java/io/github/fastformer/client/operation/preview/WorkspaceSelectionBounds.java`
  - 三个分支统一走缓存帧；第一分支（旋转为零）在缩放或重复生效时也传帧，修 E-4。
  - 完整变换的外框分支使用 `frameForPart`。去除重复的基础外框保留独立解析，避免误用完整重复后的旋转中心。
  - 回退分支解析传入 transform，修复 `resolveBase` 错误包含重复副本的问题。
- `src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java`
  - 清空工作区和断线时调用 `invalidatePartGeometry`，及时释放当前会话的已解析方块。
- 测试：`RotationFrameContractTest` 新增真实 `resolve(part)` 用例与帧复用用例；
  `WorkspacePreviewComposerCacheTest` 覆盖同 id 新实例失效、帧缓存上限、解析方块预算和两者独立淘汰。

## 未验证项

- 主代理定向运行 `WorkspacePreviewComposerCacheTest`、`WorkspaceSelectionBoundsTest` 和
  `ClientOperationControllerTest` 成功。纯平移测试先发现空 frame 仍创建缓存条目，已改为提前返回。
- 未启动客户端。每帧展开次数的收益需要实机帧时间对比。
- 缓存命中依赖“部件实例同一”。弱身份引用和两套预算防止长期强持有旧部件，
  但真实工作区会话下的内存和帧时间仍需实机观察。
