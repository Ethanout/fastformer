# 2026-09-16 client/input 与 client/placement 输入门禁审计

范围：`client/input`、`client/placement` 及其测试。重点：空手近距离破坏、AABB 第二点门禁、Alt 与普通环境、会话切换残留。

编号沿用本文件第一版：IN-1 选区点阶段归属，IN-2 界面接管残留，IN-3 断线清理，IN-4 Q 丢弃点击；IN-5 至 IN-9 为跨模块与产品项；IN-10 为 WA-3 输入侧修复（后续任务追加）。

证据级别：当前代码静态调用链，加上生成的原版源码。

- `build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/transformSource/transformed/net/minecraft/client/KeyboardHandler.java`
- 同目录 `Minecraft.java`、`KeyMapping.java`、`sounds/SoundManager.java`
- 同目录 `net/neoforged/neoforge/client/event/ScreenEvent.java`

本轮未启动游戏，未做窗口焦点自动化。已运行一次定向测试命令，结果见“测试执行结论”。

## 已确认并修复

### IN-1（P1，类别 1/2/4）：门禁只看服务端预览，本地选区草稿不拥有输入；空手门禁漏掉实体目标

**已修复部分一：本地草稿不拥有输入**

触发条件（已逐段验证可达）：

1. 工作区有本地部件。例：Ctrl+V 粘贴的客户端部件，不产生服务端选区会话。
2. 按住 Alt 左键新建选区。`ClientOperationController.handleAltCreateClick` 写入本地草稿点。
3. 手持物切为空手，准星指向 4.5 格内的方块，左键。

证据路径：

- 修改前 `InteractionContext.nearVanillaBlock:49-58` 用 `ClientOperationController.selectionSessionActive()` 判断“选区拥有输入”，该方法等于 `serverPreview.active()`（`ClientOperationController.java:112-114`）。
- 本地草稿是已存在的客户端状态：`handleAltCreateClickInternal:500-503`、`handleCreateClickInternal:415-419` 调用 `selectionSession().addDraftPoint(...)`；`selectionDraftActive():150-152` 读取它。修改前没有任何输入门禁读取它。
- Alt 分支在 `FastPlaceClientInput.java:684-702`，先于工作区命中分支，所以草稿可以在没有服务端预览时存在。

影响：空手近距离时门禁把左键判给原版，玩家在正在编辑的选区旁直接进入开挖方块的破坏流程。这与 `interaction_rules.md`“按住 Alt 时旧选区、部件选择和原版放置不能抢占”和 `current_todo.md:54` 冲突。

修复：`InteractionContext.selectionOwnsPointer(serverSelectionActive, localDraftActive)` 成为唯一归属判断，门禁取服务端预览与本地草稿之或。为控制每帧开销，草稿读取放在廉价的阶段判断之后（草稿只可能在操作阶段存在）。

**已修复部分二：空手近距离门禁漏掉实体目标**

触发条件：空手，无选区会话，准星指向 3 格内的实体，且实体后方长射线上有方块，左键。

证据路径：

- 修改前空手分支只调用 `directNearVanillaBlock`，它要求 `minecraft.hitResult instanceof BlockHitResult`。
- 原版准星命中实体时返回 `EntityHitResult`（`Minecraft.hitResult` 由 `Entity.pick` 写入）。
- 于是 `nearVanillaBlock` 为假，`onInteraction:450-558` 进入空手分支；`:541-551` 的 `!operationSession && !nearVanillaBlock && operationCandidatePoint() != null` 成立时发送首点并取消事件。

影响：空手左键攻击近距离实体不生效，改为启动选区。

修复：新增纯函数 `InteractionContext.vanillaOwnsEmptyHandClick(blockWithinReach, entityWithinReach)`；空手分支按命中类型分别用 `blockInteractionRange()` 与 `entityInteractionRange()`。该分支只在没有选区拥有指针时生效，所以选区会话内的 Enter 确认与滚轮候选不受影响。

**未修复部分：AABB 点阶段缺少客户端所有权（跨模块）**

- 客户端发送首点后、服务端 active 预览到达前，`observedInputState()` 仍为 IDLE：`FastPlaceClientPreview.operationActive()` 只反映服务端预览、工作区和几何预览。
- 该窗口内右键不设第二点。空手时原版右键没有动作，表现为点击被吞掉；手持方块时走普通放置入口。
- 窗口为一个到两个服务器 tick，影响是丢失一次模组点击，不产生错误的世界写入。
- 本轮未用计时器掩盖它。正确修法属于 operation 所有权：需要客户端自有的点阶段，使输入层按真实状态判断归属；`InteractionContext.selectionOwnsPointer` 已预留该接缝。
- 协议侧需要服务端对点请求给出确认或拒绝，才能在没有本地草稿时结束等待。该要求与 `2026-09-15-architecture-interaction.md` 的 ARCH-6 请求账本同源。

### IN-2（P1，类别 1/2/4）：界面接管后手势残留（BUG-U 复核：仍然存在）

触发条件：

1. 会话中开始工作区 Gizmo 拖动或面拖动，不取消选区。
2. 打开背包或聊天界面。
3. 在界面内松开鼠标。
4. 关闭界面，移动视角。

证据路径：

- `FastPlaceClientInput.onMouseButton:635-637`：有界面时鼠标事件直接返回，早于 `:650-663` 的手势代次结束。
- 每 tick 清理只判断 `activeSession()`（修改前 `:1262-1274`），不判断 `screen` 或窗口焦点。
- `updateWorkspaceGizmoDrag`、`updateWorkspaceFaceDrag` 也没有界面或物理按键释放判断。
- 与 `docs/plan/bugs/2026-09-15-architecture-interaction.md` 的 BUG-U 结论相同；本轮确认该缺口仍然存在。

影响：松键事件不进入结束分支，拖动与编辑 token 保留。回到世界后移动视角继续推进旧拖动，工作区编辑基线一直打开，直到下一次点击触发取消。

修复：

- 新增 `client/input/InputContextBoundary`：纯函数判断世界输入上下文是否仍拥有进行中的指针手势。
- `onClientTick` 在会话活动且手势在飞、同时界面打开或窗口失焦时调用新的 `suspendPointerGesture`。它复用 `cancelOperationGesture`（取消编辑 token、恢复本地变换起点、清除按键按下状态）并额外清空 `clickGestureToken`，保证单次触发。
- 选区内容按设计保留，只结束物理手势。

### IN-3（P2，类别 2/3）：断线 tick 分支漏清输入状态

证据路径：

- 修改前断线分支只清 `INPUT_STATE`、`QuickReplaceMode`、`BUILDING_RIGHT_PRESS`、拖拽字段与指针手势。
- 对照 `endWorldSession:113-122`，缺少 `MODIFIER_STATE`、`radialChordDown`、路径双击、操作点双击、两个工作区拖拽字段与 `clickGestureToken`。

影响：多数字段在一个 tick 内由 `pollModifierKeys` 自行收敛，残留窗口内 `modifierHeld()`、`InteractionContext.alternative` 与径向弦状态与实际物理状态不一致。风险有限，但两条清理路径规则不一致。

修复：断线分支补齐与 `endWorldSession` 相同的复位，并同步 `ClientOperationController.setAltMode(false)` 与 `InteractionContext.reset()`。

### IN-4（P2，类别 1/2/4）：Q 取消同时留下原版丢弃点击（BUG-AF 复核：仍然存在）

触发条件：默认按键配置，手持非空物品，未提交会话，按 Q。

证据路径：

- `KeyboardHandler.keyPress:476-485` 先 `KeyMapping.set(true)`、`KeyMapping.click(key)`，再 `ClientHooks.onKeyInput`。
- 修改前 `FastPlaceClientInput.onKey` 处理取消后从模组回调返回，没有消费这次丢弃点击。
- 之后 `Minecraft.handleKeybinds:2001` 的 `while (this.options.keyDrop.consumeClick())` 消费它。
- 服务端 `FastPlaceEvents.onItemToss` 有会话活动时的返还保护，但 `ServerInputDispatcher.quit` 与 `FastPlaceManager.quit` 会先清除普通会话，返还条件可能已经为假。

修复：

- `CancelInputSemantics.consumesVanillaDrop(decision, cancellationAccepted, cancelKeyBoundToVanillaDrop)`：只在纯策略认领、状态机接受取消、原版丢弃绑定匹配时为真。
- `onKey` 先请求状态机取消，并只在状态机实际接受后消费一次 `keyDrop` 的待处理点击。空闲状态不消费，改键后不消费，纯策略或状态机拒绝时不消费。
- 提交等待阶段纯策略返回 `REPORT_SUBMISSION_PENDING`，显示提示并保留原版丢弃点击。该阶段不会错误退出正在提交的会话。
- 按键比较使用 `KeyMapping.getKey()` 与 `InputConstants.Type.KEYSYM`。
- 复核修正（主代理合并时发现）：本轮初版在 `onKey` 中先按纯策略判定、消费丢弃点击，之后才请求状态机取消。`CancelInputSemantics.decide` 不读取输入路由，空闲态同样返回 `REQUEST_CANCEL`，于是空闲态按 Q 会先被吞掉丢弃点击，随后取消被状态机拒绝，Q 变成无动作。现改为先调用 `cancelActiveSession`，再把 `cancellationAccepted` 传入 `consumesVanillaDrop`，空闲态与提交等待态都不再吞掉原版丢弃。
- 对应测试：`CancelInputSemanticsTest.theQueuedVanillaDropClickIsConsumedOnlyForAMatchingAcceptedCancel` 增加“未接受取消”为假的断言。

### IN-7（P2，类别 1/2/4）：Esc 取消没有入口（BUG-AE 复核：仍然存在，本轮用真实钩子修复）

证据路径：

- `KeyboardHandler.keyPress:460-463` 先调用 `Minecraft.pauseGame`。
- `Minecraft.pauseGame:1620-1630` 要求 `screen == null` 并创建 `PauseScreen`。
- `Minecraft.setScreen:1043-1046` 在安装界面前发布 `ScreenEvent.Opening`，取消该事件会直接返回，`this.screen` 保持为 null。
- `GameRenderer.render:1004-1010` 在窗口失焦且 `pauseOnLostFocus` 为真时调用同一个 `pauseGame(false)`。这是 `pauseGame` 除 Esc 以外的唯一调用方，钩子必须区分两种来源。
- 修改前 `FastPlaceClientInput.onKey` 要求 `minecraft.screen == null`，此时暂停界面已建立；纯策略只接受键码 81。

修复：

- 新增 `ScreenEvent.Opening` 处理器 `FastPlaceClientInput.onScreenOpening`，挂在同一 game bus 订阅者上。只在“新界面是 `PauseScreen`、当前没有界面、且窗口仍然激活”时介入，已有界面的 Esc 仍按界面关闭规则处理。
- 窗口激活判断用于区分两个 `pauseGame` 调用方：`KeyboardHandler.keyPress:461`（Esc）与 `GameRenderer.render:1009`（失焦暂停）。失焦时打开的是同一个界面，此时不取消会话，只由 IN-2 结束物理手势。
- 新增 `CancelInputSemantics.decideEscape(...)`：没有可取消会话、没有通道、客户端未就绪时返回 `IGNORE`，原版暂停照常打开；提交等待中返回 `REPORT_SUBMISSION_PENDING`，提示后仍允许暂停，避免 Esc 锁死。
- 接受取消时先结束会话，再取消界面事件。取消逻辑提取为 `cancelActiveSession(...)`，与 Q 共用同一条路径。
- `pauseGame` 在 `setScreen` 返回后仍会暂停集成服务器声音，因此取消界面后下一 tick 调用 `SoundManager.resume()`，避免无声状态残留。
- 浏览器/桌面焦点不在本模组可控范围；窗口失焦由 IN-2 的边界处理。

## 记录但未修改

### IN-5（跨模块，render 所有权）：射线模式由渲染所有者决定

- `FastPlaceClientPreviewCore.raycastBlocks:3270-3293` 用 `PreviewRenderOwner` 在 `clipForPlacement`（忽略可替换方块）与 `clip`（原版 outline）之间选择。
- 空手选区首点来自 `operationCandidatePoint()`，因此命中策略取决于渲染所有者，而不是输入阶段。
- `current_todo.md:54` 要求空手选区使用与原版空手左右键一致的命中策略；`FastPlaceClientInput.longRangeSelectionBlockHit:2519-2521` 已按该要求实现，但预览侧目标提供器走另一条路径。
- 属于 `client/render` 所有权，交由 render 代理。

### IN-6（未修改，需产品决定）：视觉淡出进度仍被当作输入门禁

- `InteractionContext.nearVanillaBlock` 对非空手返回 `DISAPPEARANCE.disappeared()`。`DisappearanceState` 每 tick 只变化 1 到 3 单位，容量 20，且 `tick` 要求相机速度不超过 14 度每 tick，因此该值最多要 20 tick 才为真，快速转视角时还会退回假。
- 该谓词同时被 `SubmissionKeyboardSemantics.decide`（Enter 确认）与 `ScrollInputSemantics.decide` 使用。
- 本轮不改：把输入让行改成即时几何判断会同时改变快速起形的 Enter 确认语义，需要产品确认后按调用点拆分。空手路径不受影响。

### IN-8（已验证，不作为缺陷）：三个候选问题不可达

- `MousePressRoutingSemantics.rightTarget` 的通用原版让行位于 `cuboidWorldTarget` 之前，但两者不可能同时成立：`cuboidWorldTarget` 需要 `operationCuboid()`（服务端预览 active），而预览 active 时 `nearVanillaBlock()` 提前返回 false。因此该顺序不是可达缺陷，本轮不改，避免无依据的语义变更。
- `leftTarget` 把 `yieldToVanilla` 放在最前，同样需要“服务端预览 active 且近距离让行同时成立”，不可达。
- `ModifierGestureState.consume()` 不清除 `held()`，因此单次 Alt 手势不会把环境切回普通环境。Alt 与普通环境没有被 `consume` 混淆。

### IN-9（client/placement 审计结果）

本轮检查了 `ClientPlacementRouter` 与 `QuickReplaceMode`，未确认可达缺陷：

- `ClientPlacementRouter` 的每个发送入口都先检查通道能力（`supports`），提交类动作先调用 `beginPlacementRequest`，发送失败时调用 `abortPlacementRequest` 恢复原阶段。`ClientInputStateMachine.State.completeSubmission` 对非成功结果返回提交前状态，因此失败不会锁死输入。
- `QuickReplaceMode.canReplace` 要求手持可放置物品，`preview` 使用 `clipForPlacement`，与放置语义一致。
- 观察项（未判为缺陷，需要产品确认）：`FastPlaceClientInput.onClientTick:1333-1336` 在快速替换开启且右键按住时每 tick 发送一次 `QuickReplacePayload`，即每秒约 20 个包。服务端是否按“持续替换”语义处理需要放置所有者确认；若预期是单次动作，应在客户端改为按键沿触发。
- 跨模块项：`ClientPlacementRouter.prepareWorkspace` 每次尝试生成新的 `transferId`，重试不是原请求的继续。该问题已记录在 `2026-09-15-architecture-interaction.md` 的 ARCH-6，本轮不重复修改。

## 已确认并修复（追加：WA-3 输入侧）

### IN-10（WA-3 输入侧，已修复）：堆叠拖动量化使用单元范围而非整体范围

触发条件：对一个已经堆叠过的部件（或公共组）再次拖动 SCALE 手柄。

证据路径（修改前）：

- `FastPlaceClientInput.workspaceRepeatUnit` 单部件分支返回 `RepeatDragQuantizer.structureExtent(target, axis)`，来源是单元范围。
- 同方法公共分支用 `WorkspacePreviewComposer.resolve` 推导 `OccupiedBlockBounds.width`，来源是占用体素宽度，含空气的选择框会得到另一个值。
- `ClientOperationController.updateTransformGesture:701-708` 用 `WorkspaceSelectionBounds.resolve`（整体框）除以 `resolveBase`（单元框）得到 `cellsPerGroup`，一步重复一个整体。
- 三个单位混用：`workspace-audit.md` WA-3:60-86 记录：“第二次堆叠后拖动灵敏度翻倍：拖动 2 格会加入 4 格内容”。

影响：第二次堆叠时输入步距只有整体范围的一半，一次拖动加入两个整体；含空气的选择框在公共组下与控制器给出不同步距。

修复：

- 新增 `client/input/RepeatStrideSemantics`：`stride(...)` 返回一步重复所需的方块行程。公共组用 `WorkspaceSelectionBounds.resolve` 的 union，单部件手柄用该部件自己的 `resolve`，与写入侧一一对应。
- 盒子与范围计算全部复用 `WorkspaceSelectionBounds.union/extent`，输入层不保留第二套规则。`extent` 在空盒上返回 0，`stride` 保证不低于 1。
- 同一语义只保留一个实现：`FastPlaceClientInput.workspaceScaleUsesRepeat` 改为调用 `RepeatStrideSemantics.repeatsWholeGroup`，不再重复“SCALE 且非棱柱”的判断。
- `workspaceRepeatUnit` 变成三行委托；`FastPlaceClientInput` 的 `WorkspacePreviewComposer` 导入随之删除。

不变量（与写入侧对齐）：

1. 第一次堆叠不变：没有副本时整体框等于单元框，步距与旧值相同。
2. 第二次堆叠：整体框含已放置副本，步距随之增大，一步仍只加一个整体。
3. 含空气的选择框按选择框宽度，不按占用方块宽度。
4. 公共组按选择框 union。
5. MOVE/ROTATE、棱柱部件、找不到手柄目标时步距为 1，行为不变。

测试：`RepeatStrideSemanticsTest` 覆盖第二次堆叠（宽 5 的选择经一次堆叠后整体 10，并断言 `copiesForOffset` 在 10 格才开始第二步）、含空气选择框（五格宽、零方块仍返回 5）、公共组 union（0..5 与 10..13 得到 13，单部件仍为 5 与 3）、非重复操作与棱柱、以及空盒退化。

限制与未做：

- `RepeatDragQuantizer.structureExtent` 不再被输入层调用，变为该包内的未使用公开方法。本轮不动 `client/operation`，交由该所有者决定删除或保留。
- 步距在手势期间恒定（`drag.baseline()` 冻结），但当前每次 tick 重算。含 `selection() == null` 的部件时 `WorkspaceSelectionBounds.resolve` 会走 `WorkspacePreviewComposer`，与控制器同一 tick 的同类调用相当。若实机测量显示拖动卡顿，可在手势开始时冻结步距，本轮不做。
- 未解决的整体模型缺口仍属 operation 所有权：公共重复的组间步距按部件保存（WA-3:88-91），本轮只统一输入与写入的单位。

## 已解除的阻塞与越界报告

### B-1（已解除）：并发改动曾导致主源码编译失败

- 位置：`src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java:664-675`。同一个方法 `updateTransformGesture` 内两次声明局部变量 `AABB groupSelectionBounds`（664 行与 671 行），javac 报“已在方法中定义了变量”。
- 证据：`.\gradlew.bat test --tests "io.github.fastformer.client.input.*" --no-daemon --console=plain` 输出 `> Task :compileJava FAILED`、`1 个错误`，随后 `BUILD FAILED in 16s`。
- 归属：`client/operation`，与本轮输入任务无关；`git status` 显示该文件在并发修改中。本轮不修改该文件。
- 影响：首次运行停在 `:compileJava`，未执行测试。
- 可行的最小修复：删除 671-675 行这对重复声明之一。两段代码完全相同，保留 664-668 行即可。
- 复核：operation 所有者随后删除了重复声明。主代理重跑后，主代码与测试代码均编译通过。

### B-2（IN-1 剩余部分）：operation 层需要提供客户端点阶段

输入层已接好归属判断（`InteractionContext.selectionOwnsPointer`）。要让“首点已发出、服务端预览未到”的窗口真正设置第二点，operation 层需要提供二者之一：

1. 草稿生命周期入口：只写草稿、不进工作区的加点与清除接口，输入层据此在发送首点时写入本地草稿，并在服务端确认后清除。
2. 客户端自有点阶段：把本地草稿计入 `FastPlaceClientPreview.operationActive()` 或等价阶段投影，使 `observedInputState()` 不再只依赖服务端快照。当前 `cuboidWorldTarget` 需要 `operationCuboid()`（服务端预览 active），因此只改输入层无法让右键命中该分支。

另需服务端对点请求返回确认或拒绝，才能在没有本地草稿时结束等待。本轮未用计时器代替该确认。

### B-3（其他所有者）

- `client/render`：`raycastBlocks` 的射线模式由 `PreviewRenderOwner` 决定（见 IN-5）。
- 产品决定：淡出进度是否继续充当输入门禁（见 IN-6）。
- `client/placement`：快速替换按住右键时每 tick 发包，需要放置所有者确认语义（见 IN-9）。

## 测试执行结论

- 已运行：`.\gradlew.bat test --tests "io.github.fastformer.client.input.*" --no-daemon --console=plain`。
- 结果：主代码与测试代码编译通过。24 个 `client/input` 测试类共 113 项测试通过，失败 0，错误 0，跳过 0。
- 环境记录：主代理本地运行先受 Gradle 下载、插件解析与共享锁限制。连接执行会话完成了最终测试。
- 并发改动核查：本轮依赖的公开入口全部仍然存在且签名未变——`ClientOperationController.active/selectionSessionActive/selectionDraftActive/setAltMode/clearWorkspace/workspaceSubmissionPending/reconnectRestorePending/dismissReconnectRestore`、`FastPlaceClientPreview.active/operationActive/clearTransientFeedback/reconnectPreviewRestorePending`、`ClientInputStateMachine.InputKind.CANCEL` 与 `Dispatch.CANCEL`。`client/session` 的草稿加载类改动不与这些入口相交。
- 覆盖确认（清理时核对）：本轮 9 个主源码与测试文件的最后写入时间为 01:34:01 至 01:54:19，全部早于 XML 写入时间 01:56:38，因此该次运行编译并执行的是当前源码，包含 IN-10 的 `RepeatStrideSemantics` 与 `RepeatStrideSemanticsTest`。
- 进程清理：该次运行的 Gradle 守护进程 PID 34888（`GradleDaemon 9.2.1`，01:56:15 启动）在构建结束后空闲不退出，无子进程、无测试 worker、无客户端；3 秒采样内 CPU 时间不变（5.42 秒 → 5.42 秒）。已用 `Stop-Process -Force` 结束。清理后复核测试 XML 仍为 24 个报告、113 测试、0 失败、0 错误，产物未受损。
- 有意保留：守护进程 PID 41344（02:03:29 启动）在 02:03:47 以 `Success` 结束了一次短构建，之后空闲且无客户端。它不属于本次 client.input 测试任务，可能是主代理的验证构建，因此未结束。若需清空全部 Gradle JVM，可对该 PID 执行 `Stop-Process -Force`，或运行 `gradlew --stop`（会启动 Gradle 客户端）。

## 测试

新增：

- `InputContextBoundaryTest`：世界上下文保留手势、界面接管结束手势、失焦结束手势、无手势不打断。
- `RepeatStrideSemanticsTest`：第二次堆叠的整体步距、含空气选择框、公共组 union、非重复操作与棱柱部件、空盒退化（对应 IN-10 / WA-3）。

扩展：

- `InteractionContextTest`：`selectionOwnsPointer` 的服务端预览/本地草稿矩阵；`vanillaOwnsEmptyHandClick` 的方块、实体、两者与空集。
- `CancelInputSemanticsTest`：`decideEscape` 的接受取消、无可取消会话、通道缺失、客户端未就绪与提交等待分支；`consumesVanillaDrop` 还要求状态机实际接受取消。

未交付：`SelectionPointPhase` 曾按“等待若干 tick”实现，属于无服务端确认的计时状态，已删除。

## 实机缺口

以下必须在游戏内确认，自动化不能替代：

1. 空手在 4.5 格内左键破坏立即生效，且不被模组吞掉。
2. 空手左键攻击 3 格内实体，且实体后方有方块时不再启动选区。
3. 带本地草稿（Alt 新建）时空手左键不再开挖方块，而是编辑草稿。
4. 拖动中打开背包、聊天与窗口失焦后，回到世界不再推进旧拖动；选区按策略保留。
5. Q 取消后不掉落物品，改键与 Ctrl+Q 行为不变；空闲状态按 Q 仍按原版丢弃物品，不能被模组吞掉。
6. Esc 在活动会话中取消会话且不打开暂停界面；无会话时暂停照常打开；已有界面时按界面规则关闭；集成服务器声音不残留暂停。
7. IN-1 的客户端点阶段与 IN-5 的射线模式需各自所有者实现后再验收。
8. IN-10 的堆叠量化：单部件连续两次堆叠后，拖动灵敏度等于整体范围，已放置副本不再重排；含空气的选择框与公共组使用选择框 union。需记录步距、间隙与 Gizmo 外框是否一致。

本文为中文文稿，英文机械检查器覆盖有限，不作为语言认证。
