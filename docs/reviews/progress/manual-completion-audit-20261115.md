# 整份重构手册的完成度审计（2026-11-15）

依据：[重构手册](../2026-11-13-architecture-refactor-manual.md)。本次为静态审计，未运行 Gradle、GameTest 或真实客户端，未修改生产代码和架构基线。源码在多人迁移中，路径和行号是本次读取时的位置，最终验收必须重新定位。

**整份手册尚未完成。** 当前键盘绑定、服务器几何和世界写入包迁移不能替代剩余操作树、包环治理和客户端实测。

证据等级：**静态确认**表示已读取实现或全范围搜索；**已有验证记录**表示阶段文档记载过通过，本次未重跑；**未验证**表示尚无本次执行结果。旧验证不证明当前未提交代码通过。

Java 路径默认从 src/main/java/io/github/fastformer/ 起算，测试路径默认从 src/test/java/io/github/fastformer/ 起算。

## 优先执行顺序

| 顺序 | 完整能力 | 当前证据与完成条件 |
|---|---|---|
| 1 | 集成取消／历史冲突绑定及并发迁移 | 父代理正在执行。统一 import 后编译、定向行为测试和架构检查；不接受新增基线来掩盖包环。 |
| 2 | 几何鼠标子树 | GeometryInputController 的 queueInteraction、queuePath、queueGizmo 仍按物理按钮决定撤回、清选、加点、闭合和拖动。将分支选择写入默认数据，保留冻结快照、revision／scope 校验及入队执行。 |
| 3 | 选区鼠标子树 | SelectionInputDispatcher 的 queueSelectionPointer、selectionPressRoute、handleSelectionDraftPress 仍按按钮、Alt 和工具模式分支。依次覆盖 CUBOID、PRISM、SMART、手柄和面变换，删除对应硬编码回退。 |
| 4 | 空闲／特殊物品与通用手势 | 先补真正的 double 识别器和容差数据模型，再迁移 Alt、远距、双击、扳手和普通第一击默认树；保持手册 9.5.1 的优先级。 |
| 5 | 共享工作区组合器 | 删除 WorkspacePreviewComposer 的生产无调用解析缓存及测试钩子；组合闭包迁入 common/workspace/composition，公共计算不持有客户端全局缓存。 |
| 6 | 旧包、命名、超限包／文件和客户端包环 | 先确定领域边界，再统一迁移引用。仅搬文件不能消除相互引用；最终以不接受基线的完整检查验收。 |
| 7 | 扩展接口与测试注入 | 主题注册、特殊物品注册、SelectionTool、对外只读事件和反射测试仍有缺口。 |
| 8 | 完整执行验收 | 当前整批代码通过完整 check、新世界 GameTest、严格架构检查，再做真实客户端矩阵；同步伪代码、结构与进度文档。 |

## 按手册章节核对

| 条目 | 当前状态 | 证据与剩余事项 |
|---|---|---|
| 1.1 顶层分层 | 未完成，静态确认 | 仍有 fastplace、workspace/submission 和 network/codec 类型。geometry/generation、placement、settings、interaction 等仍有生产实现。世界写入与服务器几何区域正在并发迁移，验收时重取清单。 |
| 1.2 包 ≤12／包说明／单类型包 | 未完成，静态确认 | client/input 顶层、fastplace/geometry/generation、fastplace/workflow、server/recovery 是热点。client/input 和多个 input 子包尚无 package-info。每包说明职责，不用豁免标记掩盖散落。 |
| 1.3 旧后缀 | 未完成，静态确认 | 仍有 15 个非例外类型使用 Dispatcher／Semantics，见下表。FaceRasterDispatcher 是第 10 节指定的精确例外。 |
| 1.4 职责改名／迁移 | 部分完成 | 视觉样式已迁客户端，任务已迁服务端；组合器仍叫 PreviewComposer，ClientSelectionPart 仍在双端共享模型中保留 Client 前缀，PolyhedronGenerator 仍存在。 |
| 1.5 mixin 分组 | 未完成，静态确认 | fastplace/placement/mixin 仍同时包含 history、workspace、interaction、sideeffect 和 placement 注入。迁移后更新 mixins.json，并记录同一 Level 目标注入的顺序。 |
| 1.6 测试树／testkit／反射 | 部分完成 | 已有 testkit，测试包仍有 workspace/submission 等旧层。14 个测试类仍使用反射或 Unsafe。根目录 run 数据、模板和空目录本次未做删除审计，不把未知数据算成可删。 |
| 2 类与状态归属 | 部分完成 | 旧预览总入口已删除，输入总入口已缩小；OperationSession、PlacementTask、SelectionOperationTask 仍超 800 行。ClientInputSession 仍有多套捕获／token 和手势析取，不能认定统一 ActiveGesture 已完成。 |
| 3.1 Intent 与命令 | 已有主体，仍需验收 | 已有 IntentBus、PlayerIntentSystem 和命令参数声明。IntentCommands 按显式 schema 构造参数链，并非从任意 record 自动推导；这是相对手册示例的实现差异。需确保所有命令／网络入口共用准入。 |
| 3.2 Mode 元系统 | 部分完成，迁移中 | 客户端已有注册；服务端几何代理正在处理生命周期。ClientModes 仍注册统一 GeometryMode；ServerMode 当前没有 open／tick／detach 方法，生命周期可能由组合能力承担，应核对最终实现和测试，不能凭类名判完成。 |
| 3.3 PreviewFrame | 主体已有，客户端未验证 | 注册模式与完整帧已有阶段记录；真实绘制、同帧指针一致、GPU 资源回收和主题切换仍需实测。 |
| 3.4 玩家／客户端作用域 | 主体已有，完整验收未完成 | 已有 PlayerStateRegistry、PlayerSessionSlot、ClientScope、WorldSessionEvents。未发现旧式静态 UUID→玩家状态表；按 MinecraftServer 的实例目录是另一种职责，不能直接算作玩家隔离失败。登出／换维／停服仍需执行证据。 |
| 3.5 任务系统 | 主体已有，结构未完成 | WorldTask、LeasedJournaledTask 与队列已有；两个大任务仍超限，内存／恢复测试仍靠私有反射。 |
| 3.6 历史／恢复 | 主体已有记录 | HistoryService、DurableCommit、HistoryEntry 和 recovery.spi 已有结构与阶段记录。本次未重跑故障／恢复矩阵，不能因实例类存在而省略崩溃一致性回归。 |
| 4 通用原语 | 主体已有，仍须核对迁移点 | Registry、Gate、LatestOnlyTask、IoExecutors、AtomicFiles、Envelope、Vectors、Axis、Epsilon、RuleChain 已存在。重复数学尚未全清理，例如 BresenhamColumnVolume 仍有自有 SIX_NEIGHBORS。 |
| 5 B1～B12 | 已有修复记录，本次未逐项执行 | 最终验收应按 B1～B12 分列回归证据；本次未再次复现所有触发条件。 |
| 5.2 写法规范 | 未全部完成，静态确认 | ClientInputSession 多处内联全限定类名；IdleSessionEntryController.capture 仍有多个位置布尔参数；ForTesting 钩子仍存在；输入 action/button 仍以数值和 Semantics 常量分支。 |
| 6 死代码／重复逻辑 | 未完成，静态确认 | WorkspacePreviewComposer.resolvedForPart 仅被测试调用，仍有静态 LRU。PlanarFaceRasterizer、BresenhamFaceSweep 和旧面适配仍在。TranslatedScan 按用户决定保留，不是待删除死代码。 |
| 7 扩展点 | 部分完成 | ThemeRegistry、特殊物品注册、SelectionTool 和 common/api 只读事件仍有缺口。其他已存在注册能力仍须做一次新增项目的扩展示例验收。 |
| 8 严格架构守护 | 未达标 | 已有 ASM 字节码检查器，尚未引入手册指定的 ArchUnit，见 architecture-verification.md。verifyArchitecture 通过只表示无新增债务；完整 verifyArchitectureStrict 必须通过。 |
| 9 完整操作树／长按画线 | 样板已有，完整树未完成 | 默认资源尚无 geometry／selection／special-item／idle 子树；普通鼠标尚不产生 double 输入。双击成功率、松开后立即再按、失焦、水中规则仍需实测。 |
| 10 面调度器 | 主体已接入，内部拆分／实测未完成 | DEFAULT 保留 CURRENT_QUAD／CURRENT_POLYGON／CURRENT_VOLUME_FACE，策略 hash 与同步已有记录。TranslatedScan 仍为 2467 行平铺算法；快速算法未默认采用是形状约束下的有意状态，不可强行替换。 |

## 操作树的具体缺口

当前默认绑定文件为 cancellation.json、confirmation.json、history-conflict.json、quick_shape.json、tools.json、workspace-history.json 和 workspace.json。取消与历史冲突是父代理的未提交阶段，本次未验证。

- 几何：client/input/GeometryInputController.java:79 的 queueInteraction 决定左右键目标、撤回与清选；:137 的 queuePath 决定加点／闭合；:165 的 queueGizmo 决定手柄捕获。数据化仍须沿用 PointerPress、scope、revision 与 release 所有权。
- 选区：client/input/SelectionInputDispatcher.java:84 的 handleSelectionDraftPress 按 Alt／模式调用不同路径；:132 的 queueSelectionPointer 处理 SMART、middle、Alt Gizmo、draft 和 point；:197 的 selectionPressRoute 是按钮 switch。需覆盖无操作分支，避免删绑定后被旧路径恢复。
- 空闲：IdleSessionEntryController.java:35 的 capture 保留优先级链；IdleEntryGesture.java:11 固定 2°／0.5 格放宽；fastplace/interaction/EntryDoubleClickTolerance.java:10 固定 200／300ms、15 格和 0.5°／0.5 格边缘余量；protectEntry 固定入口保护。它们仍不是绑定参数。
- 通用 double：InputPattern.java:10 接受 double，BindingSystem.onButton 只投递 press／release 并仲裁 hold。全生产源搜索字符串 double 只命中许可列表，没有普通鼠标自动产生该信号的路径。需要识别器及距档容差 Codec，不能只允许 JSON 写这个词。
- 统一手势：ClientInputSession.java:114 起仍有 undoPress、quickShapeUndo、shapeInput、selectionPointer、operationPointPointer、geometryGizmoCapture、PointerGestureState、clickGestureToken 等并行状态。blocksDraftLoad 与 InputWorldCleanup.pointerGestureInFlight 等使用不同析取，统一捕获所有权尚未完成。

## WorkspaceComposer 闭包方案

common/workspace/preview/WorkspacePreviewComposer.java:139、:140 的 FRAME_CACHE／RESOLVED_CACHE 是全局可变 LRU。:168 的 resolvedForPart 只有测试调用；生产只有 WorkspaceSelectionBounds 使用 frameForPart。OperationContext 的 clear／disconnect／tick 仍调用全局 invalidate／prune。

1. Composition、CompositionBatch、CompositionBudget、WorkspacePreviewComposer 迁入 common/workspace/composition，Composer 改名 WorkspaceComposer，保留显式预算和结果类型。
2. WorkspaceSelectionBounds 迁 common/workspace/geometry。检查 GeometryFrame 依赖后再决定是否提为独立值类型，避免 composition 与 geometry 循环。
3. 删除 resolvedForPart、RESOLVED_CACHE、FRAME_CACHE、计数与 ForTesting 方法。公共 frame 计算保持纯函数；确有性能需求时由客户端工作区／指针实例缓存。
4. 复用 WorkspacePointerResolver 现有实例 resolvedCache，不另建全局缓存。删除 OperationContext 的全局缓存清理调用。
5. 将缓存内部结构测试替换为结果、预算、同 ID 不同对象和变换等价测试；删除该测试的 Unsafe。统一 validator、SelectionBaseline、bounds、interaction 和测试引用。

该方案尚未实施，不能把改名本身算作纯领域收口。

## 扩展接口缺口

| 条目 | 静态证据 | 完成条件 |
|---|---|---|
| ThemeRegistry | client/render/theme/VisualThemes.java:53 使用 EnumMap，:57 遍历 GeometryPalette.Theme.values()；Theme 只有 CLASSIC／HUMANIST。JSON 只能覆盖固定主题。 | 按 ResourceLocation 注册／加载；第三主题无需改枚举或色板代码，UI、revision 和缓存失效使用同一注册快照。 |
| SpecialItemHandlers | server/specialitem/SpecialItemHandlers.java:22 是静态 HashMap<Item,UseOnBlock>，:31 的 register 直接 put，可静默覆盖；ClientModes 没有独立 SpecialItemMode。 | 冻结注册并拒绝重复定义，模式拥有完整能力，默认行为进入数据树。 |
| SelectionTool | 搜索只找到 SelectionToolPreference，没有约定的 onPress／ready／volume／codec 策略接口；输入仍判断 CUBOID／SMART。 | 工具与注册拥有完整能力，新增工具不改中央输入分支。 |
| 对外只读事件 | 未找到 common/api，未发现 ShapeCommitted、HistoryRecorded、ModeChanged 类型。 | 定义不泄露可变内部状态的事件接口，在对应系统发布。 |

## 已确认仍超 800 行的文件

| 文件 | 本次读取行数 | 处理方向 |
|---|---:|---|
| fastplace/session/OperationSession.java | 1144 | 拆选点、变换、局部历史和状态边界；不能用算法豁免。 |
| server/task/block/PlacementTask.java | 992 | 拆生成／内存／写入推进；保留统一资源生命周期。 |
| server/task/block/SelectionOperationTask.java | 858 | 拆选区扫描／执行计划，不复制共同任务状态。 |
| fastplace/geometry/generation/BresenhamColumnVolume.java | 1086 | 按职责拆专用子包；算法例外须明确原因，测试钩子改注入。 |
| fastplace/geometry/generation/TranslatedScanFaceRasterizer.java | 2467 | 保留输出与遍历顺序，迁 scan 子包，拆 4～5 个职责类。 |

此表不是当前全仓重新计算的完整大文件清单，最终用当前编译报告确认归零或精确算法例外。

## 禁止后缀与反射测试

静态搜索确认以下 15 个非例外生产类型仍存在：

- server/input/ServerInputDispatcher
- client/input 下的 ModifierCommandDispatcher、ClientPhysicalInputDispatcher、ScrollInputDispatcher、SelectionInputDispatcher、WorldHistoryCommandDispatcher、MouseReleaseDispatcher、OperationPointCommandDispatcher、PathCloseInputDispatcher、RemoteSelectionPointDispatcher
- client/input/policy/DragAdvanceSemantics
- client/input/mouse/MouseButtonInputSemantics、MouseDragReleaseSemantics
- client/operation/input/OperationInputSemantics
- workspace/submission/OperationExecutionSemantics

本次搜索 java.lang.reflect、sun.misc.Unsafe、setAccessible 与 getDeclared*，确认以下 14 个测试类仍存在：

- workspace/submission/OperationWorkspaceValidatorTest
- server/task/block/PlacementTaskTest、IncrementalMemoryContentionTest
- server/session/TaskRecoveryHandoffTest
- server/feedback/PlayerMessagesTest
- client/operation/workspace/WorkspaceContentPreparerTest、SelectionDeletionPlanTest
- client/operation/selection/SmartSelectionEditsTest
- client/operation/render/SourceBlockRenderMaskTest
- client/operation/controller/OperationContextTest、GroupRepeatTest
- client/operation/clipboard/OperationClipboardStateTest
- common/workspace/preview/WorkspacePreviewComposerCacheTest
- common/workspace/model/ClientSelectionPartTest

服务器任务测试通过反射写 future 或故障字段，其他多处用 Unsafe 构造 Minecraft 对象。应注入生成／世界读取／故障结果能力，或让纯规划接收已有快照，不为测试新增生产 ForTesting 壳。

## 最终验收需要的执行证据

1. 当前整批完整 check 与新世界 GameTest，记录套件数、失败／跳过、世界目录与日志。
2. 当前 verifyArchitectureStrict；旧 report／verify 文件可能对应不同提交，不能混用数字或只列 strictBoundaryViolations 为空。
3. 真实客户端：短按、长按阈值／松开、双击第一／第二击、15 格边界、水中流体、Alt、远距、扳手、特殊物品、取消、全部子树重绑／禁用／原版放行。
4. 几何／起形／选区预览与写入一致；面策略切换与 hash 不匹配失效；联机双方使用同一冻结策略。
5. 选区变换、删除、撤销重做、冲突 overwrite／skip／cancel、提交等待、断线恢复。
6. 界面、失焦、换维、登出、重连、停服后的手势／异步／GPU／磁盘资源清理。
7. 至少三主题、资源重载、错误 JSON、主题切换；真实视觉无遗漏／重复渲染。
8. 长按补发仍以原始按下时间算双击窗口；记录成功率及是否需改为补发时刻起算，不擅自改变容差。
9. 同步伪代码树、默认 JSON、source-layout 和主进度。删除已迁走旧包说明；取消／历史提示验证后更新待迁移描述。

保留约束：不统一四边面与多边面默认形状，不删除 TranslatedScan；不添加旧磁盘／协议兼容；不删除未确认数据。

## 后续实施记录

本审计完成后的同日，WorkspaceComposer 纯领域闭包已实施，详见[工作区组合计算收口](workspace-composition-finalization.md)。旧 preview 包、静态 LRU 与对应 Unsafe 缓存测试已删除；该能力仍等待主代理统一编译与回归。上文清单保留为审计时快照，不代表这些旧路径现在仍然存在。

主题注册随后已实施，详见[主题注册收口](theme-registry-finalization.md)。ThemeRegistry 按 ResourceLocation 加载任意命名空间主题，UI、颜色、revision 与渲染帧共用快照；18 项定向测试通过。完整 Gradle 回归与真实客户端视觉验收仍待执行。上文 Theme 枚举缺口是审计时快照，已由该阶段替代。

特殊物品冻结注册与 SelectionTool 策略也已接入生产，见[选区工具和特殊物品扩展](selection-tool-special-item-finalization.md)。工具按 ResourceLocation 注册，拥有按压／就绪／体积／codec，并进入会话、HUD、本地逆操作和版本 2 草稿；50 项定向测试通过。完整物理输入树、对外只读事件及最终实测仍由后续阶段完成。

对外只读事件随后已接入真实发布点，见[对外只读事件](public-event-finalization.md)。ShapeCommitted 等待历史持久提交后发布；HistoryRecorded 明确区分 QUEUED 与 DURABLE；ModeChanged 按已有仓库实际变化发布。实例订阅随服务器 Scope 关闭，并桥接 NeoForge 总线；监听器异常不改变已接纳结果。12 项新增边界测试通过，最终整批回归与实测仍待主代理完成。

PlacementTask／SelectionOperationTask 随后已按真实职责拆至 800 行以内，见[大型写入任务与测试注入收口](task-writer-finalization.md)。指定的生成、内存争用和恢复交接测试已移除私有状态反射，改用真实构造依赖和阶段结果。11 类 61 项定向测试通过；上文的大任务和对应反射清单保留为审计时快照。
