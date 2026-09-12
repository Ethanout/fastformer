# 2026-09-13 历史存储生命周期静态审计

## 已确认

- `HistoryStore.save` 先复制调用方 payload，再把写入串接到单一 `pending` 队列；调用方取消返回的 future 不会取消实际 I/O，也不会提前释放排队字节计数。
- 写入在 `fileLock` 内按临时文件/封装格式完成，并在替换前检查单 owner 与全局磁盘配额。读取同样检查文件大小和格式版本，损坏或版本不兼容会以失败 future 返回。
- `WorldHistoryManager` 对活动 owner、pending record 和 pending capture 保留独立状态；缓存淘汰不能直接丢弃这些活动数据。此前 deferred record FIFO 修复已由 `a5c000d` 覆盖。
- 自动化测试已覆盖配额、损坏索引、版本不兼容、队列上限、淘汰保护、undo/redo 顺序、deferred record 以及异步取消后的资源释放。

## 尚待验收

静态审计不能证明真实磁盘满、进程在索引替换或批次删除边界中断、I/O 竞争、服务器重启后的方块实体完整性。上述场景仍保留在 `current_todo.md` 的历史与恢复及生命周期自动化条目中。未发现可在不改变协议的情况下安全补入的独立实现缺陷。
