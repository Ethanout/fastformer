# 2026-09-16 客户端回执边界：草稿读取的等待与重开

范围：`ClientSessionManager` 的草稿读取、回执读取与两者之间的边界；`ClientDraftLoadState`；测试 `ClientReceiptBoundaryTest`。

本轮改了 `ClientSessionManager`、`ClientDraftLoadState` 与该测试。未改服务端、未改几何 composer。未运行 Gradle。

## 一、已确认的缺口

`attemptDraftLoad` 在回执无法读取时（`ReceiptVerdict.UNKNOWN`）把 `draftLoadStates` 记为 `READY`。`READY` 同时表达两件事：“读取完成”与“本作用域不再重读”，而 `attemptDraftLoad` 第 747 行的守卫正是 `recorded.blocksRead()`，对 `READY` 返回真。因此：

- 回执文件修好后，草稿文件永远不会再被读取，`APPLIED` 判断没有第二次机会。
- `retryCurrentReceiptLoad()` 与 `activateScope` 的 scopeChanged 分支只清回执状态与回执缓存，不动草稿状态。
- `retryCurrentDraftLoad()` 要求 `mayRetry()`，而 `READY` 不满足，所以显式重试也无效。

结论：新增的回执读取机制当时无法真正恢复草稿，等待状态冒充了已完成装载。

## 二、修复

1. 新状态 `ClientDraftLoadState.AWAITING_RECEIPT`：草稿文件已读，但回执无法回答。`blocksRead()` 仍为真，所以每 tick 不会重读坏文件；`mayRetry()` 为真，所以显式边界仍可清它。
2. `attemptDraftLoad` 拆开两种非 `NOT_APPLIED` 结论：
   - `APPLIED` 记录 `READY`，因为服务端已经写入，后续回执答案不会改变结论。
   - `UNKNOWN` 记录 `AWAITING_RECEIPT`，不再记录 `READY`。
3. 回执恢复时重开草稿读取。新增私有 `rearmDraftReadAfterReceipt(key)`，只在状态正好是 `AWAITING_RECEIPT` 时清除它。两处调用：
   - `recordReceiptLoadStateCleared(key)`：显式 `retryCurrentReceiptLoad()` 与 scopeChanged 边界都经过这里。
   - `receiptStore(key)` 成功读到可写存储之后。
4. 玩家意图优先。`rearmDraftReadAfterReceipt` 不清 `READY`，因此玩家丢弃草稿（`discardCurrentDraft`）或新草稿写盘（`suspendCurrentDraft`、`persistSubmittedDraft`）之后，回执恢复不会把旧草稿重新装载。
5. 现场工作优先。新增 `liveWorkBlocksDraftRead(session)`：会话中已有工作区部件或选区草稿时，读取不发生、也不记录状态，直接返回 `NOT_LOADED`。该检查在内存中完成，位于文件读取之前，因此既不覆盖玩家的实时工作，也不产生每 tick 的文件读取；等实时工作消失后，下一次调用才真正读文件。

## 三、测试

`ClientReceiptBoundaryTest` 更新与新增：

1. `aDraftIsNotStagedWhenItsReceiptFileCannotBeRead`：断言由 `READY` 改为 `AWAITING_RECEIPT`（该断言原先固化了缺陷本身）。
2. `aRepairedReceiptLetsTheWaitedDraftArrive`：回执损坏 → `AWAITING_RECEIPT` 且不装载 → 修复文件并 `retryCurrentReceiptLoad()` → 重新读取并装载，状态 `READY`。
3. `aRepairedReceiptFileIsNotReadWithoutABoundary`：文件自行恢复但无边界时，状态保持 `AWAITING_RECEIPT` 且不装载，证明没有每 tick 重读。
4. `anAppliedReceiptKeepsTheWaitedDraftOut`：修复后的回执写着本提交 `APPLIED`，重新判断后仍不装载，文件保留。
5. `aDiscardedDraftDoesNotReturnWhenTheReceiptRecovers`：玩家已丢弃后，回执恢复不重开。
6. `liveWorkKeepsTheDurableCopyOutOfTheEditor`：会话已有选区草稿时读取被跳过且不记录状态；实时工作清空后同一次调用路径正常装载。

## 四、另一项 fixture 修正

`aFailedWriteStaysPendingAndTheNextCallRetriesIt` 原先只放一条回执，`forgetSubmissionReceipt` 之后存储变空，`OperationSubmissionReceiptStore.save` 走空存储分支，只调用 `Files.deleteIfExists`；父路径是普通文件时该路径不存在，删除返回 false 而不抛异常，于是写入“成功”，断言失败。

实现无缺陷：空存储且文件不存在时，“没有文件持有该回执”这一结论是真实的，因此报告持久化成功是正确的。修正的是 fixture：保留第二条回执，使存储非空，保存必须真正写文件，父路径是普通文件时产生真实 `IOException`，`forget` 因此返回 false。断言未改，并新增一条“删除不得丢掉另一条回执”的检查。

## 五、discard 流程的路径注入（NPE 修正）

`aDiscardedDraftDoesNotReturnWhenTheReceiptRecovers` 首轮在无游戏目录的测试环境里抛 NPE：`discardCurrentDraft` → `deleteCurrentDraftFile` → `deleteDraftFile` → `draftFile(key)` → `FMLPaths.CONFIGDIR.get()`。回执侧早有 `installReceiptFile(key, file)` 这样的注入缝，草稿侧没有，所以测试无法走真实 discard。

修正：新增对称的 `installDraftFile(key, file)` 与 `draftFileFor(key)`，并把草稿的保存、读取、探测与删除四条路径全部改为经过 `draftFileFor`（`suspendCurrentDraft`、`persistSubmittedDraft`、`probeDurableDraft`、`loadDraftOnce`、`deleteDraftFile`）。生产环境没有注入时仍回落到原来的 `draftFile(key)`，行为不变。

测试因此走真实 discard：断言状态回到 `READY`，并新增断言注入的草稿文件确实被删除。没有把断言换成手动改状态。

## 六、未做与开放项

- 未为 `AWAITING_RECEIPT` 增加界面提示：现有 `operation_draft_receipt_unknown_not_restored` 文案继续使用。
- `AWAITING_RECEIPT` 的 `mayRetry()` 为真，因此显式 `retryCurrentDraftLoad()` 在回执仍不可读时也会重读一次草稿文件；这是一次显式动作，不是每 tick 行为。是否要把它与回执状态绑定，留给产品决定。
- 未验证真实磁盘故障（占用、权限）下的重开时序；本轮测试用的是真实文件内容损坏与缺失。

状态：修复与测试已交付，未运行 Gradle。等待主代理验收。
