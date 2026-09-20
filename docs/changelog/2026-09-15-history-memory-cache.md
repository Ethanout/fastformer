# 历史内存缓存

- `HistoryMemoryCache` 拥有已解码的 undo 和 redo 批次。
- 缓存维护两个栈、字节计数、去重合并、数量裁剪和字节预算裁剪。
- 新操作清除 redo。undo/redo 提交在缓存内部移动批次并更新字节计数。
- `HistoryTaskScheduler` 独占一个异步分页 Future 和待恢复请求。
- `WorldHistoryManager` 启动分页加载、合并结果，并重新执行现有 undo/redo 检查。
- 分页失败会清理调度状态、显示失败消息并阻止异常离开服务器 tick。
- `WorldHistoryManager` 继续负责世界写入和持久化协调。
- `WorldHistoryPersistence` 继续负责磁盘读写。

自动化测试覆盖新增、提交、redo 清理、分页合并、去重、数量裁剪、在飞互斥和失败清理。真实磁盘故障、进程中断和服务器延迟仍需实机验收。
