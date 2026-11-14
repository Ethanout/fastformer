# 架构审查与重构手册（2026-11-13，激进版）

> 范围：`src/main` 约 1148 个 Java 文件、14.6 万行（含测试）。本次审查只读，结论均附 `文件:行号`，路径默认省略 `src/main/java/io/github/fastformer/` 前缀。
> 行号对应审查时的工作区（含未提交改动），执行前请重新定位。
>
> 风格基准（来自作者偏好）：
> 1. **文件夹拆细**：单个文件夹不堆放大量文件。
> 2. **轮子化**：内部接口要像在调用自己写的库。
> 3. **命令式调度**：难以拆散的职责，做成独立系统，像 mcfunction 命令那样调用。
> 4. **元系统**：用少量“咒语”或抽象基类组合出所有情况。
> 5. **预留扩展点**：未来要扩展的地方先留好接口。
>
> 本手册据此给出目标形态，不是简单的“拆大文件”。
>
> Alpha 数据策略（2026-11-13 用户更新）：不保留旧格式兼容读取、升级和迁移分支，只支持当前格式与当前 Minecraft DataVersion。试玩服务端旧数据可直接清除；无需为其编写迁移器。清除仅限确认的试玩数据，不在生产读取器内自动删除未知文件。

---

## 0. 一页结论

项目真正的问题不在“文件大”，而在**没有元系统**。同一类事情被手写了 N 遍：

| 同一类事情 | 被手写的次数 / 位置 |
|---|---|
| 交互模式（空闲/建筑/几何/选区/替换/填充） | 服务端 Manager、客户端输入、客户端预览三处各自 if 链分派 |
| 玩家状态的生命周期 | 服务端 ≥8 个清理入口，客户端清理入口在渲染层；各入口清的字段集合互不一致 |
| 网络包 | 61 次注册样板；客户端 20 个 `apply` 重载；68 处 `hasChannel` 检查 |
| “玩家正忙”判定 | 4 种定义，彼此不等价 |
| 异步“只要最新结果”缓存 | 3 套实现，失败语义各不相同 |
| 原子写文件 | 3 份逐字复制，另有 1 份策略相反 |
| 向量数学 | `normalize` 7 份（阈值两种）、`isParallelogram` 5 份、`dominantAxis` 4 份、`EPSILON` 26 次 |

所有核心类都是**全静态门面**。下表中“静态方法”指 public static 方法数：

| 类 | 静态方法 | 行数 |
|---|---|---|
| `FastPlaceManager` | 51 | 1320 |
| `OperationManager` | 44 | 984 |
| `ServerInputDispatcher` | 53 | 1177 |
| `WorldHistoryManager` | 45 | 1818 |
| `ClientOperationController` | 81 | 1731 |
| `FastPlaceClientPreviewCore` | — | 2847 |
| `FastPlaceClientInput` | — | 1938 |

全静态造成三个后果：
- 状态无法按会话或服务器实例隔离。
- 测试只能靠反射，甚至用到 `Unsafe`。
- 依赖只能通过 import 静态类连接，于是包之间形成大环：`fastplace ↔ server ↔ network`、`client.input ↔ client.operation ↔ client.render`。

**重构主线**：先造 7 个“轮子”（第 4 节），再把各子系统改造成“注册到轮子上的独立系统”，最后搬家（第 1 节）。死代码可以立即删除（第 6 节）。

---

## 1. 文件组织层面

### 1.1 顶层分层是错的：`fastplace` 名不副实

`fastplace` 是旧模组名，现在它同时装着三类东西：
- 纯领域：geometry、workspace 的一部分；
- 服务端逻辑：command、events、task、history；
- 视觉：`GeometryPalette`、`VisualTheme`。

实测的跨包依赖：

```
fastplace -> server   11 个文件（command/events/history/interaction/placement 反向调用 Manager）
network   -> server    2 个文件（FastPlaceNetwork 直接调用 Manager）
network   -> client    1 个文件（network/client/ClientPayloadDispatcher 实为纯客户端类）
server    -> network / fastplace，fastplace -> network
```

`architecture.gradle` 只用正则检查了 3 条规则，挡不住这些环。

**目标顶层（四层，单向依赖）**：

```
io.github.fastformer
├─ FastFormer            装配入口：只调用各系统的 bootstrap()
├─ core/                 轮子库：与 Minecraft 业务无关的通用件（registry、lifecycle、async、persist、math、net）
├─ common/               纯领域模型与算法：不依赖 ServerPlayer、网络、客户端
│   ├─ geometry/  workspace/  selection/  quickshape/  tinker/  movement/
├─ server/               服务端系统：玩家状态、工作流、任务、历史、恢复、命令、事件、网络处理器
├─ client/               客户端系统：输入、模式、预览、HUD、存储、网络处理器
└─ protocol/             只放 payload 定义与 codec（原 network/payload），不依赖 server 和 client
```

依赖方向：`client → protocol → common → core`，`server → protocol → common → core`。
`client` 与 `server` 互不依赖。这条规则写进 ArchUnit 测试（见 7.3）。

### 1.2 “文件夹拆细”的硬规则

需要拆分的现状热点：

| 文件夹 | 文件数 | 问题 |
|---|---|---|
| `fastplace/geometry/generation` | 51 | 平铺 |
| `client/input` | 41 | 平铺 |
| `fastplace/history` | 31 | 平铺 |
| `fastplace/geometry` | 30 | 平铺 |
| `fastplace/interaction` | 25 | 实为扳手（Tinker），与另外两个 `interaction` 包同名 |
| `client/render` 顶层 | 24 | 其中至少 7 个与渲染无关 |
| `client/render/model` | 24 | 帧数据、缓存键、网格、HUD 数据混放 |

**规则**：
- 每个包 ≤ 12 个类型（`package-info` 不计）。
- 每个包必须能用一句话说清职责，写在 `package-info.java` 里。
- 禁止单文件包，除非它是一个系统的公开 API 入口。

以上三条都加入布局检查。

当前的单文件或空包需要处理：

| 处理方式 | 包 |
|---|---|
| 单文件，合并或迁移 | `client/input/math`、`client/render/{guide,mesh,shell,state,theme}`、`client/operation/render`、`fastplace/fill`、`geometry/polyhedron` |
| 空目录，直接删除 | `client/session/{empty,quickshape,specialitem,specialshape,tree}`、`client/operation/{model,preview,transform}`、`client/placement/plan`、`gametest`、`src/main/java/com/example/examplemod`、`src/main/java/net/minecraft/client/renderer`、`resources/assets/examplemod`、`temp/axiom` |

其中 `client/session/` 下的 5 个空目录对应 `docs/principles/pseudocode/sessions/` 的会话设计，说明“按状态拆会话”开了头但没落地。第 3 节的 `Mode` 系统会把这个设计真正实现出来。

### 1.3 命名即职责：统一后缀词表

现状：
- `*Controller` 10 个、`*Dispatcher` 7 个、`*Manager`、`*Session`、`*Owner`、`*Semantics` 混用，后缀与行为没有对应关系。
- `IdleSelectionEntryController`、`IdleSessionEntryController`、`IdleEntryGesture` 三个名字几乎一样，职责完全不同。

强制词表如下：

| 后缀 | 含义 |
|---|---|
| `*System` | 一个可独立调度的子系统入口（见第 3 节），每个系统一个 |
| `*Mode` | 交互模式的实现（注册到 ModeRegistry） |
| `*Rules` | 纯函数规则，无状态 |
| `*State` | 可变状态容器，无行为或只有自身不变量 |
| `*Gesture` | 有状态的输入手势 |
| `*Codec` | 序列化 |
| `*Store` / `*Repository` | 持久化存取 |
| `*Renderer` | 只画，不查询、不改状态 |
| `*Payload` | 网络包定义 |

禁止使用：`*Manager`、`*Dispatcher`、`*Core`、`*Owner`、`*Semantics`。现有类按此重命名。

### 1.4 名不副实的类，需要改名或迁移

| 现状 | 问题 | 改为 |
|---|---|---|
| `fastplace/interaction/*` | 实为方块扳手；与 `client/interaction`、`geometry/interaction` 同名 | `common/tinker` + `server/tinker` |
| `fastplace/geometry/movement` | 冲刺和飞行，与几何无关 | `common/movement` |
| `fastplace/task/ClientWorkspacePlacementTask` | 是服务端写世界任务，唯一调用方是 `OperationManager` | `server/task/SubmittedWorkspaceTask` |
| `workspace.model.ClientSelectionPart` / `ClientBlockSnapshot` | 服务端也在用 | `SelectionPart` / `BlockSnapshot` |
| `workspace/preview/WorkspacePreviewComposer` | 被服务端任务和 validator 使用，不是“预览” | `common/workspace/composition/WorkspaceComposer`（去掉静态缓存） |
| `GeometryPalette` / `VisualTheme` / `DistanceCurve` | 纯客户端视觉，只因架构检查才留在共享包 | `client/preview/style` |
| `network/sync/PlayerPreviewSync` | 服务端状态仓库，不是协议代码 | `server/sync/PreviewSync` |
| `network/client/ClientPayloadDispatcher` | 纯客户端类 | `client/net/inbound` |
| `client/placement/ClientPlacementRouter` | 实为发包网关 | `client/net/outbound/ServerGateway` |
| `client/session/ClientTickMailbox`、`ClientStorageQueue` | 通用工具，造成 session ↔ selection 双向依赖 | `core/async` |
| `world/WorldTaskFeature` | 聊天栏和动作栏 UI | `server/feedback` |
| `PolyhedronGenerator` | “多面体”实际生成的是球 | 改名或内联 |
| `FaceRasterizationMode.NORMAL_PLANE_EXPERIMENTAL` | 带“实验”字样，却是唯一线上算法 | `QuadFaceRasterizer`，删除枚举 |
| `FastPlaceClientPreview extends FastPlaceClientPreviewCore` | 只用继承暴露静态方法；注释说的“反射”并不存在 | 删除 |

### 1.5 mixin 按目标领域分组

`placement/mixin` 的 18 个 mixin 中只有 2 个属于放置。改为：

```
mixin/{history, workspace, interaction, sideeffect, placement, client}/
```

每组在 mixins.json 中单独成段。目前有 4 个 mixin 都以 `Level.class` 为目标，应在分组文档中标注它们的注入顺序。

### 1.6 根目录与测试树

**根目录**
- `run/` 下约 40 个按日期命名的测试目录（`history-*-20260919` 等），由 gametest 任务清理或改到 `build/`。
- 删除 `TEMPLATE_LICENSE.txt`（Git 已跟踪）。

**测试树**
- 8 个测试类放错包，例如 `client/input/SelectionPointerPressTest`。
- `OperationSelectionSessionTest` 放在 `fastplace` 根包，违反了 main 代码的同名规则。
- 没有共享 fixture，同一辅助方法反复手写：`snapshot(` 17 份、`part(` 10 份、`nonZero` 6 份。
- 新建 `src/test/.../testkit/`，包含 `Snapshots`、`Parts`、`VoxelAsserts`、`Projection`。
- 21 个测试类在用反射。第 2 节实例化完成后，这些反射应全部删除；这也是衡量实例化是否完成的验收指标。

---

## 2. 类组织层面

### 2.1 上帝类拆解清单

只列职责边界，拆分的落点见第 3、4 节。

**`client/render/core/FastPlaceClientPreviewCore`（2847 行，至少 9 种职责）**

| 行号 | 职责 |
|---|---|
| 233–355 | 网络载荷入口 |
| 355–925 | 约 60 个交互命中查询，被 input 层反向调用 |
| 925–1716 | 整个 HUD |
| 1786–1975 | 世界渲染 if 链 |
| 1977–2235 | 几何计划缓存 |
| 2236–2800 | 绘制原语 |
| 2311–2365 | 会话生命周期 |
| 2365–2392 | 射线 |

**`client/input/FastPlaceClientInput`（1938 行）**

| 行号 | 职责 |
|---|---|
| — | 事件订阅 |
| 239–390 | 按键（硬编码 GLFW 键码） |
| 796–1111 | 鼠标路由 |
| 582–792 | 原版 interaction 拦截（与鼠标路由重复一遍决策树） |
| 96–160 | 网络请求生命周期 |
| 501–580、1676–1750 | 修饰键与径向菜单 |
| 1519–1573 | 全客户端 tick 编排 |

**`client/operation/controller/ClientOperationController`（1731 行，约 30 个静态可变字段）**

| 行号 | 职责 |
|---|---|
| 84–250 | 约 30 个单行转发 |
| 253–400 | 预览同步、重连 |
| 402–500 | 剪贴板 |
| 503–612 | 删除 |
| 614–723 | 草稿与智能选区 |
| 785–1092 | AABB / 面 / 变换调整 |
| 1094–1428 | 提交、回执 |
| 1438–1550 | 生命周期 |

**`client/session/ClientSessionManager`（1007 行）**

| 行号 | 职责 |
|---|---|
| 36–150 | 作用域 |
| 155–530 | 草稿 |
| 536–622 | 回调鉴权 |
| 624–815 | 回执 |
| 819–1007 | 路径与哈希 |

同一个 key 的状态分散在 6 张并行 Map 里（:37–48）。

**`server/session/FastPlaceManager`（1320 行）**

| 行号 | 职责 |
|---|---|
| 71–135 | 会话表 |
| 157–300 | 起形加点 |
| 359–550 | 模式调整 |
| 550–775 | 填充入队 |
| 780–919 | 全局清理级联：顺带清理其他所有 Manager |
| 921–978 | 恢复移交 |
| 1014–1221 | `tickTask`，单方法 207 行 |

**`server/input/ServerInputDispatcher`（1177 行）**
- 门禁样板 `interactionBlocked(player) || !canOperate(player)` 出现 31 次。
- `if (XxxManager.active)` 链。
- 网络入口与命令入口成对重复。

**`network/FastPlaceNetwork`（1041 行）**

| 行号 | 职责 |
|---|---|
| 59–247 | 注册 |
| 249–703 | 40 个 handler |
| 416–500、988–1030 | 上传准入 |
| 774–986 | 结果投递 |

**`fastplace/history/WorldHistoryManager`（1818 行）**

| 行号 | 职责 |
|---|---|
| 111–195 | 记录 |
| 220–281 | 两阶段持久提交 |
| 325–520 | undo/redo |
| 523–893 | 回滚交接（10 个 `startRollback` 重载） |
| 1032–1126 | tick |
| 1263–1365 | 加载分页 |
| 1575–1660 | 持久化重试 |

`OwnerState` 有约 20 个可变字段。`busy()` 与 `needsTick()` 的条件已经开始分叉：`needsTick` 没有 `snapshotLoadFailed`，`busy` 没有 `persistenceDirty`。

**`fastplace/recovery/PersistentRecoveryJournal`（1664 行）**
- 静态写入闸门。
- 实例生命周期。
- 修正文件。
- 启动扫描。
- **两套**回放：单文件与分段。
- palette 编解码。
- 文件工具。

**`fastplace/history/WorldChangeBatch`（1184 行）**

| 行号 | 职责 |
|---|---|
| 133–443 | 捕获，7 个重载 |
| 502–917 | NBT 编解码 |
| 942–1048 | 应用 |
| 1063–1146 | 内存估算 |

**`fastplace/session/OperationSession`（1127 行）**
- 点集、gizmo、堆叠、拖拽、undo 混在一个类里。
- undo 语义与 `GeometrySession` 不一致：前者是快照栈；后者是“删点”，且 `canUndoStep()` 恒为 `true`。

**`fastplace/geometry/generation/FastPlaceGeometry`（781 行）**
- 生成分派、射线和候选点解析（:324–683）、10 参数的 `Modes` record。

**`fastplace/geometry/generation/TranslatedScanFaceRasterizer`（2467 行）**
- 60 个静态方法、19 个私有 record。
- 只服务于“多边形面恰好 4 个顶点”这一种情况（`PlanarFaceRasterizer.java:181`）。

### 2.2 状态归属错误

**服务端**
- 以 UUID 为键的全局静态表约 25 张，分散在约 10 个类中。
- 容器混用：`HashMap` / `ConcurrentHashMap` / 以 `ServerPlayer` 为键的 `WeakHashMap`（`IdleInteractionSnapshots:25`）。
- 同一个玩家的状态没有归属对象。

**客户端**
- 工作区和选区按 `SessionKey` 隔离，但以下状态是进程级静态：`serverPreview`、`WORKSPACE_SUBMISSION`、`submittedPartIds`、5 个 capture、`CLIPBOARD_STATE`。
- 切换维度后只能靠 `() -> workspace() == owner` 这类闭包防护（`ClientOperationController:337,1162,1600`）。
- `FALLBACK_SELECTION_SESSION`（:65）是一个全局“幽灵会话”。

**输入层**
- `ClientInputSession:38–88` 有约 30 个包级可变字段。
- “当前是否有指针手势”分散在 13 个字段里，并被手写成 4 份不一致的析取：
  - `pointerGestureInFlight`
  - `leftMousePressAlreadyHandled`
  - `blocksDraftLoad`
  - `PointerDragTicker.advance`

**状态机形同虚设**
- `ClientInputStateMachine` 的 State 不是由事件驱动，而是每次从外部标志“观察”出来的（`synchronizeInputState`，1783）。
- `QUICK_REPLACE` 已经是 State 成员，处理器却仍有 23 处直接短路 `QuickReplaceMode.active()`。

### 2.3 双套实现与职责重叠

- **工作区解析缓存两套**：`WorkspacePreviewComposer.RESOLVED_CACHE` 与 `WorkspaceInteractionResolver.RESOLVED_CACHE`。前者的 `resolvedForPart` 在生产代码中没有调用者。
- **IO 模型两套**：草稿和回执同时走异步 `ClientStorageQueue` 与主线程同步读写（`settleDraftFile:362`、`saveReceiptStore:729`）。同一文件可能同时被异步写入和同步改写。
- **背压两层**：`WorldHistoryPersistence` 与 `HistoryBatchStore` 各有一套队列上限，错误来源无法区分。
- **IO 执行器三个**：策略不一致，recovery 的执行器是无界队列。
- **block state 编码两套，语义相反**：history 遇到未知属性抛异常（`WorldChangeBatch:740`），recovery 静默降级为默认状态（`PersistentRecoveryJournal:1413`）。
- **完整性校验四种**：CRC32 信封、基于 `toString` 的 CRC、`31*h+crc` 链式摘要、SHA-256。

---

## 3. 目标形态：独立系统 + 命令式调度

这是本手册的核心。原则是：**每个子系统是一个 `*System`，对外只暴露“命令”（Intent）和“查询”（View）；系统之间不直接 import 彼此的内部类。**

这与写 mcfunction 的方式一致：用 `/function ns:sys/do {args}` 调度，而不是去读写别的数据包的内部计分板。

### 3.1 Intent：一切玩家意图都是一条命令

```java
// core/command
public interface Intent { }                              // 标记接口；每种意图是一个 record
public record IntentContext(ServerPlayer player, Source source, long requestId) { }
public enum Source { NETWORK, COMMAND, INTERNAL }

public interface IntentHandler<I extends Intent> {
    Class<I> type();
    Gate gate();                                         // 进入前的门禁，见 4.3
    IntentResult handle(IntentContext ctx, I intent);
}

public final class IntentBus {                           // 唯一调度器
    public <I extends Intent> void register(IntentHandler<I> handler);
    public IntentResult dispatch(IntentContext ctx, Intent intent);   // 先过 gate，再交给 handler
}
```

收益：
- `ServerInputDispatcher` 的 31 处门禁样板、网络与命令入口成对重复（`shortModifier`/`commandCycleMode`、`scroll`/`commandAdjust`）全部消失。
- 命令行 `/ff ...` 与网络包都只构造 Intent 再 `dispatch`。
- 命令注册可以从 Intent 的 record 字段**自动生成**（Brigadier 参数映射），`FastPlaceCommandRegistry`（855 行）中的字符串 switch 随之消失。
- 测试只需构造 Intent，不需要模拟网络包。

### 3.2 Mode：交互模式的元系统（最重要的扩展点）

现在新增一种交互或形状模式，要改 11～14 处，没有编译期保障：
- 输入层：`ClientInputStateMachine.State`、`ObservedInputState`、四个事件处理器、`cancelActiveSession`……
- 预览层：`PreviewRenderOwner`、两条 if 链、`PreviewRenderResources` 登记……
- 服务端：`GeometryManager` 中 10 处 `mode != X`、`FastPlaceClientPreviewCore` 中 12 处、径向菜单、命令字符串 switch、payload……

改为**一个模式 = 一个包 + 一次注册**：

```java
// common/mode
public interface ModeId { ResourceLocation id(); }

// server/mode
public abstract class ServerMode<S extends ModeSession> {
    public abstract ModeId id();
    public abstract S open(ServerPlayer player, ModeOpenArgs args);
    public void tick(S session) { }
    public void onIntent(S session, Intent intent, IntentContext ctx) { }   // 交给通用 IntentHandler 兜底
    public ModeCapabilities capabilities() { return ModeCapabilities.NONE; } // 可撤销、可闭合路径、可滚动……
    public PreviewPayload preview(S session) { return null; }
}

// client/mode
public abstract class ClientMode {
    public abstract ModeId id();
    public InputResult onKey(KeyInput in, InputView view)        { return InputResult.PASS; }
    public InputResult onPointer(PointerInput in, InputView view) { return InputResult.PASS; }
    public InputResult onScroll(ScrollInput in, InputView view)  { return InputResult.PASS; }
    public void tick(ClientModeContext ctx) { }
    public PreviewMode preview() { return PreviewMode.NONE; }     // 3.3
    public boolean blocksDraftLoad() { return false; }
    public int priority() { return 0; }                           // 同时激活时的优先级
}

// 注册（像写一个数据包的 load.mcfunction）
ModeRegistry.register(Modes.SPHERE, new SphereServerMode(), new SphereClientMode());
```

**现有模式迁移表**：

| 现状 | 目标 Mode 实现 |
|---|---|
| `InteractionState.VANILLA` | `IdleMode`（承载空手放置、空闲入口） |
| `InteractionState.BUILDING` + quickshape | `QuickShapeMode`，下设 point/line/face/volume 子阶段 |
| `InteractionState.GEOMETRY` + `GeometryMode.*` | 每个 `GeometryWorkflow` 一个 `ServerMode` |
| `InteractionState.OPERATION` | `SelectionMode`，下挂 `SelectionTool` 策略：Cuboid/Prism/Smart |
| `SPECIAL_ITEM` | `SpecialItemMode`，下挂 `SpecialItemHandlers` 注册表 |
| QuickReplace / SmartFill | `QuickReplaceMode` / `SmartFillMode` |

`docs/principles/pseudocode/sessions/01-空闲 … 04-特殊物品` 正好对应这几个 Mode，空目录 `client/session/{empty,quickshape,specialitem,...}` 也就有了落点。

**`ClientInputStateMachine` 改为由 ModeRegistry 推导**：
- 当前模式 = 已激活且优先级最高的 Mode。
- 事件路由 = `activeMode.onX(...)`，没有处理（PASS）时回落给 `IdleMode`。
- 删除 `ObservedInputState`、`vanillaSession()`、`activeSession()` 三份重复事实。

### 3.3 PreviewMode 与 PreviewFrame：渲染管线

```
protocol payload ─► PreviewStateStore（不可变快照 + revision）
               ─► PointerResolver.resolve(...)   每帧只算一次 → PointerState
               ─► PreviewFrame{pose, buffers, camera, partialTick, opacity, AnimationPhase, paletteRev, pointer}
               ─► activeMode.preview().buildModel(frame)   纯计算，可读缓存
               ─► LatestOnlyTask 网格缓存（键 = model 身份 + paletteRev）
               ─► preview.drawWorld(frame, meshes) / preview.drawHud(frame, gui)
```

- **opacity 不再隐式传递**：删除静态 `worldPreviewOpacity`（Core:178），它目前被 `import static` 后在绘制原语中暗中相乘。
- **动画时钟统一**：三套相位（`pendingGridDashOffset`、`ghostBreathPulse`、`BoilClock`）合并为一个 `AnimationPhase`。
- **射线只算一次**：Core 中 `raycastBlocks` 有 15 处调用；`operationInteractionIntent()` 在 1435、1760、1818 行各算一次，同一帧内结果可能不一致。改由 `PointerResolver` 每帧计算一次，结果供 input、HUD、world 三处共享。
- **input 层不再依赖渲染层**：15 个 input 类反向调用 Core 的地方，改为依赖只读接口 `InputView` 与 `PointerResolver`。

### 3.4 状态归属：PlayerState / ClientScope

```java
// core/lifecycle
public interface Scoped { default void onDetach() {} void onDiscard(); default void onDimensionChanged(DimensionChange c) {} }

// server/player
public final class ServerPlayerState {                  // 一个玩家一份，按 UUID 存放
    <T extends Scoped> T get(ScopedKey<T> key);           // 延迟创建
}
public final class PlayerStateRegistry {                 // 一张表持有所有玩家
    public static <T extends Scoped> ScopedKey<T> key(String id, Supplier<T> factory);
}
// 各系统只声明自己的状态 key：
static final ScopedKey<QuickShapeSlot> QUICK_SHAPE = PlayerStateRegistry.key("quick_shape", QuickShapeSlot::new);
```

- **服务端清理统一**：Logout、Clone、ChangedDimension、ServerStopping 只需调用 `PlayerLifecycle.detach/discard/dimensionChanged(uuid)`，由注册表遍历所有 `Scoped`。现存的不对称问题一次消除：
  - Logout 不清 `ServerInputState`；
  - 换维不清 `QuickReplace`；
  - `clearServer` 被调用 3 次。

  补一个 gametest：logout 之后，任何 Scoped 表中都不存在该 UUID。
- **维度停放**：`PlayerSessionSlot<S>` 内含 active + parked 两个槽，取代 `FastPlaceManager:859`、`OperationManager:891`、`GeometryManager:398` 三处逐字复制的 park/restore。
- **客户端对应**：`ClientScope`（连接 + 维度 + 玩家）持有 `OperationContext`、`InputSession`、`Workspace`、`DraftRepository`、`ReceiptRepository`。`WorldSessionEvents.onLeave(reason)/onEnter(scope)` 是唯一的清理入口，从渲染层迁出。
- **内存泄漏**：`ClientSessionManager.playerSessions` 只有 `computeIfAbsent`，从不移除，每个访问过的“服务器 × 维度”的整个工作区都会常驻内存。改为离开时写盘并驱逐。

### 3.5 任务系统

```java
// server/task
public interface WorldTask { TaskStep tick(WorldTaskContext ctx); void cancel(CancelReason r); }
public abstract class LeasedJournaledTask implements WorldTask {   // 持有 lease、journal、commit、metrics
    protected abstract TaskStep tickBody(WorldTaskContext ctx);
    // acquireLease / releaseLease / releaseAfterCancelledJournal / stopAndTransferRecovery 只写一次
}
public final class WorldTaskQueue<T extends WorldTask> { /* TASKS + HANDOVERS + 移交 + tick 循环 */ }
```

消除的重复：
- 租约代码三份复制：`PlacementTask:613`、`SelectionOperationTask:797`、`ClientWorkspacePlacementTask:522`。
- 恢复移交两份复制：`FastPlaceManager:921–995` 与 `OperationManager:574–688`。
- `PlacementTask` 没有实现统一接口，却手写了同名方法。

### 3.6 历史与恢复改为实例化服务

**`HistoryService`**：每个 `MinecraftServer` 一个实例，取代静态 `WorldHistoryManager`。

```
server/history/
  HistoryService           对外 API：record / undo / redo / rollback(RollbackRequest)
  owner/OwnerHistory       原 OwnerState，自己负责 busy/needsTick/trim，修正两者的条件分叉
  commit/DurableCommit     显式状态机：PUBLISH_BATCH → SEAL_JOURNAL → PUBLISH_INDEX → DONE | FAILED
  rollback/RollbackRequest 10 个 startRollback 重载 → 1 个 record + 1 个入口
  entry/HistoryEntry       扩展点：size/apply/match/encode/footprint；BlockChangeEntry = WorldChangeBatch
  entry/EntryCodecRegistry 按必填 "Type" 字段分派；不补旧格式缺失字段
  store/  compaction/  memory/  vanilla/
server/recovery/
  RecoveryJournal  startup/JournalStartupRecovery  startup/JournalReplayer(单一回放循环)
  spi/HistoryReconciler                  由 history 实现后注入，打破 history ↔ recovery 环
```

`WorldHistoryManager:464` 判断能否撤销时直接调用 4 个 Manager，改为注入 `OwnerActivityGate`，即 4.3 的 `Gate`。

---

## 4. 轮子库（`core/`）：七个“咒语”

这些是全项目共用的原语，写成像第三方库一样的 API，配完整单元测试和 javadoc。

### 4.1 `core/registry`：通用注册表

```java
public final class Registry<K, V> {
    public static <K, V> Registry<K, V> create(String name);
    public V register(K key, V value);              // 重复注册直接抛异常
    public Optional<V> get(K key);
    public Stream<V> ordered(Comparator<V> order);   // 按优先级遍历
    public Registry<K, V> freeze();                  // 加载完成后冻结
}
```

以下都基于它构建：IntentBus、ModeRegistry、Packets、TinkerRules、EntryCodecRegistry、ThemeRegistry、SpecialItemHandlers（已有雏形，见 `SpecialItemHandlers:22`）。

### 4.2 `core/net`：声明式网络通道

现状：
- 61 次注册样板：`enqueueWork` 61 次、`instanceof ServerPlayer` 41 次、`var connection = ...` 20 次。
- 客户端 `ClientPayloadDispatcher.deliver` 有 20 个 switch 分支，`ClientPayloadHandler` 有 20 个重载。
- 新增一个 S2C 包要改 6 个文件、7 处。

```java
Packets.c2s(OperationPointPayload.TYPE, OperationPointPayload.CODEC)
       .toIntent(p -> new OperationPointIntent(p.pos(), p.axis()));     // C2S 包直接变成 Intent
Packets.s2c(SmartFillPreviewPayload.TYPE, SmartFillPreviewPayload.CODEC)
       .accept(ClientAccept.PREVIEW_SCOPE)                              // 连接校验策略
       .handle(SmartFillMode::applyPreview);                            // 自动进入 tick 邮箱
```

- **设置包合并**：`MiddleConfirmSettingPayload`、`TinkerHistorySettingPayload`、`InteractionUpdatesPayload` 等每个设置一个包类。合并成一个 `SettingPayload(key, value)`，配合 `SettingKey<T>` 注册表（4.1）。新增设置只需一行。
- **codec**：统一使用 `StreamCodec.composite`；`ResourceLocation.fromNamespaceAndPath("fastformer", …)` 硬编码了 65 次，改为 `FastFormer.id("…")`。
- **客户端发包**：68 处 `hasChannel` 检查收敛成 `ServerGateway.trySend(payload)`，或在连接建立时一次性协商出 `ServerCapabilities`。
- **包目录按方向分**：`protocol/c2s/<feature>`、`protocol/s2c/<feature>`。

### 4.3 `core/gate`：可组合的门禁谓词

现状是 4 种互不等价的“正忙”定义：

| 定义位置 | 包含 SmartFill | 包含写锁 |
|---|---|---|
| `ServerInputDispatcher.interactionBlocked:82` | 是 | 否 |
| `OperationManager.operationBusy:931` | 否 | 是 |
| `FastPlaceManager.placementBusy:1226` | 否 | 是 |
| `FastPlaceEvents.onItemToss:114` | 另外手写 7 项 OR | — |

命令路径 `FastPlaceCommandRegistry:110` 绕过了 SmartFill 检查。

```java
public interface Gate { GateResult test(ServerPlayer p);
    default Gate and(Gate o); static Gate not(Gate g); }
// 原子谓词由各系统注册
Gates.SMART_FILL_RUNNING, Gates.WRITE_LOCKED, Gates.HISTORY_BUSY, Gates.TASK_RUNNING, Gates.EDITING
// 组合即策略
Gate OPERATE   = not(SMART_FILL_RUNNING).and(not(WRITE_LOCKED)).and(not(TASK_RUNNING));
```

`IntentHandler.gate()` 返回其中一种组合。命令和网络走同一个 gate，就不会再出现绕过。客户端的 `canMutate()` 守卫（≥8 处写法各异）使用同一套模式。

### 4.4 `core/async`：LatestOnlyTask 与邮箱

- **`LatestOnlyTask<K,V>`**：负责版本号、取消、按 tick 发布结果、可配置的失败策略（退避 / 清空 / 抛出）。取代以下三套实现：
  - `PendingGhostMeshCache:54–168`
  - `BuildingShellCache:154–231`
  - `BuildingPreviewGenerationOwner`

  三者的 `cancelFuture` 一字不差；`BuildingPreviewGenerationOwner` 会把 `RejectedExecutionException` 抛进渲染事件。
- **`TickMailbox`**：由原 `ClientTickMailbox` 迁入。
- **`IoExecutors`**：统一创建、命名和关闭全部后台线程，包括 history-io、compaction、recovery、客户端存储、预览生成。
  - `ClientStorageQueue` 每构造一个实例就注册一个 shutdown hook，改为由这里统一管理。
  - 所有执行器挂在服务器或客户端实例上，在 `SERVER_STOPPED` 时显式关闭。

### 4.5 `core/persist`：原子文件、信封、编解码

- **`AtomicFiles.write(path, writer)`**：原子移动失败时直接报错，不再回退；写入后对父目录做 fsync。
  - 取代 `PersistentRecoveryJournal:1459`、`RecoveryJournalManifest:66`、`RecoveryJournalSegment:67` 三份复制。
  - 这三份都会在原子移动失败时回退到非原子的 `REPLACE_EXISTING`，恰好在最需要崩溃一致性的地方。
- **`Envelope`**：Magic + Version + CRC32C + DataVersion，统一前述四种完整性方案。
- **`BlockStatePaletteCodec`**：写入 DataVersion，读取时只接受当前 Minecraft 数据版本；history 和 recovery 共用严格校验，不执行迁移。
- **`StoragePaths`**：`"fastformer-recovery"` 和 `"segment-%06d.dat"` 等字面量统一在这里定义，目前分别重复了 2 次和 4 次。

### 4.6 `core/math`：向量与容差

```
core/math/Vectors   normalize(v)  dominantAxis(v) → Axis  component(v, Axis)  rotateEuler(v, rot)  rotateAround(...)
core/math/Axis      独立枚举，取代被 workspace/transform 下 7 个文件当数学轴用的 AxisGizmo.Axis
core/math/Epsilon   LENGTH = 1e-7, ANGLE = 1e-5, PLANE = 1e-9 —— 有语义的常量，取代 26 处 EPSILON 和 38 处字面量
core/math/Voxels    hollow(solid, max, observer)  isBoundary  SIX_NEIGHBORS  flood(start, predicate)
```

| 合并对象 | 现状 |
|---|---|
| `normalize` | 7 份，阈值分为 `length<1e-7` 与 `lengthSqr<1e-7` 两种，相差约 3000 倍 |
| `dominantAxis` | 4 份，返回类型各不相同 |
| 按轴取分量 | 8 份 |
| 六邻域外壳 | 6 份 |
| 欧拉旋转 | 3 份 |
| `isParallelogram` | 5 份 |
| `Pixel` / `project` / `localHeight` | 3 份 |

`safeInt` 有两种语义（截断 / `toIntExact`），统一为一种。

### 4.7 `core/rule`：规则链（以 Tinker 为样板）

`BlockTinker.resolveAction`（:142–180）是一条硬编码的 `if (x != null) return` 链，依次是：property override → copper → structure → state → shift 拦截 → ground → torch → wood → shape family → specialized → generic。

```java
public interface TinkerRule { int priority(); boolean shiftAware(); TinkerAction apply(TinkerContext c); } // PASS = 未处理
TinkerRules.register(new CopperCycleRule());   // 每条规则一个类，按 priority 排序
```

这样新增一种可调方块不需要修改 `BlockTinker`，也为将来让数据包或附加模组提供规则留出了位置。现有的 `TinkerPropertyRule`、`TinkerWoodFamily`、`TinkerShapeFamilies` 已经由数据驱动，正好作为其中的“数据型规则”。

---

## 5. 代码编写层面

### 5.1 必须修的潜在缺陷

以下各条已复核原代码。

| # | 位置 | 问题 |
|---|---|---|
| B1 | `client/input/FastPlaceClientInput.java:658–740` | `operationClickCapturedButton` 以 `-1` 表示空，但 `onInteraction` 写入 `0` 表示“已处理”，而 `0` 同时是左键编号。一个 int 编码了三种含义。改为 `Optional<Button>`，或由 3.2 的 `ActiveGesture` 取代 |
| B2 | `fastplace/recovery/RecoveryJournalSegment.java:61` | 分段 CRC 基于 `CompoundTag.toString()`（SNBT 文本）计算。改为对字节计算 CRC32C；分段格式升为 v2，删除 v1 读取器 |
| B3 | `WorldChangeBatch:47,506` / `PersistentRecoveryJournal:762` | history 不存 DataVersion；recovery 遇到版本不一致时全局封锁写入。统一严格检查当前 DataVersion，不迁移旧数据；不支持的数据隔离到 `quarantine/` 并通知管理员 |
| B4 | `PersistentRecoveryJournal` 分段回放 | 没有 DataVersion 检查，单文件路径（:761）有，两条路径行为不一致 |
| B5 | `client/session/ClientSessionManager` | `playerSessions` 永不回收（见 3.4） |
| B6 | `ClientSessionManager:274–288` | `recordUnsentSubmission` 忽略 `submitPrepared` 的返回值，队列满时静默丢失回执 |
| B7 | `ClientOperationController.onDisconnected:1478` 与 `clearWorkspace:1450` | 两者清理的字段集合不同：断线时不取消 5 个 capture，也不清 `submittedPartIds`、`pendingPointAdjustment` 等 |
| B8 | `fastplace/settings/FastPlaceSettings.java:53,114` | `maxPlacement = 20972152`，疑似 20971520（2²¹×10）的笔误，同一值在两处重复 |
| B9 | `client/render/theme/VisualThemes.java:28` | `revision*2 + ordinal` 在主题超过 2 个时会碰撞，导致漏失效。改为单调递增计数器（目前只有 2 个主题，属于潜伏问题） |
| B10 | `WorldHistoryManager:122,150,156,1444,1470` | `catch (RuntimeException \| OutOfMemoryError)` 后不记日志；`record()` 吞掉异常后转入只存在于内存的 `pendingRecords`，服务器崩溃会丢失这条历史 |
| B11 | `OperationSession.java:30,658` | `OperationPointDragPayload` 只限制单包位移 ≤128，没有可达距离校验。统一用 `ClientPointValidator` 检查可达范围、单包位移和世界高度 |
| B12 | `FastPlaceNetwork.java:46` | `serverEpoch` 是非 volatile 的静态变量，在异步回调里被读取，靠隐式线程切回保证正确。补上 `assert server.isSameThread()` 并加注释 |

以下两条子审查的结论，核实后**修正为非缺陷**：
- **`FastPlaceSettings.setFaceRasterizationMode`（:152）恒写 `DEFAULT`**：这是有意锁定的，属于“名存实亡的设置”，按冗余处理（6.2）。
- **“预览旋转缺少 symmetry”**：涉及的 `PreviewGeometrySupport.rotateLocalAxis` 在生产代码中没有调用者，按死代码删除。

### 5.2 写法规范（加入检查）

1. **禁止全限定类名内联**。现状：`FastPlaceClientPreviewCore` 129 处、`ClientOperationController` 75 处、`FastPlaceNetwork` 38 处、`ServerInputDispatcher` 32 处。
2. **禁止多个按位置传递的布尔参数**，改为 record 或 enum：
   - `leftPressTarget`：9 个参数，内部构造约 20 个布尔值的 `LeftState`；
   - `ObservedInputState.Sessions`：9 个布尔值；
   - `DragAdvanceSemantics.State`：8 个布尔值；
   - `sendWorkspaceResult(accepted, retryable, recoveryCreated)`：3 个布尔参数，另有 3 个重载 → `WorkspaceOutcome`；
   - `submittedPartIds == null` 加 `submittedDraft` 隐式表达三种提交模式 → `sealed SubmissionScope`。
3. **禁止“读后即清”的静态错误通道**：`lastOperationFailureKey`（`ClientOperationController:497`）改为返回 `Result<T, FailureKey>`。
4. **按键改为 `KeyMapping`**：`handleKey` 中硬编码了 GLFW 键码 257/335/82/90/89/81。鼠标常量统一为 `Button`、`Action` 枚举，替代 25 处字面量 `getAction()==1`。
5. **requestId 统一由单调计数器生成**：`OperationPointCommandDispatcher:8` 目前用 `nanos & Long.MAX_VALUE`。
6. **世界变更动作一律入队执行**：鼠标回调中现在一部分动作直接执行、一部分进入邮箱，竞态只能靠 token 打补丁。
7. **HUD 布局常量**：只能来自 `PreviewStyle` 或主题。`onRenderGui` 写死 `8,8/8,20/8,32`，而 `PreviewStyle.HUD_MARGIN` 已存在却没有被使用。
8. **相机平移封装**：`poseStack.translate(-camera...)` 加 push/pop 在 8 个文件中重复，改为 `WorldSpace.with(pose, camera, body)`。
9. **颜色用值对象**：`renderGhostOutline(r,g,b,a)` 加三次 `.red()/.green()/.blue()` 展开，改为传 `Color` 对象。
10. **删除无用的通配导入**：`import ...fastplace.world.*;` 全仓 43 处，几何部分 18 处里有 17 处未使用。
11. **测试钩子**：`*ForTest` 与 `*ForTesting` 在 geometry/workspace 下有 24 处、`WorldHistoryManager` 有 15 处。实例化之后改为构造注入，钩子删除。
12. **玩家提示统一**：客户端有 5 套发送提示的方式，统一走 `ClientNotifier`；服务端提示走 `server/feedback`。

---

## 6. 冗余与死代码清单（可立即删除，无行为风险）

以下各项都已用 grep 核对：统计范围为 main、test、gametest，并排除自身文件。

### 6.1 整类删除

| 类 | 行数 | 引用情况 |
|---|---|---|
| `fastplace/geometry/generation/BoundaryConstrainedFaceRasterizer` | 686 | 仅 1 个测试 |
| `fastplace/geometry/generation/ConvexPolyhedronGenerator` | 275 | 已被 Arbitrary* 取代；仅 2 个测试 |
| `fastplace/geometry/generation/BoxGenerator` | 84 | 全仓零引用 |
| `fastplace/geometry/ScalarAdjuster` | 33 | 全仓零引用 |
| `fastplace/history/HistoryStore` | 136 | 仅测试 |
| `client/render/FastPlaceClientPreview` | — | 继承壳 |

### 6.2 已归档的面光栅化模式（约 1000 行）

`FaceRasterizationMode.next()` 与设置的 setter 都固定写成 `NORMAL_PLANE`，因此以下内容都是死代码：

- `BoundaryInterpolatedFaceRasterizer` 中的部分：
  - `SweepField`（:690–795）
  - `CrossBoundaryField`（:795–1145）
  - `attempt` 中 :94–277 不可达
  - `compareCandidates` 中的模式分支（:285）
- 默认传 `POINT_SWEEP` 的便捷重载，散布在 `PlanarFaceRasterizer`、`QuadFaceGenerator`、`PrismGenerator`、`TiltedBoxGenerator`、`BresenhamColumnVolume` 中，共 10 处以上。它们也是隐患：新调用方漏传 mode，就会悄悄走回旧算法。
- 枚举值 `POINT_SWEEP`、`GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL`。
- 对应的设置字段、`FaceRasterizationSettingPayload`、设置 UI 项。

剩余的“边界采样 + 定义域”部分（:40–84）与 `NormalPlaneFaceRasterizer` 合并为 `QuadFaceRasterizer`。

### 6.3 待决策：`TranslatedScanFaceRasterizer`（2467 行）及其辅助类（约 2700 行）

它只服务于“多边形面恰好 4 个顶点”这一种情况。

**建议**：让这种情况复用平行四边形管线 `QuadFaceGenerator`，然后整体删除：
- `TranslatedScanFaceRasterizer`
- `FlowGraph`、`CountingUnionFind`、`ExteriorUnionFind`
- `BresenhamFaceSweep` 中只剩转发的部分

**需要作者判断**：这条路径的视觉效果是否有不可替代的价值。如果保留，就拆成 `generation/face/scan/` 子包，分为 4～5 个类。

### 6.4 方法级死代码

| 文件 | 死代码 |
|---|---|
| `FastPlaceClientPreviewCore` | `emptyBuildingPreview` 是常量 `false`（:1817），其分支 1820/1825/1854 全部不可达 |
| `FastPlaceClientPreviewCore` | 无调用者：`renderBuildingFallbackPoints`（:2236）、`renderBuildingEndpointFallback`（:2393）、`selectionDashOffset`（:2592）、`normalize`（:2726） |
| `FastPlaceClientPreviewCore` | 纯别名：`pendingDashOffset`、`renderStaticOperationGuideLine` |
| `WorkspacePreviewComposer` | `resolvedForPart`（:163）及其两份静态 LRU |
| `ClientOperationController` | `SOURCE_CAPTURE` 从未调用 `start`；`sourceCheck*` 三个字段只有声明和置空 |
| `ClientOperationController` | `sourceState()` 永不返回 `PENDING` / `SOURCE_CHANGED`，导致 `aabbAdjustDecision` 中两个分支不可达 |
| `ClientOperationController` | main 中无调用者：`activeSelectionTransformed`、`sourceSnapshotMatches`、`fixActiveSelection`、`selectAllWorkspaceParts`、`deselectAllWorkspaceParts`、`adjustActiveAabbPoint` |
| `ClientSessionManager` | 只有测试在用：`forPlayer` / `forScope`、`install*File`、带 override 的重载 |
| `PersistentRecoveryJournal` | `writePrepared`（:1278）无调用者；删除单文件读写分支、单文件路径辅助函数与 `segmented` 字段，仅保留当前分段格式 |
| `OperationSession` | 3 参版 `dragPointTo`（:596–617）只被测试使用 |
| `TiltedBoxGenerator` | 私有重载 `outline`（:143,149）、`degenerateSolid`（:215,221） |
| `PreviewGeometrySupport` | `rotateLocalAxis`（:150） |
| `InteractionContext` | `previewVisibility` 恒为 1，Core 中 3 处 `<=0.01F` 判断随之不可达 |
| `client/input` | `directlyNearVanillaBlock` 无调用者；`selectionOwnsPointer`、`vanillaOwnsEmptyHandClick` 只被测试引用 |
| `fastplace/task/SelectionOperationTask.java:24` | 死导入 `OperationManager`，同时是一个向上依赖 |
| `client/input` | 单行转发：`sendOperationEdgeInsertion` 等 5 个、`suspendPointerGesture`；`currentSession()` 与 `inputSession()` 重复；`ClientPhysicalInputDispatcher` 整个类只做转发 |
| `client/operation/render/SourceBlockRenderMask` | `render/mask` 的薄转发 |
| `FastPlaceClientInput` | 鉴权别名 `acceptsOperationPreviewCallback/ResultCallback`；`handleCreateClick` / `handleAltCreateClick` 是两份复制，只差一个布尔参数 |

### 6.5 逻辑重复（合并到同一处）

| 重复逻辑 | 位置 |
|---|---|
| 维度 park/restore | `FastPlaceManager:859`、`GeometryManager:398`、`OperationManager:891`，三份 |
| “仅本地剪贴板”判定 | `ClientOperationController:1135`、`:1506`、`ClientPlayerSession:190`，三份且语义略有差异 |
| `ClientOperationDraft` 构造 | `ClientPlayerSession:52` 与 `:119`，两份 |
| 中止提交样板 | `ClientOperationController:1146–1195`，五份 |
| 左键决策树 | `onInteraction:640–752` 与 `onMouseButton:936–1010`，两份且不一致 |
| “先按成对快照捕获，失败再从实时世界捕获” | `WorldHistoryManager:147`、`:1436`、`:1459`，三份 |
| 回放循环 | `recoverOne:776` 与 `recoverSealedSegments:876`；分段路径还把每个段读取、解压三遍 |
| 模式分派 if 链 | `onRenderLevelStage:1798` 与 `onRenderGui:950`，两份 |
| `OperationDragController:170–184`、`OperationPointInputController:189–203`、`FastPlaceClientInput:1928` | `finish token` 清理，三份 |
| `ServerInputDispatcher.closeActivePath:456–472` | 同一个 switch 写了两遍 |

---

## 7. 预留扩展点（面向未来）

按“将来很可能要加”的程度排序，每一项给出要预留的接口：

| 未来需求 | 现在的代价 | 预留接口 |
|---|---|---|
| 新形状或几何模式（锥、环、曲面、文字……） | 11～14 处 | `ServerMode` / `ClientMode` / `PreviewMode` 三件套 + `ModeRegistry`（3.2） |
| 新选区工具（套索、魔棒变体） | `OperationSelectionMode` 被 28 个文件直接引用 | `SelectionTool` 策略：`onPress` / `ready` / `volume` / `codec` + 注册表 |
| 新的可调方块 | 改 `BlockTinker` 的 if 链 | `TinkerRule` 注册表 + 数据驱动规则（4.7） |
| 新的特殊物品 | 已有雏形 | `SpecialItemHandlers` 迁到 `Registry`，并与 `SpecialItemMode` 挂接 |
| 新的放置效果（如木框之外的） | `placement/effect` 内硬编码 | `PlacementEffect` 接口 + 注册表 |
| 新的历史条目类型（实体、方块实体、生物群系） | codec、capture、apply、compactor、估算全都要改 | `HistoryEntry` + `EntryCodecRegistry`（3.6） |
| 新设置 | 每个设置要一个 payload、一个 handler、一处 UI | `SettingKey<T>` 注册表 + 通用 `SettingPayload` + 自动生成设置 UI 项 |
| 新主题，包括资源包加主题 | 改枚举、改硬编码色板，主题超过 2 个后 revision 会碰撞 | `ThemeRegistry`（以 `ResourceLocation` 为键）+ JSON 定义 + 单调 revision；颜色不烘焙进网格 |
| 新命令 | 改 855 行的注册类 | 从 Intent record 自动生成 Brigadier 命令 |
| 新网络包 | 改 6 个文件、7 处 | `Packets` 声明式注册（4.2）一行完成 |
| 新玩家状态 | 很容易忘记在某个清理入口登记 | `PlayerStateRegistry.key(...)`（3.4），清理自动覆盖 |
| 新异步预览 | 复制一份缓存类 | `LatestOnlyTask` 组合（4.4） |
| 新磁盘格式版本 | 各文件自管版本 | `Envelope` + 严格当前 DataVersion 校验；Alpha 阶段不提供旧格式迁移 |
| 与其他模组兼容（WorldEdit、Axiom 等） | 无 | `common/api`：对外的只读事件，包括 `ShapeCommitted`、`HistoryRecorded`、`ModeChanged`，用 NeoForge 事件总线发布 |
| 多人或权限 | 门禁分散 | `Gate` 原子谓词中加入 `Gates.permission(node)` |

**ID 约定**：所有注册键一律使用 `ResourceLocation`，如 `fastformer:sphere`。这样命令、配置、网络、数据包都能用同一个 ID 引用某个系统，和 mcf 里的 `namespace:path` 是同一种心智模型。

---

## 8. 执行手册

每个阶段都可以单独合并。每一步完成后都运行：

```bash
./gradlew.bat check
```

```bash
./gradlew.bat runGameTestServer
```

### 阶段 0：清理（约 1 天，零行为变化）
1. 删除 6.1、6.2、6.4 中的所有死代码；删除空目录、模板残留、`TEMPLATE_LICENSE.txt`；删除无用的通配导入。
2. 删除对应的测试和 `*ForTest` 钩子。
3. 预计减少约 3000 行生产代码和约 1500 行测试。

### 阶段 1：搭建轮子库（`core/`）
1. 编写 `Registry`、`Gate`、`LatestOnlyTask`、`TickMailbox`、`IoExecutors`、`AtomicFiles`、`Vectors` / `Axis` / `Epsilon` / `Voxels`，每个都配完整单元测试。
2. **只新增，不迁移**。迁移留到后续阶段，以便每一步可以单独回滚。

### 阶段 2：修复缺陷
1. 修复 B1～B12。
2. B2、B3 会改动磁盘格式：分段格式升到 v2、history 批次升到 v2，只支持当前格式；试玩服务端旧数据可清除，无需兼容 v1。
3. 给 `docs/plan/history-disk-format.md` 补充当前格式说明与已确认试玩旧数据的清理范围；不编写旧格式迁移器。

### 阶段 3：服务端实例化
1. 建立 `PlayerStateRegistry` 和 `PlayerLifecycle`，加 logout 清理的 gametest。
2. 用 `PlayerSessionSlot` 和 `WorldTaskQueue` / `LeasedJournaledTask` 消除复制代码。
3. 合并 4 种“正忙”定义，改用 `Gate`。
4. 把 `HistoryService` 实例化，引入 `HistoryReconciler` 接口打破 history ↔ recovery 环，把 `DurableCommit` 改成显式状态机。

### 阶段 4：Intent 与协议
1. 引入 `IntentBus`，把 `ServerInputDispatcher` 和命令注册类改成 Intent handler。
2. 用 `Packets` 声明式注册，拆分 `FastPlaceNetwork`；合并设置类 payload。
3. 把 `protocol/` 独立出来，确保它不依赖 server 和 client。

### 阶段 5：客户端实例化
1. 建立 `ClientScope` 和 `WorldSessionEvents`，把清理入口从渲染层迁出。
2. 把 `ClientOperationController` 拆成 `OperationContext` 下的 8 个实例组件；把 `ClientSessionManager` 拆成 Scope / Draft / Receipt 三个仓库，IO 统一走队列。
3. 输入层引入 `PointerGestureOwner` 和 `ActiveGesture`（sealed），删除 4 份手势析取和 token 字段。

### 阶段 6：Mode 元系统
1. 定义 `ServerMode`、`ClientMode`、`PreviewMode`，以及 `ModeRegistry`。
2. 先迁移 `QuickReplace` 和 `SmartFill` 两个最独立的模式作为样板，再依次迁移 Geometry、QuickShape、Selection、Idle。
3. 删除 `ObservedInputState`、`PreviewRenderOwner`，以及各处的 `mode == X` 判断。
4. 拆解 `FastPlaceClientPreviewCore`，按 `PreviewFrame`、`PointerResolver`、hud 拆开。完成后它和 `FastPlaceClientInput` 都应缩到 200 行以内，或者直接删除。

### 阶段 7：搬家与守护
1. 按第 1 节的目标包树迁移，包含 1.4 的改名。
2. 引入 ArchUnit，固化以下规则：
   - 分层单向；
   - `client` 与 `server` 互不依赖；
   - `protocol` 只依赖 `common` 和 `core`；
   - 包内类型数 ≤ 12；
   - 禁止使用 `*Manager` / `*Dispatcher` 等后缀；
   - `client.*` 子包之间无环。
3. 更新 `docs/source-layout.md`。
4. 整理测试：建立 `testkit/`，把放错包的测试归位。

### 每阶段的验收指标

| 指标 | 当前 | 目标 |
|---|---|---|
| 静态可变全局表（服务端） | 约 25 张 | 0（全部归 `PlayerStateRegistry`） |
| 测试中的反射与 `Unsafe` | 21 个类 | 0 |
| 新增一种几何模式要改的文件数 | 11～14 | 新包 + 1 行注册 |
| 新增一个网络包要改的文件数 | 6 | 2（record + 1 行） |
| 单文件行数超过 800 的类 | 约 20 | 0（算法类允许例外，但必须拆分子包） |
| 单包类型数超过 12 | 7 个包 | 0 |
| 跨层环 | `fastplace↔server↔network`，以及 client 内部 | 0 |

---


## 9. 数据化交互绑定（Interaction Bindings）与“长按右键画线”实现规格

> **需求**（2026-11-13，用户确认）：
> 1. 设置中提供开关“长按右键画线”，用来**替代原版的连续放置**。
> 2. 开关开启后，原版的长按右键由一个特殊会话接管。这个会话**只有线阶段**，不会进入面阶段。
> 3. 在这个会话中，**松开右键等同于按中键**：直接结束该阶段并写入世界。
> 4. 线模式固定为**横平竖直（`LineMode.AXIS`）**，**不能**改成自由滚轮（`FREE_SCROLL`）或视线落点（`RAYCAST`）。
> 5. 快捷操作**不做成单独的系统**，而是和现有的操作逻辑统一起来，用**数据化配置**提供高度自定义。
>
> 因此本节的设计是：把现有交互逻辑里“某种会话、某个阶段、某个输入 → 某个操作”的对应关系做成数据。
> - 内置的默认数据必须完整复现现有行为。
> - “长按画线”只是**一份会话变体数据，加上几条绑定数据**，再挂一个设置开关。
> - 以后新增快捷操作，只需写数据；只有出现新的原子操作时，才需要写一个动作类。
>
> 本节可以独立于第 8 节的阶段 3～6 实施，复用已落地的 `core/registry/Registry`。它也是第 3.2 节 Mode 元系统的数据层前身。

### 9.0 最终目标：整棵操作树数据化

“操作树”就是 `docs/principles/pseudocode/sessions/*.md` 描述的结构：

```
会话（空闲 / 快速起形 / 几何 / 选区 / 特殊物品）
  └─ 阶段（点 / 线 / 面 / 体）
      └─ 模式（横平竖直 / 自由滚轮 / 视线落点 / 坐标平面 / 基准面 / 多边面 …）
          └─ 输入（右键 / 左键 / 中键 / 滚轮 / Alt / Q / Enter …，以及按下 / 松开 / 长按 / 双击）
              └─ 动作（确认点 / 提前确认 / 删点 / 闭合 / 取消 / 切模式 / 让给原版 …）
```

最终状态：
- **这棵树完整地由数据描述**。代码中只保留“节点类型”（会话、阶段、模式）、“原子动作”和“上下文字段”这三类**原语**。
- 伪代码文档与默认数据**一一对应**。
- 用户和资源包可以重绑、删除、新增树上的任意分支。
- 数据包可以定义受约束的会话变体。
- “长按画线”是**第一个用这套数据实现的新分支**，用来验证整个机制。在它之后，现有分支逐棵迁移（迁移顺序见 9.4）。

### 9.1 三类数据

| 数据 | 说明 | 存放位置 | 谁持有、是否同步 |
|---|---|---|---|
| **会话变体**（Session Profile） | 某种会话的受约束变体，例如“只有线阶段、锁定横平竖直、松开即确认” | 数据包 `data/<ns>/fastformer/session_profile/*.json` | 服务端注册表，同步到客户端。写法参照 `TinkerPropertyRule` 的 `event.dataPackRegistry(REGISTRY, CODEC, CODEC)` |
| **交互绑定**（Binding） | 在什么上下文里、哪种输入、执行哪个操作 | 默认值放资源包 `assets/<ns>/fastformer/bindings/*.json`；用户覆盖放 `config/fastformer/bindings.json` | 客户端解析输入；服务端只校验操作本身 |
| **选项**（Option） | 设置界面上的一个开关，可以启用或禁用一组绑定 | 写在绑定文件里的 `"option"` 字段；开关状态存在 `config/fastformer/options.json` | 客户端 |

为什么这样划分：
- 会话变体决定**服务端允许做什么**，这是规则，所以放数据包，并由服务端强制执行。
- 绑定和选项是**个人按键习惯**，所以放客户端，可以被资源包和用户配置覆盖。

### 9.2 数据格式

**会话变体：`data/fastformer/fastformer/session_profile/hold_line.json`**

```json
{
  "session": "fastformer:quick_shape",
  "max_stage": "line",
  "locked_modes": { "line": "fastformer:axis" },
  "allow_mode_switch": false,
  "middle_confirm": "always",
  "entry": { "anchor": "embedded", "requires": ["creative", "holding_placeable", "aiming_block", "within_reach"] }
}
```

**交互绑定：`assets/fastformer/fastformer/bindings/hold_line.json`**

```json
{
  "option": "fastformer:hold_line",
  "option_default": false,
  "bindings": [
    { "id": "fastformer:hold_line/start",
      "context": { "session": "fastformer:idle", "items": "#fastformer:placeable", "screen": false,
                   "aiming": "block", "range": "near", "!modifiers": ["alt"],
                   "!aiming_tag": "#fastformer:hold_line_passthrough", "!tinker_consumes": true, "!double_second_press": true },
      "input":   { "button": "right", "gesture": "hold", "hold_start": "deferred" },
      "action":  { "id": "fastformer:quick_shape/start", "profile": "fastformer:hold_line" } },
    { "id": "fastformer:hold_line/release",
      "context": { "session": "fastformer:quick_shape", "profile": "fastformer:hold_line" },
      "input":   { "button": "right", "gesture": "release" },
      "action":  { "id": "fastformer:quick_shape/middle_confirm" } },
    { "id": "fastformer:hold_line/abort",
      "context": { "session": "fastformer:quick_shape", "profile": "fastformer:hold_line" },
      "input":   { "button": "left", "gesture": "press" },
      "action":  { "id": "fastformer:session/cancel" } }
  ]
}
```

**操作树写法**：绑定文件除了扁平的 `bindings` 列表，还支持按树嵌套。这是默认数据的主要写法，结构与伪代码文档对齐。加载时把树展开成扁平绑定，上层节点的 context 自动合并进子节点。

```json
{ "tree": {
  "session:fastformer:quick_shape": {
    "stage:line": {
      "mode:fastformer:axis": {
        "right/press":  { "action": "fastformer:quick_shape/point" },
        "middle/press": { "action": "fastformer:quick_shape/middle_confirm" },
        "left/press":   { "action": "fastformer:quick_shape/undo_point" }
      }
    },
    "stage:face": {
      "mode:fastformer:polygon": {
        "right/press": [
          { "if": { "near_first_point": true }, "action": "fastformer:quick_shape/close" },
          { "action": "fastformer:quick_shape/point" } ],
        "middle/press": { "action": "fastformer:block" }
      }
    }
  } } }
```

- 节点键的写法是 `类型:值`，类型可以是 `session`、`stage`、`mode`、`profile`；叶子键的写法是 `按钮/手势`。
- 叶子可以写成数组，按顺序取第一条匹配的，用 `if` 写附加条件。这对应伪代码里 `右键 [靠近首点]` 这样的分支写法。

**格式要点**
- **context 匹配**：按字段做 AND，可用字段有 `session`、`stage`、`mode`、`profile`、`items`（物品或标签）、`modifiers`、`sneaking`、`screen`、`aiming`。
- **否定**：任意字段前加 `"!"` 前缀表示否定。
- **多个绑定同时匹配**：先按 `priority` 排序，`priority` 相同则**具体性**（context 中的字段数）更高者优先。这条规则与 CSS 选择器、mcf 谓词的心智模型一致。

**动作 action**
- `id` 指向一个已注册的原子操作；其余字段是传给该操作的参数，各操作自带 Codec 校验。
- 特殊动作 `fastformer:pass`：让给原版，或让给下一条匹配的绑定。
- 特殊动作 `fastformer:block`：吞掉这次输入，什么都不做。

**输入 input**
- `gesture` 可选：`press`、`release`、`hold`、`double`、`scroll_up`、`scroll_down`、`key`。
- `hold` 和 `double` 带时间参数。
- `hold_start` 可选：
  - `after_vanilla_press`：首按照常交给原版，到原版重复使用的时刻再接管。
  - `deferred`：首按先压住；在阈值前松开时补发一次原版使用，到阈值仍按住就执行动作。长按画线使用这一种，原因见 9.5。

### 9.3 代码结构

```
common/interaction/binding/          双端共享的数据模型与 Codec（不依赖客户端）
  SessionProfile                     record + CODEC；字段对应 9.2，空值表示不约束
  SessionProfiles                    数据包注册表（同步），以及 NONE 常量
  ActionId / ActionSpec              动作 id 加参数（Dynamic）
server/interaction/profile/
  ProfileConstraints                 服务端强制执行：effectiveStage、effectiveMode、allowModeSwitch、middleConfirm
  ProfileEntryRules                  校验进入某个变体的前提（creative、holding_placeable、within_reach……）
client/interaction/binding/
  BindingSystem                      唯一入口：onButton / onScroll / onKey / tick / reset
  context/  BindingContext（每次输入冻结一份快照）, ContextMatcher, ContextFields（字段注册表，可扩展）
  input/    InputPattern, GestureRecognizer（press/release/hold/double/scroll）, ButtonArbiter
  action/   BindingAction（接口）, BindingActions（Registry<ResourceLocation, Factory>）, 内置动作类
  data/     BindingFile + CODEC, BindingLoader（资源重载监听）, UserOverrides（config/fastformer/bindings.json）
  option/   BindingOption, OptionStore（options.json）, OptionSettingsSection（自动生成设置项）
```

### 9.4 与现有操作逻辑的统一方式（分两步，不重写现有逻辑）

**第一步：本次实施**
1. **原子操作注册**：把现有代码路径包装成 `BindingAction`，**只做包装，不改原有实现**。

   | 动作 id | 包装的现有路径 |
   |---|---|
   | `fastformer:quick_shape/start` | `QuickShapeStartController`，增加 profile 参数 |
   | `fastformer:quick_shape/point` | `QuickShapeMouseInputController.dispatch` 中的 POINT 分支 |
   | `fastformer:quick_shape/middle_confirm` | 同上的 MIDDLE 分支 |
   | `fastformer:quick_shape/undo_point` | `QuickShapeUndoGesture` |
   | `fastformer:session/cancel` | 现有 Q 键取消 |
   | `fastformer:mode/cycle` | 现有短按修饰键切换模式 |
   | `fastformer:pass` / `fastformer:block` | 见 9.2 |

2. **接入点**：在 `FastPlaceClientInput` 的 `onMouseButton`、`onMouseScroll`、`handleKey` 入口处，**先调用** `BindingSystem`。
   - 只有当某条**数据绑定**匹配、并且返回“已处理”时，才取消事件并返回。
   - 没有匹配时，原有的硬编码路径照常执行。
   - 所以默认数据中只包含新功能的绑定时，现有行为完全不变。
3. **会话变体生效**：服务端在 `QuickShapeDraft` 上挂一个 `SessionProfile`（不可变，默认为 `NONE`）。所有决定阶段、模式和中键确认的地方，都改为经过 `ProfileConstraints` 读取。具体位置见 9.6。

**第二步：迁移整棵现有操作树（属于本节范围，第一步验收后紧接着做）**

把 `docs/principles/pseudocode/sessions/*.md` 里现有的硬编码按键规则**逐棵子树迁成默认数据**，写在 `assets/fastformer/fastformer/bindings/default/<会话>.json`。每迁完一棵子树，就删掉对应的硬编码分支。

迁移顺序从独立性高、风险低的开始：
1. 快速替换、智能填充
2. 快速起形：线阶段 → 点阶段 → 面阶段 → 体阶段
3. 几何会话
4. 选区：CUBOID → PRISM → 智能
5. 特殊物品
6. 空闲：最后迁移。双击入口、扳手、Alt 入口、远距离入口在这里交织，需要用到 `double` 手势和 `if` 条件的组合

迁移过程中的约束：
- **迁移期间允许新旧并存**：已迁移的子树走数据路径，未迁移的子树仍走硬编码路径。
- **以数据为准**：`BindingSystem` 在某个上下文中匹配到数据绑定时，就不再进入该上下文的硬编码路径。
- **每棵子树的数据要能完整覆盖原有分支**，包括“无操作”。“无操作”显式写成 `fastformer:block`，否则输入会回落到原版行为。

- **验收方式**：做一份“默认绑定表复现现有行为”的对照测试，即同一输入序列分别在旧路径和数据路径下执行，结果必须一致。
- **最终状态**：所有交互规则都是数据，伪代码文档与默认数据一一对应，用户可以重绑任何操作。

### 9.5 “长按画线”的输入时序（替代原版连续放置）

起点要**嵌入命中方块**（见 9.6），所以原版不能先在命中面外侧放下一块，否则会多出一块孤立的方块。因此长按画线使用 `hold_start = deferred`，精确定义如下。

1. **选项关闭**：没有绑定匹配，与现状完全一致。
2. **选项开启，且上下文匹配**（空闲、创造模式、手持可放置物品、准星命中方块、该方块不在 `#fastformer:hold_line_passthrough` 标签内、没有打开界面）：
   1. **首次按下**：
      - 取消事件，调用 `keyUse.setDown(false)`，原版这一次不放置；
      - 记录本次的 `BindingContext`，按下时的命中结果 hit、eye、view 都在这一刻冻结；
      - 清空 `session.idleEntry`。
   2. **到阈值仍按住**：执行 `quick_shape/start(profile=hold_line)`。
      - 阈值默认等于原版重复使用的间隔（4 tick，200ms），用事件时间戳判断。
      - 这一刻正是原版本该开始连续放置的时刻，所以从用户的角度看，是“长按”取代了“连续放置”。
   3. **在阈值前松开**：通过已有的输入合成路径**补发一次原版右键使用**，使用按下时冻结的命中结果，结果与原版单击一致。补发的输入必须标记为“已仲裁”，不能再次进入 `BindingSystem`，也不能被空闲双击入口当成第一次按下。
   4. **代价**：选项开启后，命中方块时的普通单击会延迟到松开时才放置，延迟约等于按键时长，通常不超过 200ms。这是起点嵌入带来的必然取舍；选项关闭时没有任何延迟。
   5. **不拦截的情况**，按下立即交给原版，零延迟：
      - 准星没有命中方块，即对着空气；
      - 命中的方块在 `#fastformer:hold_line_passthrough` 标签内。默认标签包括容器、门、活板门、栅栏门、按钮、拉杆、床、工作台类等可交互方块；作者和数据包可以增删。
      - 扳手会消费这次点击（`ClientBlockTinker.consumes`）。
3. **接管之后的输入**，都由带 `profile` 条件的绑定处理：
   - 松开右键：执行 `middle_confirm`；
   - 左键：取消；
   - 其余输入沿用快速起形线阶段的现有规则，但要经过会话变体的约束过滤，见 9.6。
4. **以下情况取消且不写入**：打开界面、窗口失焦、切换世界或维度、主手物品变化、等待服务端回执超时。**失焦不算松开。**

### 9.5.1 与现有空闲入口判定的共存规则（必须逐条满足）

现有的空闲入口有大量距离分档与放宽判定，规则见 `interaction_rules.md` 的“输入归属”一节和 `sessions/01-空闲.md`。长按画线**只能插在它们的缝隙里，不得改变其中任何一条**。仲裁顺序如下，按从上到下的顺序判定，命中即停：

| 顺序 | 现有规则 | 长按画线的处理 |
|---|---|---|
| 1 | 其他模组已经取消了事件 | 不介入（沿用“其他模组先取消事件时 FastFormer 放弃抢占”） |
| 2 | 活动会话存在 | 不介入（hold_line 的 context 是 `session: idle`） |
| 3 | 入口后 200ms 惯性保护（`entryPressGuard`）正在吞掉重复右键 | 不介入，先让 `entryPressGuard.capture` 处理。也就是说，`BindingSystem` 的空闲绑定排在 `entryPressGuard` **之后**，排在其余入口判定**之前** |
| 4 | **Alt+右键嵌入入口** / Alt+中键 | 不介入。context 加 `"!modifiers": ["alt"]` |
| 5 | **远距离单击入口**：命中超出 `block_interaction_range`，右键直接以表面入口进入，并启动 200ms 保护 | 不介入。context 加 `"range": "near"`。远距离时原版本来就无法连续放置，没有可替代的东西 |
| 6 | **近距离双击的第二击**：`idleEntry.secondPress(...)` 命中。容差随距离分档：不足 15 格为 200ms 且精确命中；15 格及以上为 300ms，带约 0.5° 的边缘余量，最多 1/2 格；另有“视线变化不超过 2° 或命中点间距不超过 1/2 格”的放宽 | 不介入，双击入口优先。也就是说，**按下时先询问双击识别器这是否是有效的第二击**，是的话交给原有路径。只有不是第二击时，才进入 hold 待定 |
| 7 | 扳手（`ClientBlockTinker.consumes`）的第一击立即生效 | 不介入（context `"!tinker_consumes": true`）。手持方块时一般不触发扳手，但以判定结果为准 |
| 8 | 命中实体、命中空气、命中 `#fastformer:hold_line_passthrough` 内的方块 | 不介入 |
| 9 | 其余情况：近距离、无 Alt、手持可放置物品、命中普通方块，并且不是双击的第二击 | **进入 hold 待定**（9.5） |

**待定之后的补发，必须保留双击的第一击语义**

现在的近距离第一击做三件事：
- 冻结原版黑框的命中方块、面和位置，以及同一插值时刻的 eye、view；
- 冻结“是否嵌入 / 是否穿透流体”这两条规则；
- 发送 `IdleInteractionCapturePayload` 快照，然后交给原版放置。服务端“已产生放置时首点锚定首次放置的位置与状态”依赖这个顺序。

阈值前松开时的补发要做到以下几点：

1. **冻结发生在按下时刻**：命中结果、eye、view、流体规则都在**按下时**冻结，与现在第一击的冻结时刻相同；补发时不重新取样。
2. **补发等同于重新注入那次按下**：把冻结的按下事件，以**原始时间戳** `occurredAtNanos`，重新送入原有的 `IdleSessionEntryController.capture → 原版使用` 路径，并跳过 `BindingSystem`。这样快照发送与原版放置的先后顺序和现在一致，服务端的首次放置锚定逻辑不需要任何改动。
3. **双击窗口从原始按下时间计算**：第二击判定沿用原来的时间戳语义，不因为补发延迟而放宽或收紧。补发在松开时才发生，此时已经过去了按键时长，所以双击窗口会**实际变短**（200ms 减去第一击的按键时长）。这是需要实测的点：
   - 如果测出来双击成功率明显下降，**只能**把窗口改为“从补发时刻起算”，也就是给 `EntryDoubleClickTolerance` 传入补发时刻；
   - **不得**改动距离分档、角度余量或 1/2 格余量；
   - 无论最后选哪种，都要在进度文档中写明结论。
4. **流体规则**：hold_line 的嵌入起点使用的命中结果，与第 1 点冻结的黑框命中**是同一个**。原版黑框会忽略流体，含水方块只记录实体形状；玩家在水中时自动穿透流体的规则，同样在按下时冻结，并随入口请求发送给服务端。不得在阈值时刻重新做射线检测。

**进入会话时，不能触发入口保护**

- hold_line 的入口**不能**调用 `protectEntry` / `entryPressGuard.arm`（`IdleSessionEntryController.java:111`）。
- 原因：`entryPressGuard` 会把被忽略的按下与随后的松开配对吞掉。一旦被它吞掉，这次长按的**松开就无法触发确认**。
- hold_line 不需要惯性保护：它的按下已经被长按识别占用，会话期间的右键按下和松开都由 profile 绑定处理。

**会话结束之后**

- 松开确认、取消或写入完成后，回到空闲，清空 `idleEntry`。下一次右键是全新的第一击，不与本次长按配对成双击。
- 结束时不启动 200ms 保护。如果实测发现松开之后立刻右键会误触发长按待定，就改为在 hold_line 的 context 上加冷却条件，例如 `"cooldown_ms": 150`。不得复用 `entryPressGuard`。

**这些放宽规则本身，也是要数据化的操作树的一部分**（9.4 第二步迁移“空闲”时处理）

迁移时，它们成为手势识别器和上下文字段的**参数**，不允许再写成散落在各处的硬编码：
- `double` 手势：`window_ms` 和 `tolerance` 都按距离分档，例如 `{"below": 15, "window_ms": 200, "exact": true}` 和 `{"from": 15, "window_ms": 300, "edge_deg": 0.5, "edge_max_blocks": 0.5}`；另有 `accept_if_any: [{"view_change_deg": 2}, {"hit_distance_blocks": 0.5}]`。
- 入口保护：动作参数 `guard_ms: 200`，同时标注“与松开配对吞掉、重复点击不延长窗口”。
- 上下文字段：`range: near|far`（读取 `block_interaction_range`）、`in_fluid`、`through_fluids`、`modifiers`。
- 迁移的验收：现有 `IdleEntryGameTests` 全部通过，并补充以下边界用例：15 格处的 300ms 窗口与边缘余量、远距离单击加保护、Alt 入口加保护、扳手双击还原、水中穿透流体。

### 9.6 服务端会话变体约束（必须在服务端强制）

**起点**
- point0 **嵌入命中方块**，即准星命中的那个方块本身的位置，不是命中面外侧那一格。语义与现有 Alt+右键的嵌入入口一致（`interaction_rules.md:8`）。
- 实现时必须复用现有嵌入入口的路径：`IdleSessionEntryController.enter(..., embedded=true, ...)`，以及 `SessionEntryRaycast` 的嵌入规则。不得另写一套嵌入判定。命中点使用按下时冻结的 hit、eye、view，不在阈值时刻重新做射线检测。
- 服务端根据入口请求携带的 hit、eye、view 重新校验：命中方块必须存在，且在可达范围内。不满足时拒绝入口，客户端什么都不做；这时不补发原版使用，因为用户的意图是长按。
- 嵌入起点意味着这条线**会写入并覆盖命中方块本身**。覆盖规则沿用现有嵌入入口的写入语义。

**`max_stage = line`**
- 凡是会把阶段推进到 FACE 的操作，一律改为“确认并提交”，或者直接忽略。
- 会话**永远不会进入面阶段**。

**`locked_modes.line = axis`**
- 现在线模式读取的是玩家设置 `settings.lineMode()`，见 `FastPlaceManager.java:149、434、472、522、566`。
- 必须统一改为 `ProfileConstraints.effectiveLineMode(session, settings)`：
  ```java
  session.profile().lockedLineMode().orElse(settings.lineMode())
  ```
- **不得改写玩家自己的线模式设置。**

**`allow_mode_switch = false`**
- 以下入口对这个会话一律直接返回：
  - `cycleStageMode` / `setStageMode`（:359、363、383）；
  - 径向菜单；
  - 短按修饰键切换模式；
  - `/ff ... line_mode`。
- 滚轮只要会切换模式，就忽略，不能借滚轮改成 `FREE_SCROLL` 或 `RAYCAST`。
- HUD 只显示“横平竖直”。

**`middle_confirm = always`**
- `ServerInputDispatcher.java:670` 处理 MIDDLE 时会检查 `middleConfirmEnabled()`，对这个变体要**跳过**这项检查。
- 处理 MIDDLE 的结果是：确认第二点，立即写入世界，结束会话。
- 候选点与 point0 重合时，按现有“重合即提交已确认部分”的规则结束，不产生新的写入，也不产生空历史。

**入口与网络**
- `StartPlacementPayload.Target` 增加 `ResourceLocation profile`，默认为 `fastformer:none`。
- 服务端查找注册表中的变体，并用 `ProfileEntryRules` 校验前提，不满足就拒绝。
- 客户端**不能**通过发送任意 profile 绕过约束：变体内容只来自服务端注册表，客户端只发送 id。

**历史**
- 原版这次没有放置，整条线（包括被覆盖的命中方块）只有**一条**快速起形历史，一次撤销就能完整还原。

### 9.7 设置界面

- `OptionSettingsSection` 读取所有已加载的 `BindingOption`，为每个选项自动生成一行开关。
  - 名称的翻译键为 `fastformer.option.<path>`，例如 `fastformer.option.hold_line` 对应“长按右键画线”。
  - 默认值取自 `option_default`。
- 用户在 `config/fastformer/bindings.json` 中可以做三件事：
  - 按 `id` 覆盖任意绑定的 `input`、`priority`、`enabled`；
  - 新增自己的绑定；
  - 引用任何已注册的动作和会话变体。
- 加载失败时的处理：
  - 未知的动作 id、上下文字段、变体 id：跳过该条，记录警告，并在设置界面标红，不让整个文件失效。
  - 文件损坏：备份为 `.bak-<时间>`，然后回退到默认值。
- 写入走 `ClientStorageQueue`，原子写入。

### 9.8 扩展点（现在定义接口，不实现具体项）

| 未来需求 | 如何扩展 |
|---|---|
| 其他“长按 + 受约束会话”，例如长按中键画面、只允许体阶段的快速填充 | 只需新增一份 `session_profile` 和一份 bindings 数据，**零代码** |
| 新的上下文条件，例如手持标签、某个 Mode、某个维度 | 在 `ContextFields` 中注册一个字段解析器 |
| 新的输入手势，例如三击、长按拖动方向、和弦键 | 新增一个 `GestureRecognizer` |
| 新的原子操作，例如撤销、切换主题、冲刺、打开径向菜单 | 实现一个 `BindingAction` 并注册；`IntentBus` 落地后，动作只构造 Intent |
| 资源包或附加模组提供整套键位方案 | 资源包覆盖 `bindings/*.json`；模组注册新的动作和字段 |
| 绑定冲突检查 | 加载后检测“同一输入、上下文可同时成立、priority 和具体性都相同”的组合，在设置界面标红 |
| 服务器禁止某些变体 | 数据包删除或覆盖对应的 `session_profile` 即可，客户端绑定会因 id 不存在而自动失效 |

### 9.9 测试与验收

**单元测试**
- `ContextMatcher`：AND、否定、具体性排序。
- `BindingFile` 和 `SessionProfile` 的 Codec 往返。
- 用户覆盖与默认值的合并、未知 id 跳过、文件损坏回退。
- hold 手势的识别器：`deferred` 的阈值判定；阈值前松开时补发一次且只补发一次；补发的输入不会再次进入仲裁，也不会触发双击入口；命中空气或命中标签内方块时零介入。
- 没有匹配时零介入。
- `ProfileConstraints`：
  - 锁定线模式后不改写玩家设置；
  - `max_stage=line` 不会进入 FACE；
  - `middle_confirm=always` 绕过中键确认开关；
  - `allow_mode_switch=false` 拒绝所有切换模式的入口。

**GameTest**（新建 `HoldLineGameTests`）
1. 开启选项后长按右键，沿轴移动视线再松开：写入一条横平竖直的线，然后回到空闲。
2. 同上，但不移动就松开：只写入嵌入的那一格，即命中方块本身，产生一条历史，撤销后完全还原。
2b. 起点验证：线从命中方块本身开始，而不是从命中面外侧那一格开始，命中面外侧不会多出一块。
3. 开启选项后短按右键：松开时补发，结果与原版单击一致，只放一个方块，位置在命中面外侧。
4. 关闭选项后长按右键：原版连续放置不变。
5. 会话中滚轮、命令、径向菜单都无法把线模式改成 `FREE_SCROLL` 或 `RAYCAST`；玩家自己的线模式设置不变。
6. 会话中永远不会出现面阶段。
7. 会话中 Q、左键、打开界面、失焦：取消，不写入。
8. 中键确认设置关闭时，松开右键仍然确认。
9. 对着箱子、门等标签内方块右键：立即打开或切换，没有延迟，也不接管。对着空气右键：与原版一致。
10. 客户端伪造未知的 profile id，服务端拒绝。
10b. 共存用例：
    - 近距离快速双击时仍然进入原有的双击入口（第二击不进入长按待定）；
    - 15～20 格范围内双击时，300ms 窗口和边缘余量依然有效；
    - 超出交互范围时单击右键，立即走原有的远距离入口，不出现长按延迟；
    - Alt+右键与 Alt+中键行为不变；
    - 长按画线的松开不会被 `entryPressGuard` 吞掉；
    - 水中长按时，起点使用按下时冻结的流体规则；
    - 长按会话结束后，立即单击右键不会与之前的长按配对成双击。
11. 现有 `IdleEntryGameTests` 和 `QuickReplaceGameTests` 全部通过。

**进度记录**：在 `2026-11-13-architecture-refactor-progress.md` 中新增“数据化交互绑定 / 长按画线”一节，写明完成项和验证结果。

---

## 10. 面光栅化调度器（FaceRasterDispatcher）实现规格

> 2026-11-14 实验后决定：**暂时保留面模式与多边面两种入口各自的原有形状**。10.7 第 3、4 步暂不执行；默认策略不得暗中统一两条路由。分类、注册、同步和快速算法只能在保留该入口结果的前提下接入。`TranslatedScan` 继续保留。对比证据见 `progress/face-raster-comparison.md`。

> 需求（2026-11-14，用户确认）：面填充算法要拆开、可选择，并通过调度器分派。
> - **平行四边形**：调度器先按朝向剪枝。简单情况各用一套专用的快速算法，只有真正倾斜的面才进入复杂算法。
> - **多边形**（顶点数不等于 4，或者四边形但不是平行四边形）：直接使用 `ProjectedBresenhamFace`。
> - **可选择**：服务器或作者可以为每一类面指定使用哪个算法，以及失败时依次退到哪个算法。
>
> 本节取代 6.3 中“删除 `TranslatedScanFaceRasterizer`”的提议。它先作为可选算法保留，是否删除由 10.6 的对比实验决定。

### 10.1 现状（2026-11-14 实测调用链）

| 入口 | 路径 |
|---|---|
| 面模式（坐标平面、基准面）、斜盒体 | `QuadFaceGenerator` / `TiltedBoxGenerator` → `PlanarFaceRasterizer.generateInterpolatedQuad` → `QuadFaceRasterizer`（法平面高度场，218 行）→ 失败时 `lastResortOwnedRaster` |
| 多边面、棱柱底面、棱锥截面 | `PolygonFaceGenerator` → `PlanarFaceRasterizer.generate` → 4 个顶点时 `BresenhamFaceSweep.attempt` → `TranslatedScanFaceRasterizer`（2463 行，外加 `FlowGraph` / `CountingUnionFind` / `ExteriorUnionFind`）；不是 4 个顶点时 `ProjectedBresenhamFace.raster` |
| 兜底 | `emergencyFallback` → `TranslatedScanFaceRasterizer.emergencyFallback` → `lastResortOwnedRaster` |
| 轮廓（OUTLINE） | 平行四边形用四条 `LineGenerator.path`；其他形状用 `ProjectedBresenhamFace.outline` |

存在三个问题：
- 分派散落在 `PlanarFaceRasterizer` 的 if 链里，并且以“前一个算法失败了”作为路由依据。
- 同一个平行四边形，从面模式进来和从多边面进来，走的是两套不同的算法，**同形状不同结果**。
- 横平竖直这样的简单面也要跑完整的高度场或最小割计算。

### 10.2 目标结构

```
fastplace/geometry/generation/face/        （迁移到 common/geometry/raster/face/ 时整体移动）
  FaceRasterDispatcher      唯一入口：rasterize(FaceRequest) → FaceRasterResult
  FaceRequest               record(List<Vec3> vertices, FillMode fill, LineTieBias tieBias, int maxBlocks, BlockGenerationObserver observer)
  FaceRasterResult          sealed：Filled(Set<BlockPos>) | LimitExceeded | NotApplicable | NoValidCandidate
  classify/
    FaceClass               enum，见 10.3
    FaceShape               record：类别、取整角点、法向轴、平行轴等分类事实
    FaceClassifier          纯函数，只用整数运算：顶点 → FaceShape
  algorithm/
    FaceRasterAlgorithm     接口：ResourceLocation id(); boolean accepts(FaceShape); FaceRasterResult rasterize(FaceShape, FaceRequest)
    FaceRasterAlgorithms    Registry<ResourceLocation, FaceRasterAlgorithm>
    AxisRectangleRaster     fastformer:axis_rectangle
    CoordinatePlaneRaster   fastformer:coordinate_plane
    AxisExtrusionRaster     fastformer:axis_extrusion
    NormalPlaneRaster       fastformer:normal_plane（原 QuadFaceRasterizer + NormalPlaneFaceRasterizer）
    scan/TranslatedScan*    fastformer:translated_scan（原 TranslatedScanFaceRasterizer，拆成 4～5 个类）
    ProjectedPolygonRaster  fastformer:projected_polygon（原 ProjectedBresenhamFace）
    EdgeOnlyRaster          fastformer:edge_only（原 lastResortOwnedRaster，保证一定有结果）
  policy/
    FaceRasterPolicy        每个 FaceClass 对应一条算法链（List<ResourceLocation>）
    FaceRasterPolicies      默认策略，以及按数据加载的策略（10.5）
```

完成后，`PlanarFaceRasterizer`、`BresenhamFaceSweep`、`QuadFaceRasterizer` 里的分派代码全部删除。以下入口统一改为调用调度器：
- `PolygonFaceGenerator`
- `QuadFaceGenerator`
- `TiltedBoxGenerator`
- `PrismGenerator`
- `PyramidGenerator`
- `BresenhamColumnVolume` 的面部分

### 10.3 分类（剪枝）规则

`FaceClassifier` 先把顶点取整成方块角点，再从上到下判断，命中第一条即停：

| FaceClass | 判定（只用整数运算） | 默认算法链 | 说明 |
|---|---|---|---|
| `AXIS_RECTANGLE` | 是平行四边形，且两条边向量各自只有一个非零分量，分别落在不同的轴上 | `axis_rectangle` | 横平竖直的矩形。两层循环直接填，复杂度 O(面积)，不需要任何搜索 |
| `COORDINATE_PLANE` | 是平行四边形，所有角点有一个坐标分量相同（面躺在 x、y 或 z 等于常数的平面上），但边是斜的 | `coordinate_plane` → `normal_plane` | 退化为 2D 问题：在该平面上用 2D Bresenham 画四条边，再按行扫描填充。四条边与 `LineGenerator.path` 一格不差 |
| `AXIS_EXTRUSION` | 是平行四边形，其中一条边向量只有一个非零分量（面内有一个方向与坐标轴平行），但面不在坐标平面上 | `axis_extrusion` → `normal_plane` | 把另一条斜边沿这个坐标轴平移：`path(c0,c1) × [0..L]`。天然没有洞，也没有多余厚度 |
| `TILTED_PARALLELOGRAM` | 其余的平行四边形 | `normal_plane` → `translated_scan` → `edge_only` | 真正的空间倾斜面，使用复杂算法 |
| `POLYGON` | 顶点数不为 4，或者是 4 个顶点但不是平行四边形 | `projected_polygon` → `edge_only` | 按需求直接使用 `ProjectedBresenhamFace` |
| `DEGENERATE` | 角点共线、重合，或面积为零 | `edge_only` | 只画边 |

几条约束：
- 平行四边形判定统一使用 `common.math.BlockQuads.isParallelogram`（基于取整后的角点），不得另写一份。
- 分类只在调度器入口执行一次，各算法内部不再重复判断朝向。
- 新增类别时，在 `FaceClass` 加值，并在 `FaceClassifier` 中按顺序插入判断。顺序就是剪枝优先级：越简单的类别越靠前。

### 10.4 调度与失败处理

```
shape = FaceClassifier.classify(request)
for id in policy.chain(shape.kind()):
    algo = FaceRasterAlgorithms.get(id)
    if !algo.accepts(shape): continue
    r = algo.rasterize(shape, request)
    Filled           -> return r
    LimitExceeded    -> return r          # 超出上限立即返回，不再尝试更贵的算法
    NotApplicable    -> continue
    NoValidCandidate -> continue          # 记 debug 日志：哪个算法、哪一类面
return EdgeOnlyRaster.rasterize(shape, request)   # 永远有结果
```

**轮廓（OUTLINE）**：
- 所有平行四边形类别统一画四条 `LineGenerator.path`，即现有 `translatedQuadOutline` 的逻辑，与选用哪个填充算法无关。
- `POLYGON` 使用 `projected_polygon` 的轮廓。
- 轮廓不走填充算法链。

**取消与上限**：每个算法内部都要调用 `observer.checkCancelled()`，并在超过 `maxBlocks` 时提前返回 `LimitExceeded`，与现有约定一致。

### 10.5 “可选”的范围与同步（必须保证预览与写入一致）

**选择权在服务端。** 预览显示的方块必须与实际写入世界的方块一致，所以策略属于服务器规则：
- 默认规则写在 `FaceRasterPolicySet.DEFAULT` 中，分别保留 `CURRENT_QUAD`、`CURRENT_POLYGON` 和 `CURRENT_VOLUME_FACE`；实验策略命名为 `CLASSIFIED`。
- 数据包可以用 `data/fastformer/fastformer/face_raster_policy/default.json` 共享覆盖，也可以用同目录下的 `quad.json`、`polygon.json`、`volume.json` 分别覆盖。采用完整六类链，格式如下：
  ```json
  { "chains": {
      "axis_rectangle":       ["fastformer:axis_rectangle"],
      "coordinate_plane":     ["fastformer:coordinate_plane", "fastformer:normal_plane"],
      "axis_extrusion":       ["fastformer:axis_extrusion", "fastformer:normal_plane"],
      "tilted_parallelogram": ["fastformer:normal_plane", "fastformer:translated_scan"],
      "polygon":              ["fastformer:projected_polygon"],
      "degenerate":           ["fastformer:edge_only"] } }
  ```

**同步到客户端**：
- 策略随数据包注册表同步到客户端，写法与第 9 节的 `SessionProfile` 相同。
- 此类 NeoForge 数据包注册表在世界启动时加载；修改后重启服务端／重新打开世界，不承诺 `/reload` 热更新。实现与验证见[面策略加载与同步](progress/face-raster-policy-data.md)。
- 客户端预览使用同一份策略。
- 策略的哈希写入预览快照的参数。客户端与服务端的哈希不一致时，以服务端为准，并让客户端预览失效。

**加载校验**：
- 未注册的算法 id：加载时报错，回退到默认链。
- 任何一条链都不能为空。

**调试覆盖（可选，第一版不做）**：可以预留一个玩家级覆盖，例如 `/ff debug face_raster <class> <algo>`。只允许有权限的 OP 使用，并且只影响该玩家自己。

`FaceRasterizationMode` 枚举及其残留（如果还有）在这一步彻底删除，由策略取代。

### 10.6 算法契约与对比实验（删除 translated_scan 之前必须完成）

**契约测试**：参数化测试，每个算法都要跑一遍：
1. **边界一致**：平行四边形类别的结果，必须包含四条 `LineGenerator.path(角点 i, 角点 i+1, tieBias)` 的全部方块。
2. **无洞**：在面的投影平面上连通，且没有被包围的空格。连通标准以现有测试为准。
3. **无多余厚度**：沿面的法向，每一列最多 1 格。断言方式与现有 `hasEnclosedAir`、`nonZero` 系列一致。
4. **确定性**：输入相同时，输出的方块集合和遍历顺序都相同。
5. **上限与取消**：超过 `maxBlocks` 时返回 `LimitExceeded`；被取消时抛出取消异常。
6. **专用算法与通用算法等价**：`axis_rectangle`、`coordinate_plane`、`axis_extrusion` 在各自负责的类别上，结果必须与 `normal_plane` **完全相同**。做不到时，列出差异，由用户决定以哪一个为准。这一条保证剪枝只是加速，不会改变形状。

**对比实验**（只读，写成测试或放在 `tools/analysis` 的脚本）：
- **样本**：随机生成至少 5000 个四边形，平行与不平行都要有，倾斜角度各异，边长 1～128。
- **两组对比**：
  - `TILTED_PARALLELOGRAM` 类别：`normal_plane` 对比 `translated_scan`；
  - 4 个顶点但不是平行四边形的情况：`projected_polygon` 对比当前的 `translated_scan`。
- **统计项**：结果完全相同的比例、边界不一致的格数、出现洞或多余厚度的次数、P50 和 P99 耗时。
- **输出**：写入 `docs/reviews/progress/face-raster-comparison.md`，附 3～5 个典型差异案例的方块坐标，或者截图。
- **结论由用户决定**：差异可以接受，就删除 `translated_scan` 和它的三个辅助类；否则保留它，作为 `tilted_parallelogram` 链上的可选算法，并拆分成子包。

### 10.7 实施顺序

1. **骨架，不改行为**：实现 `FaceClassifier`，以及能完整复现现有路由的调度器和默认策略。例如 `polygon` 类别中 4 个顶点的情况，先继续指向 `translated_scan`。现有全部几何测试和 GameTest 必须原样通过。
2. **专用算法**：实现 `axis_rectangle`、`coordinate_plane`、`axis_extrusion`。用 10.6 第 6 条的等价测试验证通过后，把它们放到对应类别算法链的最前面。
3. **统一同形状两种结果**：平行四边形无论从哪个入口进来（多边面或面模式），都走同一个分类和同一条算法链。这一步**会改变**多边面模式下平行四边形的结果。改之前先用对比实验量化差异，写进进度文档，并告知用户。
4. **切换多边形**：把“4 个顶点、不是平行四边形”的情况切到 `projected_polygon`。这一步同样会改变结果，处理方式同第 3 步。
5. **对比实验**：按 10.6 完成，由用户决定 `translated_scan` 的去留。
6. **收尾**：接入数据策略与同步（10.5），删除旧的分派代码，以及 `FaceRasterizationMode` 的残留。

### 10.8 预留扩展

| 未来需求 | 做法 |
|---|---|
| 新的面类别，例如 45° 斜面、正六边形 | `FaceClass` 加值，`FaceClassifier` 插入判断，补上默认算法链 |
| 新的算法，例如更厚的面、抗锯齿 | 实现 `FaceRasterAlgorithm` 并注册，在策略里引用 |
| 体积生成也走同一套 | `BresenhamColumnVolume`、`TiltedBoxGenerator` 的体积部分以后按同样模式建 `VolumeRasterDispatcher`；本次只约定接口名，不实现 |
| 玩家级的算法偏好 | 由 10.5 的调试覆盖扩展而来，前提是有权限检查 |
