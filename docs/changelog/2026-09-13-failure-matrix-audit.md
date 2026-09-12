# 2026-09-13 失败矩阵自动化覆盖审计

本次只核对自动化证据，不把它当作客户端实机或独立服务器进程验收。

| 阶段 | 已有自动化证据 | 仍缺的真实环境证据 |
| --- | --- | --- |
| 生成 | `PlacementTaskTest`、`PlacementDeferredGenerationTest`、`GenerationLimitExceededTest` | 客户端首帧等待、取消后迟到结果的实际帧表现 |
| 首批/后续写入 | `WorldTaskBudgetTest`、`WorldOperationCommitTest`、`WriteFailureGameTests` | 服务器断线、换维度、世界卸载时的连续写入 |
| 提交与冲突 | `WorldChangeTransactionTest`、`WorldWriteSideEffectGuardTest`、`TaskCommitFailureGameTests` | 真实进程在封口提交边界中断后的恢复 |
| 撤回/重做 | `WorldHistoryPublicationTest`、`WorldHistoryRecoveryPolicyTest`、`WorldHistoryOwnerLifecycleTest` | 重启后方块实体完整性与磁盘 I/O 竞争 |
| 历史存储 | `HistoryStoreTest`、`HistoryStoreOrderingTest`、`HistoryEnvelopeReadTest`、`VersionedHistoryEnvelopeTest` | 真实磁盘满、文件损坏和版本不兼容时的用户提示顺序 |

结论：自动化已覆盖多数阶段转换、资源释放和失败分支，但不能关闭 `current_todo.md` 中的客户端实机、断线/换维度、服务器重启及磁盘故障条目。当前没有证据支持将这些剩余项标记为“已验收”。
