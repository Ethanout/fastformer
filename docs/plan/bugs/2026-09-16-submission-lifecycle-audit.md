# 2026-09-16 工作区提交结果生命周期审计

范围：`WorkspaceSubmissionTracker`、`ClientOperationController` 提交出入口、客户端输入状态机、服务端 `OperationManager` / `FastPlaceNetwork` / `PlayerPreviewSync` 的提交队列与回包路径。

本轮只读。未改任何生产文件。工作树既有并发修改未回滚、未重置、未提交、未覆盖。

证据级别：

- 静态：当前代码逐行调用链。
- 自动化：本轮未运行 Gradle，未新增测试，无执行结果。
- 实机：未执行。

结论分成两类。第一类是已证实执行路径，每一步都有具体文件与行号，可从客户端输入一路追到服务端任务与回包。第二类是尚未证实，只有类型级缺口，未找到可达触发序列，因此不作为缺陷。

并发修改提示：审计期间断线代理已修改 `OperationManager`、`FastPlaceNetwork` 与 `ClientOperationController` 的提交和结果接点，并新增 `WorkspaceSubmissionLedger`、`WorkspaceSubmissionBook` 与客户端回执查询。本报告中 `OperationManager` 的行号取自审计时修订，该文件已从 707 行增至 782 行，相关行号以第 404 至 444 行（`applyWorkspace`）、第 480 至 490 行（`tickWorld`）、第 743 至 745 行（`taskActive`）为准。结论 D 提到的重放账本已由该代理实现，重复提交一节在用于修复前需要按账本重新核对。

## 结论摘要

| 编号 | 严重度 | 结论 | 证据 |
|---|---|---|---|
| SL-1 | 已推翻 | 写入闸门关闭时，提交在准入处被拒绝，不产生无限 pending | 静态，`canOperate` 第 35 至 39 行 |
| SL-1b | 未证实 | 闸门在提交被接受之后关闭：任务停摆，客户端无超时 | 链路成立，触发仅限调度器异常 |
| SL-2 | P2 | 服务端“已有任务/占用”拒绝被客户端显示为“方块冲突” | 静态，全链路 |
| SL-3 | P2 | 提交等待中发生连接边界或换维度：迟到 accepted 回包被丢弃，且草稿不落盘 | 静态，全链路 |
| SL-4 | 未证实 | 与本提交无关的 `OPERATION_TASK` 锁住本次提交 | 类型缺口存在，无触发序列 |
| SL-5 | 未证实 | `cancelTask` 不发结果导致客户端停在结果宽限期 | 类型缺口存在，无触发序列 |

## 已证实的执行路径

### SL-1（已推翻）：写入闸门关闭时提交在准入处被拒绝

复核结论：原先的“闸门关闭后仍入队并永久等待”不成立。本轮重新逐行核对准入路径，遗漏了 `canOperate` 内的闸门检查。

证据链：

1. `ServerInputDispatcher.canOperate` 第 35 至 39 行是“创造模式、设置已启用、`PersistentRecoveryJournal.writesAllowed()`”三者的合取。
2. `ServerInputDispatcher.applyWorkspace` 第 520 至 525 行在调用 `OperationManager.applyWorkspace` 之前先要求 `!interactionBlocked`、`canOperate`、`!nearNormalBlockReach`。闸门关闭时 `canOperate` 为 false，因此在 `OperationManager` 之前就返回 false。
3. `FastPlaceNetwork.handleOperationWorkspaceApply` 第 295 至 299 行在返回 false 时发送 `accepted=false`（5 参数重载，`retryable` 默认 true）。
4. 客户端因此清跟踪器、解锁工作区、保留草稿，并显示 `operation_submit_rejected`。没有任务入队，没有永久等待。

残余一（未证实，SL-1b）：闸门在提交被接受之后关闭。`WorldTaskFeature.tickSafely` 第 40 至 48 行在任一次调度器 tick 抛异常时调用 `blockNewWrites()`。此时已在 `TASKS` 中的任务停摆（`OperationManager.tickWorld` 第 429 至 432 行 `break`），`taskActive` 仍为真（第 683 至 685 行），活动仍是 `OPERATION_TASK`，客户端 `tick()` 在 `TASK_ACTIVE` 永远返回 false（`WorkspaceSubmissionTracker` 第 50 至 52 行）。触发条件是调度器异常，本轮未做故障注入，未确认出现频率。

残余二（防护缺口，需跨文件）：`FastPlaceNetwork.handleShapePlacement` 第 330 行直接调用 `OperationManager.applyWorkspace`，不经过 `canOperate`。`ShapePlacementPayload` 在当前 `src/main` 中没有生产发送方，只有注册与测试，因此该路径当前为死代码。若将来出现发送方，它会绕过写入闸门。

### SL-2（P2，提示正确性）：服务器忙的拒绝显示为“方块冲突”

触发条件：另一玩家的任务占用同一维度的写协调器，或该玩家自己有放置任务在执行，且此时提交工作区。

证据链：

1. `OperationManager.operationBusy` 第 668 至 673 行包含 `WorldWriteCoordinator.busy(server, dimension)`。该条件是按维度而非按玩家的，因此另一玩家的写入也会让本次提交被拒绝。
2. `OperationManager.applyWorkspace` 第 409 至 412 行显示动作栏 `operation_task_running` 并返回 false。
3. `FastPlaceNetwork.handleOperationWorkspaceApply` 第 295 至 299 行在返回 false 时调用 5 参数 `sendWorkspaceResult`。该重载把 `retryable` 默认成 true（第 552 至 557 行）。
4. 客户端 `ClientOperationController.applyWorkspaceResult` 第 937 至 963 行因 `shouldRetainWorkspaceAfterFailure(true)` 保留工作区，并显示 `operation_submit_rejected`，文案是“提交未通过：部分方块发生冲突，选区已保留，可修改后重试”。

影响：本次拒绝与方块冲突无关，玩家按提示去修改方块不会解决。真实原因在动作栏里，两条消息同时出现且互相矛盾。保留工作区与可重试的结论本身正确，只有归因错误。

补充：`ServerInputDispatcher.applyWorkspace` 第 521 行的拦截分支不显示任何服务端提示，客户端同样收到 retryable 的冲突文案。该分支下玩家看不到任何真实原因。

第三种原因（写入闸门关闭）：闸门关闭时 `canOperate` 为 false，第 521 行拦截并返回 false，客户端同样显示“部分方块发生冲突”。服务端此时也不显示任何提示，只有 `PlayerLifecycleEvents` 第 70 行在登录时检查过闸门。因此同一个错误文案至少覆盖三种真实原因：占用、内存或设置拦截、写入闸门关闭。

### SL-3（P2，草稿与结果）：连接边界丢弃迟到回包，且草稿不落盘

触发条件：玩家提交工作区后，在结果到达前发生断线、退出世界或换维度。

证据链：

1. `OperationManager.applyWorkspace` 第 423 行在入队成功后调用 `cancel(player)`，清掉服务端操作会话并同步非活动预览。
2. 客户端 `ClientOperationController.synchronize` 第 233 至 240 行的非活动分支用 `shouldClearWorkspaceAfterSnapshot(previous, submissionPending)` 判定；提交等待中为 false，因此工作区被保留。
3. 连接边界触发 `ClientOperationController.onDisconnected`（第 996 至 1016 行）：清空 `WORKSPACE_SUBMISSION`，再调用 `ClientSessionManager.suspendCurrentDraft(OperationDraftIdentity.from(serverPreview))`。
4. `OperationDraftIdentity.from` 对非活动预览返回 null。`ClientPlayerSession.suspendOperationDraft` 第 36 至 49 行在 identity 为 null 且实时草稿非空时把 `suspendedOperationDraft` 置为 null，然后执行 `clearLiveInteraction()`。
5. `ClientSessionManager.suspendCurrentDraft` 第 113 至 114 行取不到草稿数据就直接返回，不写盘。
6. 服务端任务在重连后继续运行并发送 accepted 结果。客户端 `applyWorkspaceResult` 第 911 行用 `WORKSPACE_SUBMISSION.accepts` 判定，跟踪器已清空，回包被静默丢弃。此时既不调用 `SOURCE_MASK.complete`，也不调用 `clearWorkspace`，也没有成功提示。

影响：世界已经写入，客户端没有任何确认。若该任务随后失败并回滚，玩家手上也没有草稿可以重试，必须重新选择。设计文档 `2026-09-16-workspace-audit.md` 架构方案第 4 条已经指出“提交等待中取消工作区”缺少统一收尾，本条给出它的第二个入口。

## 尚未证实（仅理论风险）

### SL-4：与本提交无关的 `OPERATION_TASK` 锁存

类型缺口确实存在。`WorkspaceSubmissionTracker.observeActivity` 第 40 至 46 行只看活动值，不看 transfer ID。任何 `OPERATION_TASK` 都算“本次任务已开始”，任何其他值都算“本次任务已结束”。服务端 `TASKS` 只按 owner UUID 索引。

本轮未找到可达序列。`applyWorkspace` 在 `operationBusy` 时拒绝（同 owner 已有任务时 `localTaskBusy` 为真），因此同一玩家的队列里不会有第二个操作任务。客户端在 `SUBMITTING` 阶段把常规输入全部阻断（`State.SUBMITTING` 第 147 行），玩家无法在此期间启动另一个操作任务。残余影响也有界：外来 `OPERATION_TASK` 结束后，阶段进入 `WAITING_FOR_RESULT`，40 tick 后仍会超时并保留草稿。

结论：不作为缺陷。若要彻底消除，需要让活动回包携带 transfer ID 或操作 ID，跟踪器按 ID 匹配，而不是按活动值匹配。

### SL-5：`cancelTask` 不发送工作区结果

`OperationManager.cancelTask` 第 652 至 658 行只移除任务并把恢复交给 `WorldHistoryManager`，不发送 `OperationWorkspaceResultPayload`。调用方是 `FastPlaceManager.quit`（第 236 至 244 行），入口是 `handleQuit`。客户端的 `QuitFastPlacePayload` 只从 `cancelActiveSession` 发出，而该函数在跟踪器等待期间不会被调用（`CancelInputSemantics` 先报告）。因此本轮未找到“跟踪器等待中任务被移除且无结果”的序列。若将来放开等待期的取消，这里会变成真缺口：客户端只能靠 40 tick 宽限期收尾。

## 已有保护与排除项

- 复制重复（已证实部分）：最终方块内容不再改变。`ReversibleBlockSnapshot.placeAt` 第 115 至 118 行在实时状态已等于目标时跳过 `setBlock`。`WorldChangeBatch.build` 第 325 至 341 行在全部 before 与 after 内容相同时返回 `Optional.empty()`，`WorldHistoryManager.pollPreparedOperation` 第 166 至 169 行据此直接进入 READY，该批次不写历史。
- 复制重复（未证明部分）：不能从最终方块相等推出整次重复提交无副作用。以下几项未界定：
  1. 方块实体目标在状态相等时仍会重建实体。`placeAt` 第 120 至 147 行在 `blockEntity` 非空时新建替换实体、调用 `setBlockEntity`、`setChanged`、`sendBlockUpdated`；`blockEntity` 为空时还会移除已有实体（第 125 至 127 行）。重复执行因此仍产生实体对象、方块更新与客户端回调，只是内容相同。
  2. 事务层始终记录过一次写入。`write` 第 326 行在 `placeAt` 之前调用 `transaction.recordBefore(before)`，因此即使每个位置都是空操作，`hasWrites()` 仍为真。该标志决定失败时的 `retryableWorkspaceFailure`（`OperationManager` 第 480 行），并决定成功路径是否进入历史提交（第 487 行）。
  3. 两次提交之间的世界变化未界定。此时 `before.matches`（第 315 行）失败，任务走 FAILED，随后按 `hasWrites()` 决定可重试性；若已有一处记录，客户端会收到不可重试并清空工作区。该序列是否存在需要运行期证据。
  结论：只有“最终方块内容不重复”是已证实的。重复执行的事件、实体回调、历史与恢复后果均未证明，标为“未证明重复写入后果”，不把全流程幂等当作已证实。
- 迟到回包不会写入新草稿：`accepts` 第 58 至 60 行要求跟踪器仍在等待且 transfer ID 相同。跟踪器一旦清空，旧回包无法触发任何写入或恢复动作。风险只在“丢弃后无人收尾”（SL-3），不在“误写”。
- 草稿误恢复：恢复需要服务端选择身份完全相等。`OperationDraftIdentity` 由模式、点位、棱柱数、偏移、外壳膨胀、选区最小/最大点组成，来源是服务端快照而不是文件。身份不同时 `restoreSuspendedOperationDraft` 第 52 至 61 行返回 false。本轮未找到误恢复序列。
- 提交等待中的取消被拒绝：`CancelInputSemantics.decide` 与 `decideEscape` 在 `workspaceSubmissionPending()` 为真时返回 `REPORT_SUBMISSION_PENDING`。Q、Escape、Gizmo 拖动（`beginWorkspaceGizmoDrag` 第 1660 至 1662 行）都被同一判据拦住。
- 超时后输入状态机回退：`expireWorkspaceRequest` 以 `EXPIRED` 调用 `completeSubmission`，`State.completeSubmission` 第 203 至 213 行对非 `SUCCEEDED` 事件返回来源状态，`transition` 第 124 至 133 行随即清空请求令牌。`ClientInputStateMachineTest.failedSubmissionReturnsToItsOwningPhaseDespiteAStaleSnapshot` 覆盖该行为。因此“超时后卡在 SUBMITTING”不成立。
- 分块传输超时：`TRANSFER_TIMEOUT_NANOS` 为 30 秒，客户端在同一个 tick 内发完全部分块，`FastPlaceNetwork.tick` 每服务端 tick 清理一次。首分块之后才会开始计时，实际竞争窗口极小。本轮不作为缺陷。
- 回执作用域：工作区回包作用域按玩家 ID 与 transfer ID 的组合键存储（`workspaceCallbackScope` 第 577 至 580 行），与 `2026-09-16-transfer-isolation.md` 的修复一致。

## 架构修复方案

1. 统一收尾入口。为一次提交定义唯一的结束函数，参数是 transfer ID 与结果。它必须同时清跟踪器、解锁工作区、重建选区与遮罩、结束输入层的提交令牌，并对迟到回包给出降级处理（至少一条信息）。SL-1、SL-3 与架构方案第 4 条都收敛到该入口。
2. 超时语义分层。`TASK_ACTIVE` 目前没有上限。建议为“任务已入队但从未开始”增加独立上限，或让服务端在入队被写入闸门挡住时立即回传一个不可重试的拒绝，而不是静默留在 `TASKS`。
3. 结果归因。`OperationWorkspaceResultPayload` 增加原因字段（忙碌、冲突、内存、写入闸门）。客户端按原因选择文案。当前唯一的冲突文案不能覆盖占用类拒绝。
4. 草稿落盘与身份解耦。`suspendOperationDraft` 应在实时草稿非空时总是写盘，身份只在恢复时校验。当前实现把“身份缺失”当成“不必保存”，SL-3 因此丢草稿。
5. 活动关联。`ActivityStatePayload` 携带操作 ID 或 transfer ID，使跟踪器可以按 ID 匹配，而不是按活动值匹配。该项同时消除 SL-4 的理论缺口。

以上五项都跨 `client/operation`、`client/input` 与服务端。本轮不改代码，等待主线程决定归属。

## 可复现测试条件

静态可复现（无需运行游戏）：

1. SL-1：断言 `WorkspaceSubmissionTracker` 在收到 `OPERATION_TASK` 后 `tick()` 永远返回 false，且 `FastPlaceActivity.OPERATION_TASK` 的来源是 `TASKS.containsKey`。测试文件建议放在 `src/test/java/io/github/fastformer/client/operation/controller/`。现有 `WorkspaceSubmissionTrackerTest` 的 `activeServerTaskDisablesTheStartTimeout` 已经覆盖前一半。
2. SL-2：驱动 `ServerInputDispatcher.applyWorkspace` 在 `WorldWriteCoordinator.busy` 为真时返回 false，断言回包 `retryable` 为 true 且 `failedTargetPositions` 为空。空失败列表与“方块冲突”文案的组合就是缺陷本身。
3. SL-3：用 `ClientOperationControllerTest` 的模式驱动 `onDisconnected`，前置条件是工作区非空、`serverPreview` 非活动。断言 `suspendedOperationDraftData()` 为 null 且未触发文件写入。该测试需要 `sun.misc.Unsafe` 快照助手，与既有测试一致。

实机复现（需要运行客户端与服务端）：

4. SL-1：在开发环境让世界任务调度抛出一次异常（或在测试构建中直接调用 `blockNewWrites`），然后提交工作区。观察：动作栏停在 `operation_scanning`，Q 与 Escape 只显示 `operation_submit_pending`，任务永不结束。
5. SL-2：两名玩家在同一维度，一人执行大范围放置任务，另一人提交工作区。观察：动作栏为“已有任务正在执行”，聊天提示为“部分方块发生冲突”。
6. SL-3：提交后立即断线并重连。观察：世界已写入，客户端无成功提示，草稿不可恢复。

## 未完成与实机缺口

- 本轮未运行 Gradle。全部结论为静态证据，没有任何测试或实机确认。
- SL-1 的第一环是调度器抛出异常。未做故障注入，未确认该状态在真实运行中出现的频率。
- `WorldWriteCoordinator.busy` 的真实占用时长与并发玩家数未测量。
- 迟到回包在真实网络抖动下的到达时间未测量。SL-3 只证明“回包被丢弃且没有收尾”，不证明抖动一定触发。
- 未检查 `docs/plan/bugs/2026-09-16-server-entry-audit.md` 是否已登记 `TASKS` 跨登出的存留。若已登记，本条只补充客户端侧后果。

## 移交 BUG-K 的相邻风险（本轮只登记，不下结论）

旋转状态与旋转预算属于独立任务，本轮不处理。已核实的事实登记如下，供 BUG-K 直接使用。

边界声明：本轮不能声称已证明“旋转桥接导致超额实际写入”。`OperationWorkspaceValidator.validate` 第 82 至 84 行有后置的 `writes.size() > maxBlocks` 拒绝，最大放置量因此不会被绕过。本轮未运行 Gradle，没有任何测得数值。可证实的只有前置预算低估。

1. 成本模型没有旋转项。`WorkspaceGeometryCost.of` 第 60 至 81 行只按缩放与重复计算：`writtenUpperBound` 取扫描体积或源格数，没有桥接项。`VoxelRotation.rotate` 第 126 至 137 行的 `bridgeFaceNeighbours` 会插入额外格。现成断言给出最小案例：`VoxelRotationTest.nonOrthogonalRotationBridgesFaceAdjacentSourceVoxels`，源 2 格、Y 轴 45 度，输出 3 格。该案例下 `scanVolume` 为 0，`writtenUpperBound` 等于源格数 2，`projectedUpperBound` 为 2，实际 3。
2. 内存准入用投影值。`validate` 第 55 至 61 行在展开之前完成 `WorkspaceGeometryBudget.assess` 与 `WorldOperationMemory.snapshotAdmission(budget.plannedUpperBound(), 0L)`。预订量来自 `projectedUpperBound`，不是实际输出。
3. 展开先于计数。第 74 行先完整执行 `resolveValues` 并写入 `writes`，第 82 行才比较大小。峰值内存发生在拒绝之前，后置拒绝只能挡住结果，不能挡住过程。
4. 渲染侧没有后置复核。`WorkspacePreviewComposer.canResolveForRendering` 第 422 至 428 行调用 `WorkspaceGeometryBudget.fits`，后者第 29 至 31 行只比较 `projectedUpperBound`。`resolveForRendering` 第 416 至 420 行在守卫之后直接展开，结果交给唯一生产消费者 `WorkspaceInteractionResolver` 第 374 行，中间没有数量复核。
5. 低估幅度有限。桥接格只来自面相邻源体素对，每对每轴最多加 2 格，最多 3 个轴各一次，因此额外格数不超过 18 倍源格数。这是有界的小倍数，不是无界膨胀。该上限由静态推导得出，未测量。

### 外框与点映射（已核实，待纳入统一修复）

以下三项与上节同属旋转模块，只做静态核对。修复方向是集中成一处 frame 点映射，避免继续分散补丁。

1. 平移与旋转顺序相反。`WorkspaceSelectionBounds.rotatedCuboid` 第 201 行先调用 `axisAligned(source, transform.withRotation(Vec3.ZERO), frame)`，该函数第 180 至 182 行已经加上 `transform.translation()`；随后第 221 至 223 行绕 `frame.rotationSteps()` 的未平移 pivot 旋转这八个角。`WorkspaceTransform.withRotation` 第 64 至 66 行保留 translation，因此平移确实已经进入 `base`。合成器的顺序是先旋转后平移：`WorkspacePreviewComposer.resolveCore` 第 314 行旋转，第 317 至 322 行才平移。两者相差 `R(t) - t`，只要旋转与平移都非零就不相等。缺一条“旋转加非零平移”的外框回归。
2. 取整规则不一致。`WorkspaceSelectionBounds.scaledExtent` 第 185 至 188 行用 `Math.rint`（半值取偶），合成器 `scaleValues` 第 364 至 366 行用 `Math.round`（半值向上）。`WorkspaceGeometryCost.scaledWidth` 第 94 至 101 行明确按 `Math.round` 对齐合成器。恰为半值时外框与体素相差一格。该项是否可达取决于 Gizmo 数值能否产生精确半值，本轮未查。
3. 空气偏置选区被重新居中。`axisAligned` 第 160 行在有 `scaleAnchor` 时把整个选区框中心替换成占用方块的中心，选区框相对占用中心的偏移被丢弃。该行为与合成器一致：`scaleValues` 第 357 至 370 行本来就用占用包围盒建立采样网格，所以外框跟随占用中心不会与体素漂移。但它改变了“外框等于用户画出的选区”的含义，而 `RepeatStrideSemantics` 第 55 行与第 66 行又用 `WorkspaceSelectionBounds.resolve` 推导步距。这是设计取舍，需要先对照既有设计说明再决定，不能用一条重复当前实现的测试代替决策。

状态：本轮已停止，未修改任何生产文件，未运行 Gradle，未新增测试，不再展开调查。等待主线程复核后决定 SL-1、SL-3 与旋转模块的修复归属。
