# 2026-09-16 回执结算只读审计

范围：`ClientOperationController.applyWorkspaceReceipt`、`ClientOperationController.applyWorkspaceResult`、`ClientSessionManager` 的草稿清除与恢复入口、`ClientPlayerSession` 的 staged 所有权、`ClientOperationDraftCodec.readSubmissionId`。

本轮只读。未改任何 Java 文件，未运行 Gradle，未创建线程，未恢复历史实现或编译修复任务。现有源码保留。

证据级别：静态，当前工作树逐行调用链。没有自动化结果，没有实机结果。

行号取自本轮读取的修订。该文件由主代理并发修改，修复前需要重新核对行号。

## 结论摘要

| 编号 | 严重度 | 结论 |
|---|---|---|
| RS-1 | P1 | 终态先落盘，草稿后清理。清理失败后重复回答被早退，已应用草稿永久留在磁盘 |
| RS-2 | P1 | staged 命中旧提交后无条件删磁盘，可删除更新提交的恢复副本 |
| RS-3 | P1 | 删除失败仍返回成功，界面显示"提交成功" |
| RS-4 | P2 | 恢复被拒（身份不匹配）仍删磁盘，durable 草稿不可再取回 |
| RS-5 | P2 | "文件不存在"与"文件不可读"不可区分，结算是非判断不成立 |
| RS-6 | P2 | 在线路径清 live，离线路径不清 live，两条路径语义不同 |

## RS-1：终态先落盘，清理后执行，且清理不可重试

证据链：

1. `ClientOperationController.applyWorkspaceReceipt` 第 1055 至 1057 行先把 `APPLIED` 写入回执并落盘。
2. 同方法第 1061 至 1062 行随后才清 staged 与 durable 草稿。
3. 同方法第 1049 至 1051 行在 `entry.outcome() == receipt.outcome()` 时 `continue`。

后果一：进程在第 1057 行与第 1061 行之间停止，清理从未发生。

后果二：任一次清理失败后，回执已是终态 `APPLIED`。服务端对后续查询仍答 `APPLIED`，第 1049 行判等成立并早退，清理永不重试。

后果三：磁盘上的已应用草稿在下次登录被重新装载。`ClientSessionManager.loadDraftOnce` 第 481 至 489 行，`attemptDraftLoad` 第 497 至 523 行第 515 行 `stageSuspendedOperationDraft`，`ClientOperationController.shouldHoldStoredDraft` 第 333 至 337 行使其进入恢复确认流程。玩家可以把服务端已经写入的改动再次提交。

同一缺陷存在于在线路径：第 1134 行 `settleReceipt(APPLIED)` 先落盘，第 1137 行才 `discardDurableDraftIfSubmitted`。第 1152 至 1153 行的不可重试分支结构相同。

## RS-2：staged 命中旧提交后删除更新提交的副本

证据链：

1. `ClientSessionManager.discardCurrentDraftIfSubmitted` 第 218 至 224 行只检查 staged 草稿的 `belongsToSubmission(transferId)`，随后调用 `discardCurrentDraft()`。
2. `discardCurrentDraft` 第 199 至 205 行无条件调用 `deleteCurrentDraftFile()`，不看文件属于哪一次提交。

可达触发序列：

1. 登录。`attemptDraftLoad` 第 515 行把磁盘上的旧提交 X 装入 staged。
2. 玩家提交新工作区。`persistSubmittedDraft` 第 180 至 196 行把同一文件写成提交 Y。该方法只写盘，不 staging。`ClientPlayerSession.buildSubmittedDraft` 第 48 至 80 行同样不 staging。
3. 此时 staged 属于 X，durable 文件属于 Y。
4. X 的 `APPLIED` 到达。第 1061 行 `discardCurrentDraftIfSubmitted(X)` 返回 true，第 222 行执行 `deleteCurrentDraftFile()`，删掉 Y 的恢复副本。
5. Y 仍在飞行中。Y 的回执仍在磁盘，Y 的草稿已经不存在。

`staged` 与 `durable` 是两种所有权，共享一个删除动作。任何一方命中都会动另一方。

## RS-3：删除失败仍返回成功

证据链：

1. `ClientSessionManager.deleteCurrentDraftFile` 第 538 至 542 行返回 void。失败只发一条界面提示。
2. `discardDurableDraftIfSubmitted` 第 262 至 267 行忽略该结果，恒返回 true。
3. 调用方第 1063 至 1071 行按返回值显示 `fastformer.message.operation_submit_success`。

后果：文件还在，回执已是终态，界面声称成功。RS-1 的早退使这次失败不可重试。

在线路径第 1137 行与第 1153 行同样忽略返回值。

## RS-4：恢复被拒仍删磁盘

证据链：

1. `ClientSessionManager.restoreCurrentDraft` 第 157 至 168 行：只要 `hadStagedDraft` 为真，第 162 行就删除 durable 文件，不看 `restored` 的取值。
2. `ClientPlayerSession.restoreSuspendedOperationDraft` 第 151 至 169 行在身份不匹配时返回 false。

后果：服务端选区已变化时，玩家确认恢复得到的是一次拒绝，同时磁盘副本被删除。该内容此后不可取回。方法 javadoc 第 154 至 155 行称"消费或拒绝"才删除，与 `ClientDraftLoadState` 第 6 至 7 行"失败保留文件"的既有约定冲突。

## RS-5：不存在与不可读不可区分

`ClientSessionManager.durableSubmissionId` 第 243 至 253 行在两种情况下都返回 null：文件不存在，文件读取失败。

结算需要区分这两种情况：

- 文件不存在，说明该次提交没有 durable 产物，清理视为已确认。
- 文件不可读，说明所有权未知，清理不能确认，回执必须保持 open。

当前返回值只有 null 一种表达，判定为"不匹配"，因此不可读文件被当成"无产物"。这与 RS-4 一起会把损坏文件判为已清理。

## RS-6：两条路径对 live 的处理不同

在线路径第 1144 行执行 `clearWorkspace()`，live 工作区被清空。离线路径第 1052 至 1072 行不触碰 live。

`live` 工作区没有 submissionId，无法证明它属于哪一次提交。玩家在重连后可能正在编辑它，因此结算不触碰 live 是正确的选择。

需要写明的规则：结算只处理 staged 与 durable。live 的处置属于结果处理，两条路径必须给出同一规则，或者明确记录两者差异的理由。

## 统一的可重试结算边界

中心规则：清理确认在前，终态落盘在后。清理是幂等的，因此可以重放。

### 三种所有权

| 所有权 | 载体 | 判定依据 | 清除动作 |
|---|---|---|---|
| staged | `ClientPlayerSession.suspendedOperationDraft` | `draft.submissionId()` 等于本次 transferId | 清内存 |
| durable | 草稿文件 | `ClientOperationDraftCodec.readSubmissionId(root)` 等于本次 transferId | 删文件，返回真实结果 |
| live | `operationWorkspace` | 无稳定标识 | 结算不触碰 |

### 结算步骤

1. 校验回执存在，且 dimension 与 transferId 都与回答一致。
2. 结果为 `APPLIED` 时，对 staged 与 durable 各调用一次按所有权清除。两次调用互不连带。
3. 每次清除返回三值之一：`PRESENT_AND_REMOVED`、`ABSENT`、`UNCONFIRMED`。
4. 只有两次结果都不是 `UNCONFIRMED` 时，才把终态写入回执。
5. 存在 `UNCONFIRMED` 时，回执保持 open，并保留待清理状态。下一次回答或下一次登录边界重试结算。

### 对现有缺陷的作用

- RS-1：清理先于落盘。落盘失败或进程停止时，回执仍为 open，下一次回答重新结算。判等早退改为"判等且无待清理"才早退，或者对 `APPLIED` 恒执行结算，仅对界面提示去重。
- RS-2：两种所有权各自寻址，任何一方命中不再连带另一方。
- RS-3：删除结果向上传递。未确认时不显示"提交成功"。
- RS-4：`restoreCurrentDraft` 只在 `restored` 为真时删除 durable 文件。被拒时保留文件并提示。
- RS-5：清除结果使用三值，读取失败映射为 `UNCONFIRMED`。
- RS-6：结算不触碰 live，并在两个结果路径写明同一规则。

### 需要的最小接口改动

- `deleteDraftFile` 的结果必须能被调用方读取。当前 `deleteCurrentDraftFile` 返回 void。
- 需要一个按所有权命名的清除入口，替代 `discardCurrentDraftIfSubmitted` 与 `discardDurableDraftIfSubmitted` 的组合，避免连带删除。
- 需要一个可测的删除失败注入口，用于制造 `UNCONFIRMED`。

## 关键测试

分为三组。第 1 组覆盖已证实缺陷，第 2 组覆盖清除语义，第 3 组覆盖持久化与重试。

1. staged 属于 X、durable 属于 Y。X 的 `APPLIED` 到达后 staged 清空，Y 的文件仍存在。
2. 删除失败时结算返回未完成，回执保持 open，界面不显示"提交成功"。
3. 回执已是 `APPLIED` 且清理曾失败。再次收到 `APPLIED` 回答时仍执行清理。
4. 在清理与终态落盘之间注入失败。回执保持 open，下次结算仍会执行清理，且清理幂等。
5. `restoreCurrentDraft` 身份不匹配时 durable 文件仍存在。
6. staged 与 durable 各自存在与缺失的四种组合，结算都收敛到同一终态。
7. `durableSubmissionId` 面对不存在文件返回"无产物"，面对损坏文件返回"未确认"。
8. durable 文件损坏时结算不落终态，且不显示成功。
9. `belongsToSubmission` 对另一 transferId 返回 false，对应文件不被删除。
10. 在线路径的 `APPLIED` 之后，磁盘上没有属于该 transferId 的草稿文件。

## 未完成与限制

- 本轮未运行 Gradle，未新增测试，无执行结果。以上测试全部是待写清单。
- 未做故障注入，`UNCONFIRMED` 的实际出现频率未知。
- 主代理正在修改 `ClientPlayerSession.buildSubmittedDraft` 与提交来源规范化。修复前必须按当时源码重新核对行号与签名。
- 未审查服务端 `WorkspaceSubmissionBook` 的结算语义，本轮范围限于客户端。
