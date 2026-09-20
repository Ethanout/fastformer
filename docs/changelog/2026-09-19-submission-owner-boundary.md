# 提交 owner 边界

工作区提交 tracker 现在保存提交时的选区交互 owner。服务端回执必须同时匹配 transfer ID 和当前 owner；旧会话或旧作用域的回执会被丢弃。提交开始、超时和清理仍使用原有生命周期，网络与磁盘格式未改变。

控制器的提交门禁也通过当前 owner 查询 tracker；旧 owner 的 pending 不再阻塞粘贴、撤销、移动、变换或再次提交。

同时，空的草稿事件在进入选区生命周期前直接拒绝，非法输入不会改变阶段状态。

验证：`./gradlew test --tests '*WorkspaceSubmissionTrackerTest'` 通过。
