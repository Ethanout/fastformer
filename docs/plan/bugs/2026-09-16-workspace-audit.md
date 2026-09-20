# 2026-09-16 工作区与客户端会话审计

范围：`src/main/java/io/github/fastformer/client/operation/**`、`src/main/java/io/github/fastformer/client/session/**` 及其测试。

本轮不改 `client/render`、`client/input` 和服务端。工作树既有修改未回滚、未重置、未提交、未覆盖。

证据级别：

- 静态：当前代码逐行调用链，加上已安装的 Minecraft / NeoForge 源码。
- 自动化：本目录新增或更新的测试。本轮按任务要求未运行 Gradle，测试只做静态复核，尚未执行。
- 实机：未执行。

## 已确认并修复

### WA-1（P1，会话 / 恢复）：换维度销毁刚保存的旧维度草稿

触发条件：同一连接、同一玩家、同一进程内切换维度，且离开时工作区或选区草稿非空。

证据链：

1. 维度变化时 `ClientPacketListener.handleRespawn` 判定维度不同（源码 1157 行），新建 `ClientLevel` 并调用 `Minecraft.setLevel`（同文件 1180 行）。
2. `Minecraft.setLevel` 在替换世界前对旧世界发布 `LevelEvent.Unload`（源码 2126 行）。
   证据文件：`.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.233/neoforge-21.1.233-sources.jar` 内 `net/minecraft/client/Minecraft.java`、`net/minecraft/client/multiplayer/ClientPacketListener.java`。
3. `FastPlaceClientPreviewCore.onClientLevelUnload` 调用 `endWorldSession()`，经 `PreviewSessionLifecycle.endWorldSession()` 执行 `clearSourceMask()` 和 `disconnectOperation()`。
4. `ClientOperationController.onDisconnected` 调用 `ClientSessionManager.suspendCurrentDraft(...)`，把草稿写入旧维度 scope 的文件。
5. 下一个客户端 tick：`FastPlaceClientInput.onClientTick` → `ClientSessionManager.observePlayer` → `activateScope(新维度)`。

影响：旧代码在 `activateScope` 内调用 `current.endInteraction()`，把第 4 步刚放入内存的 `suspendedOperationDraft` 置空；随后 `deleteDraftFile(previousKey)` 删除刚写盘的唯一副本。该删除发生在任何新维度快照到达之前，身份校验没有执行机会。玩家返回旧维度后无法恢复草稿，与 `interaction_spec.md:264`、`design_guidance.md:17`、`architecture.md:35` 以及 TODO“按服务器/存档、玩家和维度隔离草稿”相反。

修复：

- `ClientPlayerSession.endInteraction()` 改为 `detachEnvironment()`。新方法只清实时手势状态，保留已暂存草稿。
- `ClientSessionManager.activateScope` 只调用 `detachEnvironment()`，删除该处的草稿文件删除。

文件清理仍由 `restoreCurrentDraft` 和 `discardCurrentDraft` 在草稿被消费或拒绝时执行，恢复仍要求玩家按 Enter 且服务端选择身份完全相等。

测试：`ClientPlayerSessionTest.detachingAnEnvironmentKeepsTheDraftStagedForThatEnvironment`、`ClientPlayerSessionTest.switchingDimensionKeepsTheDraftStagedForTheLeftScope`。

验证缺口：文件删除路径在无头测试中不可达（`loadedDrafts` 只在真实客户端填充）。该部分只有静态证据。

### WA-2（P1，源方块遮罩）：重连恢复后源遮罩不重建

触发条件：断线或世界卸载时工作区含已位移或待删除的 WORLD 部件；重连后玩家按 Enter 确认恢复。

证据链：

- 遮罩在边界被清空：`PreviewSessionLifecycle.endWorldSession()` → `clearSourceMask()`；`ClientOperationController.onDisconnected` 也调用 `SOURCE_MASK.clear()`。
- `confirmReconnectRestore` 恢复草稿后调用 `synchronize(pending)`。`ClientOperationController.java:220` 在 `!workspace().isEmpty()` 时直接返回，不调用 `refreshSourceMask()`。
- 工作区变更监听器只调用 `refreshInteractionState`（`ClientOperationController.java:77`、`87`），不刷新遮罩。

影响：恢复的位移部件重新显示其源方块，直到下一次编辑。用户确认恢复后同时看到源方块和虚影。这违反 BUG-02 的“移动期间源方块隐藏”要求，也与会话恢复 TODO 的“源引用”检查冲突。

修复：

- `confirmReconnectRestore` 在恢复草稿后、应用快照前调用 `refreshSourceMask()`。
- 遮罩坐标推导收敛到 `SourceBlockRenderMask.maskedSourcePositions(...)`（`SourceBlockRenderMask.java:102`）。控制器 `refreshSourceMask` 只调用该函数，规则只有一处定义。

测试：`ClientOperationControllerTest.confirmingAReconnectRestoreRebuildsTheSourceMask`、`SourceBlockRenderMaskTest.maskedSourcePositionsFollowThePartSourceAndItsDisplacement`。

### WA-3（P2，stack 整体堆叠）：重复手势混用整体步距与单元步长

触发条件：对一个已经堆叠过的部件再次拖动 SCALE（stack）轴。

证据链：

- `updateTransformGesture` 的 SCALE 分支用 `WorkspaceSelectionBounds.resolve(part)` 求当前整体边界，因此得到的是组间步距（宽 2 的源、一次堆叠后整体宽 4）。
- 同一分支只把 `repeats` 端点增加 `totalSteps` 个单元，而 `WorkspacePreviewComposer.resolveValues` 用同一个 `repeatStride` 位移所有副本。旧副本因此被重排：源为 x=0、1，第一次堆叠得到 x=0..3；第二次把 stride 从 2 改为 4，得到 x=0、1、4、5、8、9，已放置的 x=2、3 消失。
- 输入量化使用第三个单位：`FastPlaceClientInput.workspaceRepeatUnit` 的单部件分支返回 `RepeatDragQuantizer.structureExtent`（原始单元宽，不含已有重复），公共分支返回占用方块宽度。
- `2026-09-15-architecture-transactions.md` BUG-AO:116 已撤回“固定原始尺寸”的方案。步距从 2 变为 4 本身不是缺陷。缺陷是三个单位混用，以及重复手势重排已放置副本。

影响：第二次堆叠移动已经完成的工作，并且与拖动距离不一致。BUG-AO:117-120 和 TODO BUG-11 要求：以当前整体为本次基线、内部相对位置不变、组间步距等于当前整体范围、输入量化与生成共用同一步距。

修复：

- 单元步距（写入 `repeatStride` 的值）保持基础单元范围，来源为 `WorkspaceSelectionBounds.resolveBase`。任何重复手势都不再移动已放置副本。
- 每一步重复一个整体。`groupDelta = totalSteps × (整体范围 ÷ 单元范围)`，整体范围来自 `resolve`。
- 例：源宽 2、已有一次堆叠时，整体 4、单元 2，每步增加 2 个单元。第二次拖动一步得到 x=0..7，已放置的 0..3 不动，新组 4..7 等于整体的一份副本。
- 第一次堆叠不受影响：整体范围等于单元范围，每步仍是 1 个单元。

测试：`ClientOperationControllerTest.secondStackGestureRepeatsTheWholeGroupAndKeepsPlacedCopies`、`WorkspaceSelectionBoundsTest.wholeGroupBoxIncludesPlacedCopiesWhileTheCellBoxDoesNot`。第一次堆叠的既有断言保留在 `stackGestureUsesSelectionExtentForRepeatStride`。

跨 input 需求（本轮不改 input，通知主代理）：

1. `FastPlaceClientInput.workspaceRepeatUnit` 的单部件分支必须返回整体范围，与公共分支同源。例如 `WorkspaceSelectionBounds.extent(WorkspaceSelectionBounds.resolve(target), drag.axis())`。否则第二次堆叠后拖动灵敏度翻倍：拖动 2 格会加入 4 格内容。
2. 公共分支当前用 `OccupiedBlockBounds.width`（占用方块宽度），控制器用选择框范围。含空气的选择框会得到两个值，需要统一到同一函数。
3. 上述两处都在 input 文件内，本轮只登记需求。

未解决的模型缺口（只报告）：

- 公共重复的组间步距结论已修正。后续审计见 [2026-09-16-group-repeat.md](2026-09-16-group-repeat.md)：公共组在点阵一致时已经满足“整体为单元、组间步距等于整体范围、含空气”，本文件原先“无法同时对所有部件成立”的说法过宽，已作废。
- 真正的组层缺口在混合点阵：某个部件先用单部件手柄堆叠后，它的副本点阵与整体基础范围不同，单一 `(repeats, repeatStride)` 层无法既保留副本又刚性重复整体。修复需要组重复层，并同步网络计划编解码（BUG-AO:119、ARCH-2）。该改动超出本轮所有权。
- 旋转部件的整体范围当前是轴对齐包络，仍受 BUG-AN / BUG-AM 的坐标空间契约影响。

## 既有审计复核（只复核，不改其他所有者文件）

- BUG-AJ 已修复：`WorkspaceContentPreparer.clipboardParts` 先过滤空解析结果，再构造要求非空的 `OperationClipboard.Part`。
- BUG-AK 已修复：`OperationClipboardState.saveAndPublish` 先写盘再发布内存。保存失败保留旧剪贴板。`OperationClipboardStateTest` 覆盖。
- BUG-E 已修复：旧的 `clipboardLoaded` 标志已不存在。`OperationClipboardState.load` 只在成功读取后缓存，失败与文件缺失都可重试。
- BUG-AU 已修复：`WorkspaceContentPreparer.submissionParts` 过滤空内容，并对待删除部件保留非空 `sourceSnapshot`。`WorkspaceContentPreparerTest` 覆盖。
- BUG-AC / BUG-AI（撤回路由）仍未修复，归 input 归属：`WorkspaceKeyboardSemantics.decide` 只判断“工作区是否活动”，不读本地历史；`FastPlaceClientInput.handleWorkspaceShortcut` 忽略 `ClientOperationController.undo()` 的返回值。控制器已返回准确结果，需要 input 侧区分“已处理 / 无步骤 / 被阻止”。本轮不修改 input。
- BUG-Y（本地历史无界）仍是架构项。`ClientOperationEventStack` 无数量与内存上限，节点闭包持有完整 `Snapshot`。见下方架构方案。

### WA-4（P1，草稿读取）：读取失败与版本不兼容都会销毁唯一草稿

触发条件：启动或进入作用域时读取草稿文件，遇到临时 IO 失败，或遇到其他版本写入的文件。

证据链：

- 旧实现用 `Set<SessionKey> loadedDrafts` 表示“是否读过”。`add(key)` 在读取**之前**执行，因此“尝试过”和“读取完成”混为一个布尔值。
- 旧代码 `catch (IOException | RuntimeException)` 无差别调用 `deleteDraftFile(key)`。临时权限或磁盘错误、版本不兼容、解码失败三条路径都删除文件。
- 结果：唯一副本被删除，进程内不再重试（`loadedDrafts` 已包含该 key），用户也无法再次触发恢复。这与 `architecture.md` 的读取状态契约、ARCH-4 的“版本不兼容保留原文件”和“删除必须来自明确用户丢弃、已确认提交或规定的保留策略”相反。

修复：

- 新增 `ClientDraftLoadState`：`NOT_LOADED`、`READY`、`RETRYABLE_FAILURE`、`VERSION_INCOMPATIBLE`、`CORRUPT`。只读成功或明确不存在才进入 `READY`。
- `ClientSessionManager.attemptDraftLoad` 每次作用域只读一次。任何失败都保留文件，记录原因，并且只提示一次。
- 分开读取失败与内容失败：文件读取失败为 `RETRYABLE_FAILURE`；版本不同为 `VERSION_INCOMPATIBLE`；读取成功但解码失败为 `CORRUPT`。
- `ClientOperationDraftCodec` 新增 `VersionMismatchException extends IOException`，把“版本不同”和“内容损坏”分开。缺少 Version 字段仍按损坏处理。
- 明确的重试边界：`retryCurrentDraftLoad()` 清除失败状态，让下一次作用域 tick 再读一次；重新进入同一环境（作用域 key 变化）只清除 `RETRYABLE_FAILURE`，不自动重试版本或内容问题。同一作用域的重复调用绝不重读，因此没有每 tick 重试或刷屏。
- 作用域内已有草稿（`suspendCurrentDraft` 保存成功、`discardCurrentDraft` 显式丢弃、`restoreCurrentDraft` 消费或拒绝）都会把状态置为 `READY`。

删除授权核对：

- `restoreCurrentDraft` 旧代码无条件 `deleteCurrentDraftFile()`。新代码只在调用前确实存在暂存草稿时删除。两种情况都符合 TODO“服务器无活动选择、选择身份变化时必须清理草稿”：身份相等表示草稿被消费，身份不同表示选择身份已经变化。
- 没有暂存草稿时不删除。此时文件可能从未读取成功，删除会再次销毁唯一副本。
- `discardCurrentDraft` 保持删除：它只由玩家明确丢弃或“服务器无活动选择”触发。

测试：`ClientDraftLoadTest` 覆盖真实文件失败（损坏字节）、版本不兼容、解码失败、文件缺失、同作用域不重读、重新进入的边界语义、显式重试。测试注入临时路径，因此不依赖 `FMLPaths`。

### WA-4b（P1，提示路径）：草稿提示在客户端单例缺失时空指针

- 触发：任何草稿提示路径（读取失败、保存失败、删除失败）在 `Minecraft.getInstance()` 为 null 时执行。
- 证据：`build/test-results/test/TEST-io.github.fastformer.client.session.ClientDraftLoadTest.xml` 记录 6 个失败，类型 `java.lang.NullPointerException: Cannot read field "player" because "minecraft" is null`，栈为 `ClientSessionManager.showDraftMessage` → `recordDraftLoadFailure` → `attemptDraftLoad`。7 个测试中唯一通过的是文件缺失路径，因为它不生成提示。
- 影响：这不是测试问题。旧 `showDraftMessage` 只检查 `minecraft.player`，不检查 `minecraft`。启动早期或卸载后的提示会抛出异常，并中断产生该提示的作用域读取流程。测试只是把这个缺陷暴露出来。
- 修复：`showDraftMessage` 增加 `minecraft == null` 判断。不放宽测试断言。
- 复核：修正后同一提示路径只调用一次，状态在提示之前已写入，异常不再逃出 `attemptDraftLoad`。

### WA-4c（复核）：加载失败保留与重试实际可用

- `explicitRetryReadsAValidDraftAndStagesIt` 构造真实序列：损坏文件 → `RETRYABLE_FAILURE` 且文件仍在 → 换成可解码的草稿文件 → 记录的状态仍阻止重读且未暂存草稿 → `retryCurrentDraftLoad()` → 下一次读取返回 `READY`、暂存草稿、`restoreSuspendedOperationDraft(identity)` 成功。
- 该测试用的草稿没有部件，因此解码不需要方块注册表，可在无头环境验证完整链路。
- 既有测试继续覆盖版本不兼容与解码失败都保留文件。

语言键（本轮已加入 `src/main/resources/assets/fastformer/lang/en_us.json` 与 `zh_cn.json`，两个文件键集合一致，各 364 键，UTF-8 无 BOM）：

- `fastformer.message.operation_draft_load_failed`：EN “Session draft read failed: the file is kept and restore can be offered again; check the client log and file permissions”，ZH “会话草稿读取失败：文件已保留，可再次尝试恢复；请检查客户端日志和文件权限”。
- `fastformer.message.operation_draft_version_incompatible`：EN “Session draft version is not supported: the file is kept and was not restored; update the mod or discard the draft”，ZH “会话草稿版本不受支持：文件已保留且未恢复；请更新模组或丢弃该草稿”。
- `fastformer.message.operation_draft_corrupt_retained`：EN “Session draft is corrupt: the file is kept and was not restored; create the selection again or discard the draft”，ZH “会话草稿已损坏：文件已保留且未恢复；请重新创建选区或丢弃该草稿”。
- 旧键 `fastformer.message.operation_draft_corrupt` 与 `operation_draft_corrupt_delete_failed` 本模块不再引用，本轮保留键值不删除。
- 可选接线：`currentDraftLoadState()` 与 `retryCurrentDraftLoad()` 已公开，UI 可据此显示“重试读取草稿”。若需要按钮或按键，请在 input/render 侧接线。

## 复核后未发现确认缺陷的路径

- 可重试的提交拒绝：`applyWorkspaceResult` 保留工作区、重建遮罩、选中失败部件并记录失败坐标。失败坐标只对同一草稿生效（`failedTargetsForDraft`），再次编辑后自动失效。
- 提交超时：`onClientTick` 清提交状态、解锁、重建遮罩、提示超时，草稿保留可重试。迟到回执因 transfer ID 不再当前而被 `accepts` 拒绝。
- 提交成功：`SOURCE_MASK.complete` 先写入重叠的已提交目标，再 `discard`，随后 `clearWorkspace` 无残留恢复。
- 剪贴板边界：空部件在构造前被过滤，复制失败不改内存内容，粘贴在提交等待中拒绝并给出原因。

## 提示缺口（已确认，未修改）

- `paste` 的“部件上限”提示不可达。`ClientOperationWorkspace.MAX_PARTS` 为 `Integer.MAX_VALUE`，判定 `value.parts().size() > MAX_PARTS - workspace().size()` 永假。真正生效的上限是 `OperationClipboardCodec.MAX_BLOCKS`（2,000,000 方块），该失败经解码异常上报为 `operation_paste_clipboard_invalid`。TODO 中的“部件上限”场景因此没有专门提示。修复需要先定义剪贴板部件或方块预算的产品规则，并同时改 input 侧提示归属，本轮只记录。


## 排除项（不列为确认缺陷）

- 提交结果与源遮罩的线程竞争：`FastPlaceNetwork.handleOperationWorkspaceResult` 走 `context.enqueueWork`，`ClientPayloadDispatcher.applyWorkspaceResult` 与每 tick 的 `reapply()` 都在客户端线程。无跨线程写入。
- 源遮罩跨维度残留：维度切换触发 `LevelEvent.Unload`，`clearSourceMask()` 清空遮罩并按 `replacedStates` 恢复方块。本轮未修改。
- 提交成功路径对重叠目标使用客户端快照而不是服务端权威状态：差异由服务端方块更新覆盖，且 `complete()` 只处理被遮罩的坐标。没有确认的持久错误，不列为缺陷。
- “`lastOperationFailureKey` 是单一可变静态槽”：`copySelected`、`paste`、`submitWorkspace` 都在入口清空该槽，成功时不留下旧键，当前调用方不会显示错误结果。作为可读性建议记录，不作为缺陷。

## 架构方案（本轮已实施 WA-1 至 WA-4 的最小部分）

1. 会话作用域。已做到“切换作用域不销毁草稿”，并让读取结果显式化。下一步把暂存草稿做成按作用域可查询的数据，而不是只挂在会话盒子上，并让恢复入口只读取当前作用域。
2. 草稿读取边界。本轮已分状态、保留文件并给出显式重试入口。下一步把读取移出每 tick 的观察路径，改为在进入作用域时请求一次，并按 ARCH-4 增加真实的“加载中”状态。
3. 源遮罩。遮罩应与工作区数据一起变化。建议由工作区变更监听器携带修订号驱动一次推导，并把监听器拆为“轻量状态变化”和“内容变化”两类，避免每帧重复遍历全部源坐标。当前实现保留显式调用，因为逐帧调用会增加多部件拖动的开销。
4. 提交与遮罩竞争。本轮确认结果回调在客户端线程。仍需为“提交等待中取消工作区”定义遮罩与保留草稿的统一收尾，避免服务端任务继续写入时客户端先恢复源方块。
5. 客户端历史。先引入共享的不可变源快照，再按数量与内存双预算裁剪 `ClientOperationEventStack`。不要用限制部件数量规避模型成本。

## 未完成与实机缺口

- 本轮未运行 Gradle。WA-4b 的根因来自 `build/test-results/test` 的现有 XML；修复后的测试尚未重新执行，静态复核覆盖了导入、构造器参数、断言类型和调用可见性。
- 需要在 `.233` 实机验证：同连接换维度往返后的草稿确认与恢复；确认恢复后源方块保持隐藏；第二次堆叠的步距、空隙和 Gizmo 外框；断线时遮罩恢复；同一连接内连续两次换维度。
- 草稿文件注入故障未做实机验证：只读目录、磁盘满、文件被占用时确认保留文件并只提示一次；替换为其他版本文件后确认保留并按重试边界处理。临时路径测试覆盖读取失败与版本判断，但不覆盖真实配置目录。
- `deleteCurrentDraftFile`、`suspendCurrentDraft` 的真实路径经过 `FMLPaths.CONFIGDIR`，在无头测试中不可达。删除授权语义只有静态论证和内存态测试支持。
- input 侧堆叠量子需求（见 WA-3）未实现，拖动灵敏度在第二次堆叠后仍会翻倍，直到 `workspaceRepeatUnit` 改为整体范围。
- 同源空指针已补保护：`ClientOperationController.applyWorkspaceResult` 与 `onClientTick` 的四处提示路径同时检查 `Minecraft.getInstance()` 和 `minecraft.player`。生产环境通常存在客户端单例，但无头测试、启动前和关闭后不会再因提示代码中断状态收尾。
- 同类未加保护一处：`ClientOperationController.sourceSnapshotMatches` 直接解引用 `Minecraft.getInstance().level`。调用方只有 input 路径和 `adjustActiveAabbPoint`，生产环境有活动客户端，测试未触达。本轮不改 `client/operation`，只登记。
- 服务端在玩家换维度时仍调用 `FastPlaceManager.quit`（见 `2026-09-16-server-entry-audit.md`）。该行为会让新维度返回非活动快照，从而在 WA-1 保留草稿后由既有规则清理它。WA-1 是服务端修复的前置条件，不是重复写入风险。
- 草稿英文机械检查只识别少量英文词，不代表中文表述认证或程序正确性认证。
