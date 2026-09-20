# 提交重连设计：草稿保留与结果对账

状态：只读调查。未改生产代码，未跑 Gradle，未新增测试。

范围：SL-3“断线提交丢草稿”。回答四个问题，给出可落地方案。

## 结论摘要

| 问题 | 答案 |
|---|---|
| pending 时草稿身份是否已在本地记录 | 没有。只记录 transfer ID |
| 断线各入口的实际顺序 | 先清输入会话，再挂起草稿 |
| 纯客户端粘贴的身份是否另有定义 | 没有。身份只能来自服务端预览 |
| 重连恢复会不会重复执行 | 恢复本身不会。它只写入工作区，不重提 |

核心问题不是“草稿没保存”。是客户端丢失了“这次提交的结果”。草稿与结果必须分开处理。

## 证据一：pending 时本地只有 transfer ID

`WorkspaceSubmissionTracker` 的字段是 `phase`、`transferId`、`waitTicks`。没有身份字段。

`ClientOperationController.submitWorkspace` 第 895 至 897 行只做三件事：`WORKSPACE_SUBMISSION.begin(transferId)`、`setLocked(true)`、`send()`。提交时没有把当时的选区身份抄到本地。

因此断线时只能现取身份，见下条。

## 证据二：断线时身份取值必然为空

`ClientOperationController.onDisconnected` 第 996 至 1016 行：

1. 第 1001 行取身份：`OperationDraftIdentity.from(serverPreview)`。
2. 第 1012 行才把 `serverPreview` 置为非活动。

顺序本身没问题。问题是 `serverPreview` 在此之前已经被服务端同步成非活动：`OperationManager.applyWorkspace` 第 423 行在入队成功后调用 `cancel(player)`，服务端随即下发非活动预览。

`OperationDraftIdentity.from` 第 33 至 36 行对非活动载荷返回 null。

于是链条断开：

| 位置 | 行为 |
|---|---|
| `ClientPlayerSession.suspendOperationDraft` 第 39 至 47 行 | 身份为 null 且实时草稿非空时，把 `suspendedOperationDraft` 置为 null |
| `ClientSessionManager.suspendCurrentDraft` 第 113 至 114 行 | 取不到草稿数据就直接返回，不写盘 |

审计文档第 68 至 69 行描述的就是这一步。本轮确认成立，并补上“服务端取消会话先于断线发生”这一前置条件。

## 证据三：断线入口顺序

`LevelEvent.Unload` → `FastPlaceClientPreviewCore.endWorldSession` 第 3280 至 3284 行 → `PreviewSessionLifecycle.endWorldSession` 第 27 至 35 行。

顺序是：`endInputSession`、`clearSourceMask`、`resetPreviewSession`、`disconnectOperation`、`clearInteractionCache`、`clearFeedback`、`clearWorldRenderState`。

输入会话先结束。`FastPlaceClientInput.endWorldSession` 第 115 至 124 行调用 `INPUT_STATE.reset()` 第 116 行。因此到 `disconnectOperation` 时，提交令牌已经清空，挂起流程无法再从输入层得知“刚刚有一次提交”。

换维度走另一条路：`ClientSessionManager.activateScope` 第 77 至 85 行在连接相同、维度不同时调用 `detachEnvironment`，只清实时手势，不挂起草稿。该路径不写盘，也不丢盘。

## 证据四：纯客户端粘贴没有身份

`OperationDraftIdentity.from` 第 33 至 36 行的唯一输入是 `OperationPreviewPayload`。字段包括 `minOffset`、`maxOffset`、`hullInflation`，这些只存在于服务端载荷。

本地选区草稿 `ClientSelectionSession.DraftState` 第 222 至 228 行只有模式、点位、棱柱基数、最小点、最大点。它没有偏移与外壳膨胀。

纯客户端工作区（例如粘贴后提交）在提交时服务端可能根本没有操作会话，`serverPreview` 非活动，身份恒为 null。这条路径的草稿在任何断线顺序下都不会落盘。

结论：本地选择数据不足以重建服务端身份。不能靠“本地算一个身份”来绕过。

## 证据五：服务端任务在断线后继续运行，结果无人接收

`OperationManager.TASKS` 第 35 行按玩家 UUID 索引。`tickWorld` 第 428 至 438 行遍历全部 owner，不检查在线状态。`detachPlayer`（`FastPlaceManager` 第 791 至 805 行）不删除任务。

任务完成后，`OperationManager` 第 511 至 517 行发送结果，参数是 `context.onlinePlayer()`。

`WorldTaskContext.onlinePlayer()` 第 56 至 58 行在玩家离线时返回 null。`FastPlaceNetwork.sendWorkspaceResult` 第 563 至 565 行对 null 玩家直接返回。

因此世界写入真实发生，客户端永远收不到 accepted。审计文档第 70 行描述成立。

附带的两个事实：

- 动作栏文字有离线补偿。`WorldTaskContext.actionBar` 第 60 至 67 行在玩家离线时改走 `WorldTaskFeature.deferActionBar`，登录时由 `attachPlayer` 第 68 至 80 行重放。结果包没有对应的补偿。
- `WORKSPACE_CALLBACK_SCOPES` 有残留。`sendWorkspaceResult` 第 563 行先判空返回，第 577 至 580 行的 `remove` 不会执行，条目按 (UUID, transferId) 留在表里。

## 证据六：迟到结果即使到达也会被拒

服务端 `PlayerPreviewSync.beginClientSession` 第 70 至 74 行在每次登录生成新的会话 ID。客户端 `acceptsPreviewCallback` 第 197 至 202 行在连接对象变化时用新会话的第一个快照刷新 `callbackSessionId`。

`matchesOperationCallbackScope` 第 241 至 246 行要求 `sessionId` 完全相等。旧结果携带旧会话 ID，因此被拒。

`ClientPayloadDispatcher` 第 84 至 88 行在进入控制器前就把它挡掉。

这解释了审计文档第 96 行的结论：风险不在“误写”，在“丢弃后无人收尾”。

## 证据七：恢复不重提

`ClientOperationController.confirmReconnectRestore` 第 316 至 326 行做三件事：清 `pendingReconnectPreview`、`restoreCurrentDraft`、`refreshSourceMask`、`synchronize(pending)`。

没有任何 submit 调用。`ClientPlayerSession.restoreSuspendedOperationDraft` 第 52 至 61 行只把草稿写回工作区与选择会话。

所以“重连自动重复执行”不成立。真正的重复风险是：玩家看到草稿回来，手动再按一次提交。此时上一次任务可能已完成。

## 架构方案

草稿保留与结果对账是两件事，分两条线做。

### A 线：草稿保留（本地，不依赖服务端）

1. 提交时抄下身份。`submitWorkspace` 在 `send()` 之前把当时的 `serverPreview` 身份写入跟踪器。此时预览仍活动。
2. 跟踪器增加 `identity` 字段，`begin(transferId, identity)` 一并保存。
3. `onDisconnected` 改用跟踪器里的身份，不再现取 `serverPreview`。
4. 本地工作区在提交时记来源标记：`SERVER_SELECTION` 或 `LOCAL_ONLY`。纯客户端粘贴标记为 `LOCAL_ONLY`。
5. `OperationDraftIdentity` 增加该来源标记，或在新记录类型里保存。`LOCAL_ONLY` 的草稿不做服务端身份校验，改为按连接与维度作用域恢复。
6. 校验规则保持：`SERVER_SELECTION` 草稿仍要求身份完全相等。本地数据无法重建服务端身份这一事实不变。

效果：断线后草稿一定落盘。恢复时不再依赖已经不存在的服务端预览。

### B 线：结果对账（服务端记账，客户端查询）

草稿能留住内容，但留不住“上一次提交成功没有”。这一半必须由服务端回答。

7. 服务端加一张有界结果账本，键是 (玩家 UUID, transferId)，值是接受与否、可重试性与时间。
8. 发送结果前先写账本。玩家离线时写入照旧进行。
9. 登录时客户端对每条本地提交回执发一次对账查询。
10. 服务端按 transferId 回答三种结果：已应用、失败可重试、未知。
11. 账本按玩家与时间设上限，旧条目淘汰，避免无界增长。

对账查询在 `onPlayerLogin` 之后、预览同步之前发出最合适，因为此时客户端已知道本次连接的新会话 ID。

### C 线：客户端如何用对账结果

| 服务端回答 | 客户端动作 |
|---|---|
| 已应用 | 清草稿，显示 `operation_submit_success`，重建源遮罩 |
| 失败可重试 | 保留草稿，显示失败原因，允许手动重试 |
| 未知 | 保留草稿，但**不**显示成功，也**不**自动重提 |

“未知”必须保留。它是账本淘汰、跨存档或异常退出后的真实状态。把它当成失败会导致玩家重复提交已经写入的世界；当成成功会导致玩家丢失未写入的内容。

### D 线：不自动重提

恢复流程保持现状，只写入工作区。任何重提都由玩家按键触发。

若要降低“玩家重复提交已完成任务”的风险，可在对账结果为“未知”时于 HUD 显示一次提示，说明上一次提交的结果无法确认。这是提示，不是自动动作。

## 落地顺序

| 顺序 | 改动 | 理由 |
|---|---|---|
| 1 | A 线第 1 至 3 项 | 最小改动，立刻止住丢草稿 |
| 2 | B 线第 7 至 8 项 | 服务端先记账，成本低，独立可测 |
| 3 | B 线第 9 至 10 项 | 打通对账链路 |
| 4 | A 线第 4 至 5 项 | 覆盖纯客户端粘贴，改动面较大 |
| 5 | C 线与 D 线提示 | 收尾体验 |

第 1 步单独就能修掉 SL-3 的主症状。第 4 步涉及草稿文件格式，需要版本号提升与兼容读取。

## 测试建议

1. A 线：工作区非空、`serverPreview` 活动时提交，再断线。断言草稿数据非 null 且发生一次文件写入。
2. A 线回归：保持现状测试，断言 `serverPreview` 非活动时不写盘。该测试记录的是修复前的行为，第 1 步之后应改为断言写盘。
3. A 线：`LOCAL_ONLY` 草稿在身份为 null 时仍恢复。
4. B 线：玩家离线时任务完成。断言账本含该 transferId 且结果为已应用。
5. C 线：对账返回“未知”时不出现成功提示，且草稿保留。
6. C 线：对账返回“已应用”时草稿清空且出现成功提示。
7. 账本上限：写入超过上限后最旧条目被淘汰。
8. 会话 ID：旧会话的结果包仍被 `matchesOperationCallbackScope` 拒绝。

第 1 至 3 项参照 `ClientOperationControllerTest` 的现有模式。第 4 至 7 项需要服务端侧测试。

## 未证实与边界

- 未测量断线到结果产生的实际时间窗口。本方案不依赖该窗口。
- 账本淘汰策略的具体上限需要结合存档规模决定，本轮未定。
- “未知”状态下玩家重复提交的实际后果仍未界定。审计文档第 91 至 95 行已列出未证明项：方块实体重建、事务记录、历史与恢复后果。本方案不假设重复提交幂等。
- 未验证 `restoreDraftState` 对含 `pendingDelete` 部件的恢复是否完整。该字段在 `ClientSelectionPart` 内，本轮未逐字段核对。
- `WORKSPACE_CALLBACK_SCOPES` 残留条目未定量。它按玩家与 transferId 增长，玩家重连多次会累积。建议在 A 线或 B 线中一并清理。
- 本方案不新增语言键。C 线提示复用现有键；若确需新键，必须同时加入 `en_us.json`、`zh_cn.json` 与 `HoverTextLanguageTest.REQUIRED_KEYS`。
