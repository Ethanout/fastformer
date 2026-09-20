# 2026-09-16 回执结算统一修复实现

依据：`docs/plan/bugs/2026-09-16-receipt-settlement-audit.md` 的只读审计，加上主代理的修正意见。

本轮实现统一的可重试结算边界。未运行 Gradle。未创建线程。未改 `OperationManager`、网络层与遮罩实现。

## 改动的文件

生产代码：

| 文件 | 改动 |
|---|---|
| `client/session/OperationDraftSettlement.java` | 新增。两个所有权的三值判定、合并、持久化规则、可注入 IO |
| `client/session/OperationSubmissionReceipt.java` | 新增 `cleanupPending` 组件与两个派生方法 |
| `client/session/OperationSubmissionReceiptCodec.java` | 读写 `CleanupPending`，旧文件按已应用迁移 |

| 文件 | 改动 |
|---|---|
| `client/session/OperationSubmissionReceiptStore.java` | 新增 `needingCleanup()` 与 `confirmCleanup()` |
| `client/session/ClientSessionManager.java` | 新增结算入口、登录重放、探针；删除两个连带删除方法；恢复被拒时保留文件 |

结果分支、语言与测试：

| 文件 | 改动 |
|---|---|
| `client/operation/controller/ClientOperationController.java` | 回执分支与在线结果分支改用结算；按遮罩代理要求改为 `SOURCE_MASK.complete(Map.of())` |
| `assets/fastformer/lang/en_us.json`、`zh_cn.json` | 新增 2 个语言键 |

| 文件 | 改动 |
|---|---|
| `src/test/.../OperationDraftSettlementTest.java` | 新增。判定矩阵与真实文件重放 |
| `src/test/.../OperationSubmissionReceiptTest.java` | 新增持久化与旧格式迁移用例 |
| `src/test/.../HoverTextLanguageTest.java` | 登记 2 个新键 |

## 结算顺序

一次已应用结果按此顺序处理：

1. 把已应用结果写入回执，并置 `cleanupPending = true`，然后落盘。
2. 分别清除 staged 与 durable 两份副本，各自按自己的所有权判定。
3. 两份都确认后，把 `cleanupPending` 置为 false 并落盘。

任一步停止，磁盘上的回执都仍然记录着待清理，所以下一次边界能接着做完。客户端不等待服务端再次回答，因为账本可能重启并答 `UNKNOWN`。

「已落盘」由存储的 dirty 标记判断，而不是由写入调用是否返回。写入失败后 dirty 保持为真，下一次调用重写同一份内容。

崩溃点覆盖：

| 停止位置 | 磁盘状态 | 下次启动的行为 |
|---|---|---|
| 第 1 步之后 | 已应用 + 待清理 | 登录重放清除副本 |
| 第 2 步之后 | 已应用 + 待清理 | 文件已不存在，重放判为已确认并收尾 |
| 第 3 步落盘失败 | 已应用 + 待清理 | 重放重试一次，删除与保存都幂等 |

## 三值所有权

`OperationDraftSettlement.Ownership` 有三个值：`ABSENT`、`REMOVED`、`UNCONFIRMED`。

两个值不够，因为「没有副本」与「副本无法确认」必须分开。前者是已确认的工作，后者必须保持待清理。

| 输入 | 结果 |
|---|---|
| 无 staged 草稿 | `ABSENT` |
| staged 属于本次提交 | `REMOVED`（清内存不会失败） |
| staged 属于更新的提交 | `ABSENT`，不动它 |

durable 的判定：

| 输入 | 结果 |
|---|---|
| 文件不存在 | `ABSENT` |
| 文件属于本次提交，删除成功 | `REMOVED` |
| 文件属于本次提交，删除失败 | `UNCONFIRMED` |

| 输入 | 结果 |
|---|---|
| 文件属于更新的提交 | `ABSENT`，不动它 |
| 文件读取失败 | `UNCONFIRMED`，不删 |
| 文件未记录提交编号（旧格式） | `UNCONFIRMED`，不删 |

staged 与 durable 各自判定，互不连带。旧提交的结果不会删除新提交的副本。

## 对各项审计结论的处理

结果正确性：

- RS-1：已应用结果与待清理标记一起落盘。登录边界在读取草稿文件之前重放清理，因此已应用的草稿不会再次进入编辑并再次提交。判等早退改为「判等且无待清理」才早退，重复的应用回答仍会结算。
- RS-2：删除入口按所有权命名，任一方命中不再连带另一方。
- RS-3：删除失败返回 `UNCONFIRMED`，界面不显示提交成功。

状态与路径：

- RS-4：恢复被拒时不再删除文件，改为记录 `RETRYABLE_FAILURE` 并提示。该状态在下次进入同一环境时允许重读一次。
- RS-5：探针区分「文件不存在」与「文件不可读」。
- RS-6：不是缺陷。在线路径有 `WORKSPACE_SUBMISSION.accepts` 与工作区锁定作为保证，所以在线成功后清空工作区保持不变。重连回执不清未知的 live。

## 旧格式迁移

旧文件没有 `CleanupPending` 键。旧写入者在尽力而为的路径上删除副本，可能没有删掉。

规则：已应用且缺少该键的记录按待清理处理，清除一次。其他结果不产生待清理。

为了让规则无歧义，已应用的记录总是写出该键，取值为 true 或 false。缺少该键因此只有一个含义：旧文件。

## 补充：回执读取未知与结算无证据边界

本节与上文的服务端准入修复分开。范围是客户端回执读取失败时的两个边界。

### 可达性核对

沿真实调用链确认三条结论。

| 问题 | 结论 |
|---|---|
| `attemptDraftLoad` 能在回执不可读时运行吗 | 能。`observePlayer` 先调用 `replayPendingCleanups`，它会经 `receiptStore` 缓存不可读状态，随后 `loadDraftOnce` 进入 `attemptDraftLoad` |
| 控制器后续恢复另有阻拦吗 | 没有。恢复只校验草稿身份与服务端选区，不查回执，草稿会进入可编辑可再提交状态 |
| `settleAppliedSubmission` 会在无回执时运行吗 | 会。回执存储不可读时 `find` 为空，`existing` 为 null |

### 回执不可读不等于没有回执

原实现把二者都当作 `false`，于是带 `SubmissionId` 的草稿被直接 staged。回执文件可能包含该提交的已应用结果，因此这个等价关系不成立。

`receiptVerdict(key, transferId, storeOverride)` 返回三值：

| 值 | 含义 |
|---|---|
| `APPLIED` | 回执明确记载已应用 |
| `NOT_APPLIED` | 回执可读，且没有该提交的已应用结果 |
| `UNKNOWN` | 回执文件不可读，客户端无法证明任何一种 |

`attemptDraftLoad` 在结果不是 `NOT_APPLIED` 时拒绝装载草稿，保留文件并提示。已应用用既有键，无法核对用新键 `operation_draft_receipt_unknown_not_restored`。

草稿未记录提交编号时结果恒为 `NOT_APPLIED`，因此旧格式草稿不受影响。

### 回执读取重试

原实现把不可读存储永久缓存，没有重试入口。

- 读取失败记录 `RETRYABLE_FAILURE`，不再记 `CORRUPT`，因为失败可能是暂时性的。
- 版本不匹配记录 `VERSION_INCOMPATIBLE`，因为只有更新的客户端能读。
- 重新进入同一环境时清除可重试失败并丢弃缓存的不可读存储，下一次使用会重新读取。
- `retryCurrentReceiptLoad()` 是显式重试入口，与草稿侧的 `retryCurrentDraftLoad()` 对应。

### 结算不把无证据当已落盘

`settleAppliedSubmission` 在存储只读时返回 `recordSaved = false`。副本仍然清除，因为清除按提交编号判定，不依赖回执；但客户端不得声称已记录。

只读存储同时会给出自身的读取失败提示，因此玩家看到的是「回执文件读不了」加「本地副本不会再发送」。

### 待写入始终有归属

`forgetSubmissionReceipt` 与 `updateSubmissionReceipt` 原先在「本次没有改动」时提前返回，于是上一次失败留下的待写入没有归属。现在两者都在返回前调用 `persistIfDirty`。`recordSubmissionReceipt` 的容量分支同样补上。

### 测试注入边界

`installReceiptFile(key, path)` 让回执文件路径可注入，与草稿侧 `attemptDraftLoad(..., Path file)` 的做法一致。回执管线因此可用真实文件测试：读取失败、不可读、写入失败与重试。

新增 `ClientReceiptBoundaryTest` 13 项：

1. 回执文件损坏时带编号草稿不装载，文件保留。
2. 回执可读且无该条目时草稿正常装载。
3. 无提交编号的草稿在回执损坏时仍正常装载。
4. 已应用回执仍然阻止装载。
5. 读取失败在环境边界重试，之后能看到已应用结果。
6. 版本不匹配不在环境边界重试，但显式重试仍可用。
7. 无失败时显式重试报告无事可做。
8. 回执不可读时结算不报告已记录。
9. 回执可读且无条目时结算报告已记录。
10. 有可写文件时已应用结果确实落盘。
11. 删除操作会补写上一次遗留的待写入。
12. 更新操作会补写上一次遗留的待写入。
13. 写入失败后存储保持待写入，下一次调用补写。

### 本节披露

- 未运行 Gradle。以上测试没有执行结果。
- 结算用例只断言 `recordSaved`，因为副本结果依赖真实草稿文件位置；副本所有权本身由 `OperationDraftSettlementTest` 覆盖。
- `recordSaved = false` 时控制器显示 `operation_submit_cleanup_pending`。存储只读时该文案偏保守，因为副本其实已清除；客户端另有一条回执读取失败提示。没有为此新增第三个语言键。

## 修改后的披露

验证与风险：

- 本轮未运行 Gradle。以上测试是新增代码，没有执行结果。
- `ClientSessionManager` 是单例，读取 `FMLPaths.CONFIGDIR`。加载边界按真实文件与显式回执存储测试；回执路径可注入之后，回执管线也按真实文件测试。其余管理器接线未做集成测试。
- 清理持续未确认时，同一会话内只在下次环境入口或再次收到结果时重试，这样避免每 tick 读取文件。

行为边界：

- 加载边界拒绝装载已知已应用的草稿，也拒绝装载回执无法核对的草稿。这一步不能只靠清除副本，因为删除可能失败、文件可能不可读。
- 结果记录用存储的 dirty 标记判断是否已落盘。写入失败后再次调用会重试，不会把内存状态当成磁盘状态。
- 结算不触碰 live 工作区。在线路径成功时仍清空 live，因为那由提交跟踪器与工作区锁保证。
- 服务端准入重放链见 `2026-09-16-admission-replay-response.md`。

## 测试清单

本轮共 52 项：`OperationDraftSettlementTest` 33 项，`ClientDraftLoadTest` 新增 3 项，`OperationSubmissionReceiptTest` 新增 4 项，`ClientReceiptBoundaryTest` 新增 12 项。

新增文件 `OperationDraftSettlementTest`。

staged 所有权：

1. 无 staged 草稿判为 `ABSENT`，且不清除。
2. staged 属于更新的提交判为 `ABSENT`，且不清除。
3. staged 属于本次提交判为 `REMOVED`，且真的清除。

durable 所有权：

4. 文件不存在判为 `ABSENT`，且不尝试删除。
5. 文件读取失败判为 `UNCONFIRMED`，且不删除。
6. 文件未记录提交编号判为 `UNCONFIRMED`，且不删除。
7. 文件属于更新的提交判为 `ABSENT`，且不删除。
8. 文件属于本次提交且删除成功判为 `REMOVED`。
9. 删除失败判为 `UNCONFIRMED`。

合并与判定：

10. 两个所有权互不连带。
11. 两值全组合下 `confirmed()` 的真值表。

真实写入失败：

12. 写入失败后记录保持 dirty，重试成功后才变干净，重载后待清理仍在。
13. 清理标记写入失败后存储保持 dirty，重试成功后磁盘才与内存一致。
14. 与磁盘一致的存储不需要写入。

容量与不可驱逐：

15. 已应用且待清理的记录不会被容量驱逐。
16. 全部记录都有待办义务时，存储拒绝新记录。

结果语义与不降级：

17. 已应用结果不被 `UNKNOWN` 或可重试失败替换。
18. `UNKNOWN` 结果仍可升级为已应用。
19. 重载后的已应用记录仍拒绝降级。

真实文件重放：

20. 已应用草稿被清除，重载后不再请求清理。
21. 更新提交的草稿保留。
22. 旧格式草稿保持未确认。
23. 损坏文件保持未确认。
24. 文件不存在判为已确认。
25. 只读回执存储：副本清除仍执行，但保存失败不被当作已保存。

判定与幂等：

26. 判定读取器区分「有编号」「无编号」「无文件」。
27. 只读存储仍如实报告内存状态。
28. 重复确认清理是幂等的。

结果标记语义：

29. 非已应用结果永不请求清理。
30. 非已应用结果带清理标记会被拒绝。
31. 结果变为已应用时开始待清理；确认后清除；转为其他结果时撤销。

删除重试：

32. 删除失败后文件保留，下一次尝试成功。
33. 每次删除尝试都被计数。

`ClientDraftLoadTest` 新增，覆盖管理员接线层：

34. 已应用提交的草稿不进入 staged，文件保留，待清理仍可重试。
35. 结果仍开放的提交，其草稿照常进入 staged。
36. 未记录提交编号的旧格式草稿照常进入 staged。

`OperationSubmissionReceiptTest` 新增：

37. 已应用回执的待清理标记能跨重启保留。
38. 已确认的清理能跨重启保留。
39. 旧文件中的已应用记录按待清理读出。
40. 旧文件中的其他结果不产生待清理。
