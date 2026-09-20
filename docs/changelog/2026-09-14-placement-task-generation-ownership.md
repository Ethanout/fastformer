# PlacementTask 生成所有权

- 取消或恢复交接时，`PlacementTask` 不再保留未完成生成 future。
- generation reservation 仍在生成 worker 结束后释放。
- 迟到的生成失败不会改变已交接任务的失败状态。
- 保留实机退出存档、磁盘故障和客户端生命周期验收。
