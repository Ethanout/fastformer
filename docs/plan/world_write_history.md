# 世界写入与历史所有权

这份文档记录当前实现的交接边界。它服务于小型结构的首块延迟，也约束后续拆分不能破坏恢复日志和撤回语义。

## 阶段图

```text
目标生成
  -> 内存预约
  -> expected 快照与冲突检查
  -> write-ahead journal 创建
  -> 世界批次写入
  -> 实际 after 快照
  -> journal after 完成
  -> 压缩 before/after 历史批次
  -> 发布一次 undo/redo 记录
```

写入阶段只需要 `expected/before`、冲突策略、journal 和世界写入许可。after 快照与压缩历史属于写入完成后的收尾阶段；它们不能成为首批写入的前置条件。

## 首批写入所需的分段 WAL 设计

当前 `.dat` journal 把全部 `before` 与预测 `after` 放在一个原子文件中。它能保证完整的 write-ahead 约束，但首个方块必须等待整个结构的快照和压缩完成。小型结构的首块延迟优化需要把 journal 拆成可独立校验的 segment，同时保留恢复期间的单一操作所有权。

每个操作使用一个固定 operation ID，并在 `fastformer-recovery` 目录创建操作目录：

```text
<sequence>-<owner>-<operation>/
  manifest.dat
  segment-000000.dat
  segment-000001.dat
  ...
  seal.done
```

`manifest.dat` 只包含版本、维度、owner、operation ID、segment 数量上限和坐标顺序规则。每个 segment 包含连续位置的 `before` 快照、预测 `after` 快照、序号、条目数量和 manifest 摘要。segment 写入临时文件、强制落盘后再原子改名；只有改名成功后，调度器才允许写入该 segment 的方块。

操作状态按以下顺序推进：`manifest prepared -> segment prepared -> segment written -> segment verified -> final seal`。`segment prepared` 是首批写入的安全门槛；`final seal` 只在所有 segment 的实际 after 已收集并且修正数据写入后发布。历史压缩在 final seal 之后异步执行，不能阻塞首批世界写入。

恢复规则：

- 缺少 `final seal` 时，按序读取所有完整 segment，只恢复能匹配 `after` 的位置；最后一个临时文件和未完成 segment 视为未提交并删除。
- 存在 `final seal` 时，先校验 seal 中的 segment 数量、摘要和维度，再按写入顺序回放实际 after，保留外部修改。seal 表示操作完成，与旧 `.done` 语义一致，重启不能撤销已完成操作。
- segment 序号缺失、摘要不匹配、重复序号或同时存在临时/正式文件时，停止该操作的自动恢复并保持文件，阻止新的世界写入。
- 取消发生在首个 segment 写入前时删除整个操作目录；取消发生在任一 segment 写入后时保留已完成 segment，转入恢复任务，不能直接释放 lease。
- 世界保存和恢复完成后才删除目录。进程重启重复执行同一规则必须幂等。

实现顺序：先抽取 `RecoveryJournalSegment` 的编码与校验，再接入内容恢复，最后替换现有一次性 `PersistentRecoveryJournal` 的提交路径。`WorldWriteCoordinator` 的 lease 仍归整个操作所有，segment 只决定哪些位置已具备写入所需的持久日志。旧 `.dat/.done/.delta` 格式在迁移期继续只读恢复，避免升级时丢失未完成操作。

## 首块延迟约束

当前 `PersistentRecoveryJournal` 使用一次性、位置对齐的 `Before`/`After` 数组，并通过临时文件写完、强制落盘、原子改名后才发布 prepared journal。这个格式不能在只拿到首批快照时安全地追加记录：未完成的后续位置会让恢复任务无法判断 journal 是否完整。因此首批写入提前到“全部快照之后”之前，需要先引入分段 write-ahead 设计（例如带 operation ID、批次序号、完成标记和最终 seal 的 append-only journal），并为崩溃恢复定义半成品批次规则。单纯提高每 tick 预算或异步压缩不能解决这段首块等待。

## 资源所有者

| 资源 | 当前所有者 | 生命周期 |
| --- | --- | --- |
| 目标源与生成游标 | `PlacementTask` / `WorldOperationTask` | 生成完成，写入游标耗尽后释放 |
| expected 快照 | `WorldChangeTransaction` | 冲突检查完成并转为写入游标后释放 |
| before 快照 | `WorldChangeTransaction` | 写入失败时转移给恢复任务，成功后交给历史批次 |
| 预测 after | `WorldJournalPreparation` | journal 创建期间使用，不能替代实际 after |
| 实际 after | `WorldChangeTransaction` | 最后一个写入完成后收集 |
| 恢复 journal | `WorldJournalPreparation` / `PersistentRecoveryJournal` | 首批写入前创建；失败或取消时继续用于恢复 |
| 历史批次 | `WorldOperationCommit` / `WorldChangeBatch` | journal after 完成后异步压缩，发布后由历史管理器持有 |
| 内存预约 | 任务与 `MemoryReservation` | 资源交接时缩容或释放 |
| 世界写入许可 | `WorldWriteCoordinator` | 每次写入阶段持有，收尾或失败时释放 |

## 交接规则

1. operation ID 在目标生成时创建，并贯穿 journal、历史批次和日志。
2. journal 是崩溃恢复的事实来源；历史批次只服务于玩家 undo/redo。
3. 取消或写入异常先从任务表摘除，再把 before/after 和 journal 转移到恢复管理器。
4. 历史压缩失败不能撤销已经完成的世界写入；应保留 journal，并报告“写入完成但撤回记录不可用”。
5. expected、before、after 的坐标顺序必须保持一致；压缩只能改变存储布局，不能改变顺序语义。

## 与 WorldEdit/Axiom 的借鉴边界

WorldEdit 的 `EditSession`/ChangeSet 分离说明写入和 undo 记录可以由不同对象负责；Axiom 的预览与提交状态分离说明预览数据不应成为提交事务的所有者。本模组额外保留 write-ahead journal、冲突检查和进程重启恢复，因此只借鉴边界，不引入通用 Extent 层。
