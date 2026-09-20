# 2026-09-16 几何预算架构审计（工作区展开的无界路径）

范围：`WorkspaceContentPreparer`、`ClientOperationController.updateTransformGesture`、`WorkspaceInteractionResolver`、`WorkspaceSelectionBounds`、`WorkspacePreviewComposer`、`OperationStackRegion`、`VoxelRotation`、`WorkspaceGeometryCost`、`WorkspaceGeometryBudget`、`OperationWorkspaceValidator`。

本轮只读。未改任何 Java 文件。未运行 Gradle。`client/operation/preview` 与 `client/operation/transform` 由旋转代理占用，本文件只给方案。

证据级别：静态，逐行调用链。没有测量数据。可达性判断按“生产写入方是否存在”区分，不把构造可达当成 UI 可达。

## 一、结论摘要

| 路径 | 是否受预算保护 | 展开量 | 触发条件 |
|---|---|---|---|
| `WorkspaceInteractionResolver:434` | 是，`canResolveForRendering` | ≤ 100,000 | 渲染 |
| `WorkspacePreviewComposer.resolveForRendering:416` | 是 | ≤ 100,000 | 渲染 |
| `WorkspacePreviewComposer.frameForPart:109` / `geometryFrame:47` | 是，`canResolveForFrame` / `canResolveForRendering` | ≤ 100,000 | 渲染与 Gizmo 外框 |
| `WorkspaceContentPreparer.clipboardParts:19` | 否 | 缩放体积 × 重复格数（可含桥接增格） | Ctrl+C 复制选中部件 |
| `ClientOperationController.updateTransformGesture:800` | 否 | 每个基线部件的完整展开，每个手势步一次 | 多部件common 手势（MOVE/SCALE/ROTATE） |
| `ClientOperationController.updateTransformGesture:851` | 否 | 当前部件完整展开 | common 分组 ROTATE |
| `WorkspaceSelectionBounds:95` | 否 | 变换有效但无选区时为完整展开 | 无选区部件 + 有效变换 |
| `OperationWorkspaceValidator.validate:74` | 部分 | 先完整展开，再在 `:82` 后置计数 | 服务端提交校验 |

预算模型 `WorkspaceGeometryCost` 与预算门 `WorkspaceGeometryBudget` 覆盖缩放扫描体积与重复格数，漏掉旋转桥接增格、重复列表本身的内存、以及上表四处不受保护的调用点。

## 二、现有成本模型与预算门的实际覆盖

`WorkspaceGeometryCost.of`（第 60 至 81 行）计算三项：

- `scanVolume`：仅当缩放改变某个轴的占用宽度时非零，等于三个目标轴宽之积（第 84 至 92 行）。
- `writtenUpperBound`：无扫描时为源格数，有扫描时为扫描体积（第 73 行）。该上界对缩放成立，因为 `scaleValues` 只遍历目标网格（`WorkspacePreviewComposer:375-391`）。
- `projectedUpperBound`：`writtenUpperBound × repeatCells`，饱和乘法（第 79 行）。

`WorkspaceGeometryBudget.fits`（第 29 至 31 行）只比较 `projectedUpperBound <= maxBlocks`；`assess`（第 34 至 49 行）按部件累加同一个值。两个消费者用它：渲染侧 `WorkspacePreviewComposer.canResolveForRendering:427` 传 `CLIENT_RENDER_BLOCK_LIMIT = 100_000`；服务端 `OperationWorkspaceValidator.validate:55` 传 `maxPlacement`（默认 20,972,152，`FastPlaceSettings:34`）。

已核实没有漏接的三处：`WorkspaceInteractionResolver:434-435` 先判 `canResolveForRendering`；`resolveForRendering:416-420` 有同一守卫；`WorkspaceSelectionBounds.geometryFrame:107-112` 经 `frameForPart` / `geometryFrame`，两者各有守卫，超限时返回 null 并退回保守外框。

## 三、漏接点

### 3.1 旋转桥接增格不计入成本模型

`VoxelRotation.rotate` 第 108 至 117 行对每个面相邻源体素对调用 `bridgeFaceNeighbours`（第 126 至 137 行），最多插入 2 格。`WorkspaceGeometryCost` 没有对应项，因此 `projectedUpperBound` 不是旋转输出的真实上界。

- 已证实的落点是**前置低估**：`OperationWorkspaceValidator.validate` 第 55 至 61 行的内存准入用 `budget.plannedUpperBound()`；渲染守卫同样只用投影。
- 未证实的部分是“超额实际写入”：`validate` 第 82 至 84 行在展开后另有 `writes.size() > maxBlocks` 后置拒绝，最大放置量不会被绕过。本轮无测量。
- 幅度有界：桥接格只来自面相邻对，每对每轴最多 2 格，最多 3 个轴各一次，额外格数不超过 18 倍源格数。这是静态推导。

### 3.2 重复列表本身的内存不进预算

`OperationStackRegion.repetitions(int limit)` 先按 `capacity = min(limit, cellCount())` 建立 `ArrayList`，再逐个填充。`WorkspacePreviewComposer.resolveCore:304` 传 `Integer.MAX_VALUE`，因此容量等于真实格数。129³ 区间的重复列表是 2,146,689 个 `BlockPos` 对象，在任何输出写入之前就已分配。这是“先分配、后判断”的第二个落点。

本轮已给该类加上按需遍历 API，见第九节；调用方改用新 API 后才能免掉这笔内存。

### 3.3 不受保护的四个调用点

1. `WorkspaceContentPreparer.clipboardParts:19` 调用无守卫的 `WorkspacePreviewComposer.resolve(part)`（第 411 至 413 行）。生产入口是 `ClientOperationController.copySelected:345` → `OperationClipboard.copy:27`。
2. `ClientOperationController.updateTransformGesture:799-804` 在 `common` 为真时对**每个**基线部件调用 `WorkspacePreviewComposer::resolve` 求 `groupBounds`。该方法在第 774 行，属于每个手势步的入口，因此展开随步数重复。
3. 同一方法第 850 至 856 行的 ROTATE 分支再次调用 `resolve(part)` 以求分组旋转中心。
4. `WorkspaceSelectionBounds:95` 的 `resolveValues(part.blocks(), transform)` 只在“无选区且变换有效”时才是完整展开。无选区的部件没有生产写入方（`ClientSelectionPart:36` 的空部件不带方块；带方块的剪贴板部件由文件解码得到），因此该项需要手工编辑的剪贴板或草稿文件。非立方体选区（`OperationSelectionMode` 有 CUBOID、PRISM、CONVEX_HULL）走 `rotatedCuboid` 的 8 角路径，不受影响。

### 3.4 服务端校验是“先展开后计数”

`OperationWorkspaceValidator.validate` 第 74 至 76 行对每个部件完整执行 `resolveValues`，第 82 至 84 行才比较 `writes.size()`。峰值内存发生在拒绝之前。这是设计上的顺序问题，不是上界错误。

## 四、两种政策必须分开

| 消费者 | 上限来源 | 数值 | 语义 |
|---|---|---|---|
| 渲染与 Gizmo 外框 | `WorkspacePreviewComposer.CLIENT_RENDER_BLOCK_LIMIT` | 100,000 | 超过则不渲染，保留保守外框 |
| 客户端交互（Ctrl+C、手势求中心） | 目前无 | 无 | 需要新的交互预算 |
| 服务端提交 | `FastPlaceSettings.maxPlacement` | 默认 20,972,152 | 超过则拒绝计划 |

渲染上限与服务端配额相差约 209 倍，这是有意的：渲染要保住帧率，服务端只要守住内存与历史。**不得**把 100,000 统一套到服务端，也不得用服务端配额放宽渲染。客户端交互路径应当有自己的第三个预算，取值建议与渲染上限同源但可独立配置，因为 Ctrl+C 与手势求中心都需要完整结果，不能“超限就当作空”。

## 五、统一有界组合 API 提案

### 5.1 形状

在 `WorkspacePreviewComposer` 之上引入一个组合边界，签名建议如下（本轮不实现）：

```java
public record CompositionLimit(long maxCells, long maxScratchCells) {
   public static final CompositionLimit RENDER = ...;      // 100_000
   public static final CompositionLimit INTERACTION = ...;  // 交互预算
}

public sealed interface Composition<T> {
   record Composed<T>(Map<BlockPos, T> values) implements Composition<T> { }
   record TooLarge<T>(long limit, long estimated) implements Composition<T> { }
   record Refused<T>(String reasonKey) implements Composition<T> { }
}

public static <T> Composition<T> compose(
   Map<BlockPos, T> source, WorkspaceTransform transform, CompositionLimit limit
);
```

关键要求：

1. **先估后分配。** 估值顺序：源格数 → 重复格数（`cellCount`）→ 缩放目标轴宽 → 预估输出（含桥接上界）→ 与 `maxCells` 比较。任一步超限立即返回 `TooLarge`，不进入展开。
2. **重复不建列表。** 用索引循环遍历区间（min 到 max 的三层循环）代替 `repetitions(int)`，或调用现有的有界 `repetitions(limit)`，`limit` 由预算反推。禁止传 `Integer.MAX_VALUE`。
3. **单次遍历中做运行计数。** 桥接格在插入时计数；达到上限立即中止并返回 `TooLarge`。这样上界只是快速路径，运行计数才是保证。
4. **失败不伪装成成功。** 超限不得返回空 map，因为空 map 与“缩放后确实为空”不可区分。`TooLarge` 与 `Composed(empty)` 必须是两个结果。
5. **保留现有语义。** 下采样合法为空仍返回 `Composed(empty)`（`resolveCore:289-293` 的注释要求保留该语义）。
6. **两个消费者各自传预算。** 渲染传 `RENDER`，交互传 `INTERACTION`，服务端校验在 `OperationWorkspaceValidator` 内传 `maxPlacement` 并把 `TooLarge` 映射为现有的 `Result.failed(...)`。

### 5.2 失败结果与提示责任

| 调用点 | 收到 `TooLarge` 后 | 提示责任 |
|---|---|---|
| `resolveForRendering` / `WorkspaceInteractionResolver` | 返回空 map，渲染层保留保守外框 | 无提示，静默降级（现状） |
| `frameForPart` / `geometryFrame` | 返回 null | 无提示（现状） |
| `WorkspaceContentPreparer.clipboardParts` | 整次复制失败，保留旧剪贴板 | 需要一条提示键，由 `copySelected` 显示。**不得**跳过超限部件后报告复制成功：那会让玩家以为复制完成而实际少了内容 |
| `ClientOperationController.updateTransformGesture:800` | 不用展开结果，跳过分组中心修正 | 需要一条提示键，或记录一次（避免每步重复提示） |
| 同一方法 ROTATE 分支 | 不做分组旋转中心修正，仍应用旋转 | 同上 |
| `WorkspaceSelectionBounds:95` | 返回保守外框 | 无提示 |
| `OperationWorkspaceValidator.validate` | `Result.failed(List.of())` | 现有 `operation_submit_rejected` 路径 |

提示键需要新增，建议命名 `fastformer.message.workspace_too_large_for_action`，同时加入两个语言文件。归属由渲染/输入侧决定，本轮只登记。

### 5.3 消费者覆盖

改完后 `WorkspacePreviewComposer.resolve` 与 `resolveValues` 应成为包内可见，或改为要求显式 `CompositionLimit`。当前这两个方法是 `public`，任何新调用点都会再次绕过预算；收紧可见性是防止回归的一半。

## 六、分阶段测试

阶段 1（纯单元，无游戏）：

1. `WorkspaceGeometryCost` 增加旋转项后，断言桥接案例的投影不小于实际输出：2 格源、Y 轴 45 度、输出 3 格（现成断言见 `VoxelRotationTest.nonOrthogonalRotationBridgesFaceAdjacentSourceVoxels`）。
2. `compose` 在 `maxCells` 之上返回 `TooLarge` 且 `Composed` 从未被构造（可用计数包装的 map 证明没有分配）。
3. 重复区间不建列表：对 129³ 区间用 `maxCells = 1000` 调用，断言返回 `TooLarge` 且没有 `ArrayList` 增长（通过分配上限断言或注入计数实现）。
4. 下采样为空仍返回 `Composed(empty)`，与 `TooLarge` 区分。
5. 渲染消费者：`canResolveForRendering` 与实际输出上限一致（桥接案例）。

阶段 2（消费者行为）：

6. `WorkspaceContentPreparer.clipboardParts` 在任一部件超限时整次失败：`OperationClipboard.copy` 不写入，旧剪贴板内容保持不变，并产生一条可提示的失败原因。部分复制需要产品明确允许后才能实现，本轮方案不含该选项。
7. `ClientOperationController.updateTransformGesture` 在 `common` 且超限时不抛异常，变换仍然应用。
8. `OperationWorkspaceValidator.validate` 保持现有 `writes.size() > maxBlocks` 语义，同时新增前置快速拒绝。

阶段 3（真实入口，GameTest）：

9. Ctrl+C 复制一个 129³ 重复部件：客户端不卡顿、显示提示、剪贴板不含该部件。
10. 多部件 common MOVE 手势每步不展开超出预算的部件。

阶段 1 与 2 可在无头测试完成；阶段 3 需要 GameTest 环境。本轮不运行任何一项。

## 七、129³ 的触发路径（已核对，避免混淆）

已逐行核对 `ClientOperationController.updateTransformGesture` 第 807 至 843 行。该方法只有一个 `switch`，分支是 `MOVE`、`SCALE`、`ROTATE`，**没有独立的 STACK 分支**。

- 重复区间由 **SCALE** 分支的非棱柱路径扩展：第 822 至 842 行计算 `cellStride` 与 `groupDelta`，然后 `transform.repeats().withAxisEndpoint(axis, direction, groupDelta)`（第 840 行）。`groupDelta` 被 `Math.clamp(..., -128, 128)` 限制（第 833 至 837 行），与 `OperationStackRegion.ENDPOINT_LIMIT` 一致。
- 棱柱部件走另一分支：第 810 至 821 行只改 `scale` 与补偿平移，**不**扩展重复区间。因此棱柱部件无法产生 129³ 区域。
- 所以 129³ 区域的产生方式是：对一个立方体部件，在三个轴上各做一次拖到端点极限的 SCALE 手势。数据结构层面的等价构造是 `OperationStackRegion.repeat(...)` / `withAxisEndpoint(...)`，现有测试 `OperationStackRegionTest.endpointsAndEnumerationAreBoundedByOperationLimit` 就是这样构造 129³ 的。“STACK”在这个方法里不是手势名，而是 `repeats` 这个词表达的数据概念。

其余可达性说明（不夸大）：

- 需要连续三次把三个轴的 SCALE 拖到端点上限 128。这是刻意操作，不是普通使用会出现的状态。
- `updateTransformGesture:800` 的展开只在 `common` 为真（多部件共同轴手势）时发生，单部件拖动不触发。
- `WorkspaceSelectionBounds:95` 的完整展开需要“无选区 + 有效变换”的部件，生产侧没有写入方，需要手工编辑文件。
- 因此当前没有“打开界面就卡死”的证据。风险集中在刻意构造的部件或手工编辑的文件上，加上每个手势步重复展开造成的累计开销。

## 八、未完成与开放问题

1. 桥接增格的精确上界已按面相邻对推导（不超过 18 倍源格数），但未测量真实分布。
2. `CompositionLimit.INTERACTION` 的取值需要产品决定：太小会让正常的大部件复制失败，太大则失去保护。
3. 收紧 `resolve`/`resolveValues` 可见性会影响 `OperationWorkspaceValidator`（跨包），需要选择“公开但要求传预算”或“把组合边界放到共享包”。
4. 服务端内存准入是否应从 `plannedUpperBound` 改为“估算 + 运行计数”需要与内存代理确认，本轮不结论。

## 九、本轮独立实施：`OperationStackRegion` 按需遍历

范围只有 `OperationStackRegion.java` 与其测试，未动 composer 与 validator。未运行 Gradle。

新增 API：

- `RepetitionVisitor`：函数式接口 `boolean visit(BlockPos)`，返回 false 停止遍历。
- `visitRepetitions(long limit, RepetitionVisitor)`：按需遍历，顺序与旧 `repetitions(int)` 完全一致（x、y、z 各自从最小端点到最大端点）。`limit <= 0` 或 visitor 为 null 时访问 0 格。循环计数用 `long`，端点取到 `Integer.MIN_VALUE` / `Integer.MAX_VALUE` 时不溢出也不死循环。
- `visitRepetitions(RepetitionVisitor)`：不限量版本，内部传 `Long.MAX_VALUE`。
- `repetitions(int limit)` 保留原签名与结果，改为委托新遍历，并按需扩容（初始容量 `REPETITION_INITIAL_CAPACITY = 256`），不再按 `min(limit, cellCount())` 一次性预留。旧调用点的返回值逐项不变。

新增测试 5 项（`OperationStackRegionTest`）：

1. `aLargeRegionDeliversItsFirstCellWithoutBuildingTheWholeList`：129³ 区域用 `limit = 1` 立即取到首格 `(0, -128, 0)`，返回计数 1，并断言 `cellCount()` 仍是 129³。
2. `aVisitorThatStopsEndsTheWalkImmediately`：visitor 返回 false 后总回调次数为 1。
3. `theVisitorOrderMatchesTheLegacyList`：新遍历结果与 `repetitions(100)` 全等，前缀与 `repetitions(5)` 全等。
4. `aBoundAtOrBelowZeroDeliversNothing`：`limit` 为 0 与 -1 时都不回调，`repetitions(0)` 仍为空列表。
5. `anExtremeEndpointRegionWalksWithoutOverflow`：`Integer.MIN_VALUE` 到 `Integer.MAX_VALUE` 的区域 `cellCount()` 为 4294967296，取下前 3 格且不溢出。

未做：`WorkspacePreviewComposer.resolveCore:304` 仍调用 `repetitions(Integer.MAX_VALUE)`。切换它会改变内存行为，属于旋转代理范围，等释放后再接。

状态：几何预算方案与 `OperationStackRegion` 按需遍历已交付（改了该类与其测试，其余只读）。未跑 Gradle。等旋转代理释放 `composer` / `validator` 后再进入统一 `compose` 接线。
