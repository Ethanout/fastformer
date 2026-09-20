# 2026-09-16 未完成问题证据清单

只读当前实现。最多 15 项。按用户五类归类，优先架构与交互。

分类：

1. 程序逻辑
2. 交互体验
3. 提示缺失或误导
4. 设计与实现冲突
5. 其他：数据完整性、资源成本、测试覆盖

本轮未改生产、测试或其他报告。未运行 Gradle。取消回执修复已验收，不列入。回执结算、源遮罩 Mixin、IN-2/IN-4/IN-7、缩放外框 `Math.round`、取消账本结算均已落地，不列入。

当前预算任务：`CompositionBudget` / `CompositionCounter` 已存在。`WorkspacePreviewComposer.resolveCore` 仍无界展开。分组任务：repeat-layer 栈未落地。异步预览任务：`PendingGhostMeshCache` 失败后不重试。下表用“覆盖”标记这三项是否吃掉该缺口。

## 优先：架构与交互

| ID | 类 | 项 | 当前入口 | 缺失行为 | 覆盖 | 需要的证据 |
|---|---|---|---|---|---|---|
| R1 | 4 | G1 混合点阵公共堆叠 | `WorkspaceTransform` 只有一层 `(repeats, repeatStride)`。`ClientOperationController.updateTransformGesture` 第 810 至 845 行对每个部件改写同一层。 | 单部件先堆叠后再公共堆叠时，已放置副本被改写。A 的 5..9 与 B 的 12..13 消失。单层等差无法同时保留旧偏移与整体步距。 | 分组任务独占。预算任务不修模型。 | 启用 `GroupRepeatTest.placedCopiesSurviveACommonSecondStackAfterPerPartFirstStacks`。协议变更后加编解码测试。实机：两部件各自堆叠一次，再共同拖 SCALE。 |
| R2 | 1,5 | 交互路径无界展开 | `WorkspaceContentPreparer.clipboardParts` 第 19 行、`updateTransformGesture` 第 802 至 807 行与第 854 行都调用无守卫的 `WorkspacePreviewComposer.resolve`。`resolveCore` 第 323 行仍 `repetitions(Integer.MAX_VALUE)`。 | `CompositionBudget.INTERACTION` 未接到这些入口。Ctrl+C 与多部件手势每步完整展开。超限会卡主线程，空 map 还会伪装成合法空结果。 | 预算任务覆盖接线。分组任务不覆盖。 | 单元：超限复制必须失败并保留旧剪贴板。手势步不得分配完整 129³ 列表。实机：大重复组拖公共轴，记录帧耗时。 |
| R3 | 4 | 服务端先展开后计数 | `OperationWorkspaceValidator.validate` 第 74 至 84 行对每个部件 `resolveSnapshots`，然后才比较 `writes.size()`。第 55 行预算不含旋转桥接增格。 | 拒绝发生在峰值分配之后。测试 `doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot` 仍要求不读服务端源。 | 预算任务部分覆盖（先估后分配）。不覆盖实体内容身份。 | 单元：超限计划在展开前拒绝。内存探针比较峰值。不要把空 map 当失败。 |
| R4 | 2,4 | 锁定部件无法单独重选 | `WorkspaceInteractionResolver.resolveGizmo` 第 84 至 86 行：LOCKED 且未选中则跳过。`resolvePart` / `resolveFace` 同样跳过 LOCKED。 | 变换后的部件失去选择后，标签、边框、手柄都不能单独点回。Ctrl+A 仍可用。与 BUG-07“锁定只禁面编辑”冲突。 | 不在预算/分组/异步预览范围内。 | 点击测试：移动 A，再点 B，再点 A 的边框或手柄。断言 A 重新成为选择目标。 |
| R5 | 1,2 | 网格构建失败后不重试 | `PendingGhostMeshCache.publishCompletedMesh` 第 82 至 87 行：异常后写空网格并清 `future`。相同输入第 35 行命中缓存，不再提交。 | 暂时资源失败后，相同候选永久空。没有失败状态，也没有退避。`PreviewAsyncPolicy.meshSynchronously` 只决定同步或异步，不修失败状态。 | 异步预览任务独占。 | 单元：注入一次失败后再请求同一集合，必须启动第二次构建。实机：大球确认进入调整后，预览不得永久消失。 |
| R6 | 1,4 | 世界箱子移动用客户端空物品 | `ClientOperationController` 捕获走客户端 `saveWithFullMetadata`。`OperationWorkspaceValidator.validate` 不调用 live 回调。WORLD 部件只贡献清除坐标，目标内容沿用客户端快照。 | 普通区块同步的箱子 `getUpdateTag` 为空。移动后目标箱子没有服务端物品。现有测试锁定“不读世界”。 | 不在三项任务范围内。 | GameTest：服务端箱内有物品，客户端未开箱，移动到空地。比较源与目标物品。撤回必须还原。 |
| R7 | 1,2 | 提交拒绝文案覆盖多种原因 | `ClientOperationController.applyWorkspaceResult` 可重试分支第 1190 行固定 `operation_submit_rejected`（冲突文案）。服务端忙走 `operation_task_running` 动作栏。闸门关闭时 `canOperate` 拦截，客户端仍只见冲突文案。 | 占用、闸门、内存与真实方块冲突共用一句。玩家按提示改方块无效。 | 不在三项任务范围内。取消结算已修服务端终态，不修归因。 | 定向：忙维度、关闸门、真实冲突三条路径必须三条不同提示。实机对照动作栏与 HUD。 |
| R8 | 2,3 | 提交等待 HUD 与取消提示 | `WorkspaceSubmissionHud` 已落地。等待期 Q/Esc 返回 `REPORT_SUBMISSION_PENDING`。活动行在等待时不再追加取消键。 | 文档仍标未验收。纯客户端粘贴、服务端任务已开始、结果迟到三条路径的画面未测。 | 不在三项任务范围内。 | 实机：提交后立刻看 HUD。任务开始前后各拍一帧。确认取消键提示不出现，等待行出现。 |
| R9 | 1,4 | 历史分页失败永久 busy | `WorldHistoryManager.OwnerState.busy` 第 1485 行含 `historyLoadFailed`。`attachHistoryPage` 第 1074 行失败后置位。`trimToSetting` 第 821 行只在 `history == null && historyLoad == null` 时清标志。分页失败时 history 已存在。 | 之后 `request`、撤回、`interactionBlocked` 一直拒绝，直到服务器重启。提示没有重试动作。 | 不在三项任务范围内。 | GameTest：已有 history 后注入分页失败。断言 busy 可清除或可重试。实机：超缓存容量批量撤回中途读失败。 |
| R10 | 2,4 | AABB 点阶段客户端不拥有输入 | 发送首点后、服务端 `operationActive` 到达前，`observedInputState()` 仍为 IDLE。窗口约 1 至 2 tick。 | 空手右键被吞。手持方块走原版放置。`InteractionContext.selectionOwnsPointer` 已预留接缝，点阶段未接入。 | 不在三项任务范围内。 | 集成：发出首点后立即右键。断言第二点被模组认领，或明确拒绝并提示。不要用计时掩盖。 |

## 其余未完成项

| ID | 类 | 项 | 当前入口 | 缺失行为 | 覆盖 | 需要的证据 |
|---|---|---|---|---|---|---|
| R11 | 5 | 客户端撤回栈有界但仍是引用计数 | `ClientOperationEventStack` 默认 512 节点、65536 权重。`Retention.UNMEASURED` 是下界 1。 | 不能证明 JVM 字节。超大快照仍可能挤掉旧节点。设置未接到该预算。 | 不在三项任务范围内。 | 单元：声明权重的快照淘汰顺序。实机：连续编辑大部件后 Ctrl+Z 仍能撤最近一步。 |
| R12 | 2 | 源遮罩异步编译窗口 | `SourceMaskRenderFilter` 按区块绑定快照。正在编译的网格看不到新发布。`BlockEntityRenderDispatcherMixin` 只挡实体。 | 遮罩变化后，已在飞的区块网格可能一帧画出旧源方块。文档明确未验收。 | 不在三项任务范围内。异步预览任务不覆盖遮罩上传。 | 实机：开始移动后立即转相机。源方块不得闪现。取消后碰撞仍是服务端方块。 |
| R13 | 2,5 | 双层半透明预览排序 | `FastPlaceClientPreviewCore` 在 confirmed 与 pending 同时存在时走双层分支。缓存路径按相机距离排序，双层按网格顺序。 | 层数切换时透明面顺序变化，可能闪动。独立 FaceBuffer 方案已撤回。 | 异步预览任务相关画面，但不修排序。 | 实机：准星靠近可交互方块使 `worldPreviewOpacity < 1`，同时有 confirmed 与 pending。转相机检查跨层遮挡。 |
| R14 | 1,2 | 大球确认到调整卡死 | `PreviewAsyncPolicy.useLightweightShell(100_001)`。确认进入调整时工作量从生成切到网格。`PendingGhostMeshCache` 同步上限 16_000。 | TODO BUG-04。失败不重试（R5）会放大本项。预算接线（R2）不覆盖 building 外壳。 | 异步预览任务覆盖调度。预算任务不覆盖 building。 | 实机：半径约 20 格球体，确认后进入调整。记录输入到首帧、帧耗时、是否卡死。 |
| R15 | 2 | 旋转后 MOVE 坐标空间 | TODO BUG-11：按住 Alt 后 Gizmo 消失。旋转后 MOVE 必须用世界轴，SCALE 手柄才跟局部轴。 | 旋转状态测试已过 GameTest。Alt 消失与坐标系产品语义仍待实机。分组任务不改 Gizmo 空间。 | 不在三项任务范围内。 | 实机：旋转 90 度后拖 MOVE。方块沿世界轴。SCALE 手柄随局部轴。按 Q 退出 Alt 后首方块预览立刻恢复。 |

## 剔除（文档未同步，当前实现已修）

- SL-5 取消不结算账本：`settleCancelledTask` 已落地，GameTest 79 项含 4 项取消回执。
- RS-1 至 RS-3 终态先落盘且清理不可重试：`settleAppliedSubmission` 与 `needsCleanup` 已存在。
- BUG-X 客户端 `setBlock` 遮罩：改为 Mixin 渲染层。
- IN-2 界面手势残留、IN-4 Q 丢弃、IN-7 Esc：`InputContextBoundary`、`consumesVanillaDrop`、`onScreenOpening` 已存在。
- 缩放外框 `Math.rint`：`WorkspaceSelectionBounds.scaledExtent` 现为 `Math.round`。
- 提交等待 HUD 代码缺口：`WorkspaceSubmissionHud` 已接线。缺的是实机，见 R8。
- 不可重试失败仍显示“选区已保留”：已改 `operation_submit_discarded`。可重试冲突文案仍在，见 R7。

## 建议下一包

先做 R1 分组模型，或先做 R2 预算接线。两者都改 `updateTransformGesture`，不要并行改同一方法。R5 与 R14 同属异步预览，可并行于分组。R6 与 R9 是数据与锁死，不依赖预览。
