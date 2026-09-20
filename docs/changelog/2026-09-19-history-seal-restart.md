# 历史封口边界重启测试

## 实现与结果

扩展开发用 `RecoveryProcessTests` 和 `scripts/Test-RecoveryRestart.ps1`，增加 `SealedHistory` 场景。测试类仍从生产 jar 排除。未修改生产恢复逻辑、网络或磁盘格式。

2026-09-19 执行：

```powershell
./scripts/Test-RecoveryRestart.ps1 -RunDirectory run/history-seal-restart-20260919 -Scenario SealedHistory
```

脚本返回成功。结果文件 `run/history-seal-restart-20260919/recovery-process-result.txt` 为 `PASS`。

测试先保存石头基线，再准备 journal、写入金块、发布历史批次并封口。确认索引尚不存在后调用 `Runtime.halt(17)`。第二个服务器进程执行真实启动恢复，并检查：

- 世界方块为已提交的金块。
- undo 索引恰好包含本次操作，redo 为空。
- 历史批次可以解码，操作身份正确。
- journal 已清理，写入门禁开放，各维度无忙碌预约，内存预约为零。

测试不在封口后主动保存世界，要求启动恢复处理已提交 journal。全部操作使用独立新存档。

## 未覆盖

这不是断电测试，也不是完整任务调度测试。测试通过现有持久化接口建立发布边界，未验证三类任务均能正确推进到该边界。

多操作历史顺序、undo/redo 实际执行、方块实体、流体、外部修改和磁盘满仍需分别留证。本结果不允许将历史协议整项验收标为完成。

## 补充发布边界

同日增加并执行以下三个双进程场景。每个场景使用独立新存档，脚本退出成功，目录中的 `recovery-process-result.txt` 均为 `PASS`。

| Scenario 参数 | RunDirectory 参数 | 中断位置 | 重启断言 |
| --- | --- | --- | --- |
| `BeforeHistoryBatch` | `run/history-before-batch-20260919` | 世界已写入，历史批次尚未发布 | 世界回退为石头，undo/redo 均为空 |
| `AfterHistoryBatch` | `run/history-after-batch-20260919` | 历史批次已发布，journal 尚未封口 | 世界回退为石头，undo/redo 均为空 |
| `PublishedHistory` | `run/history-index-restart-20260919` | journal 已封口，索引已发布，journal 尚未清理 | 世界为金块，undo 恰好一条，redo 为空 |

运行方式与上文相同，将 `-Scenario` 和 `-RunDirectory` 替换为对应行。未封口场景在中断前保存已写入世界，避免仅通过重读旧区块而误判回退成功。所有场景同时检查 journal 清理、写入门禁、维度预约及内存预约。

## 已有历史的保留与顺序

随后将上述四个场景扩展为预先存在一条旧历史。旧操作位于相邻方块，世界保存为钻石块，批次及索引先完成持久化。四个场景再次全部通过。

结果目录为 `run/history-order-<Scenario>-20260919`，其中 `<Scenario>` 分别为 `BeforeHistoryBatch`、`AfterHistoryBatch`、`SealedHistory`、`PublishedHistory`。每个目录的结果文件均为 `PASS`。

未封口场景检查新位置回退为石头、旧位置仍为钻石块、历史仅保留旧操作。封口场景检查新位置为金块、旧位置仍为钻石块、历史顺序为新操作再旧操作。索引顺序与解码后的批次顺序必须同时符合预期，redo 为空。

这覆盖一条既有历史与一条中断操作，不覆盖多个未清理 journal 的相互排序，也未实际执行 undo/redo。

## 两份重叠 journal

新增 `MultipleHistory` 场景并执行通过，结果见 `run/history-multiple-journals-20260919/recovery-process-result.txt`，内容为 `PASS`。

场景保留一条既有历史，再将同一位置依次从石头改成金块、绿宝石块。两次修改分别发布批次并封口，但均不更新索引。中断后启动恢复重放两份 journal。

断言确认最终世界为绿宝石块，索引和加载批次顺序均为第二次修改、第一次修改、既有历史，且 journal、写入门禁和预约均清理。启动日志记录两次按顺序重放。

此场景通过现有持久化接口构造边界。混合封口/未封口日志、恢复过程中再次中断、外部修改与实际 undo/redo 仍未覆盖。

## 混合封口状态

`MixedHistory` 场景通过。结果文件为 `run/history-mixed-journals-20260919/recovery-process-result.txt`，内容为 `PASS`。

同一位置先从石头改成金块并封口，再改成绿宝石块但不封口。两份批次均已发布，新操作均未进入索引。测试保存绿宝石块所在世界后强制中断。

重启断言确认世界回到金块，历史只包含金块操作和既有旧操作，未封口的绿宝石块操作不在历史中。journal、写入门禁和预约均清理。这只覆盖已提交操作之后发生未提交操作的顺序。

恢复过程中再次中断、外部修改、方块实体及实际 undo/redo 仍需验收。
