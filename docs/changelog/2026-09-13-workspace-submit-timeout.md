# 2026-09-13 工作区提交超时收尾

## 修改

- `ClientOperationController` 为工作区提交增加 30 秒客户端等待上限。
- 超时后清除旧 transfer owner、解除工作区锁定并保留草稿，显示可重试提示。
- 迟到的旧 `OperationWorkspaceResultPayload` 不会改变后续提交状态。
- 提交期间按 Q 保留草稿并提示提交仍在进行，不再无条件清空工作区。

## 验证边界

`./gradlew test` 通过。仍需客户端与服务器联合验证正常慢任务、无回执、取消回执、断线重连及迟到回执。
