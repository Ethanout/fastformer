# 给大降智后的AI擦屁股（2026-09-13）

## 2026-09-13 静态 bug 审计新增

- BUG-10（低至中，已有修复，待验收）：当前 `ClientOperationController` 已增加 30 秒客户端提交超时；超时会解除锁定并保留草稿，提交进行中按 Q 会阻止清空草稿并显示 pending 提示。位置：`src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java:646-660`、`742-765`，`src/main/java/io/github/fastformer/client/input/FastPlaceClientInput.java:180-188`。仍需联合测试服务端慢任务、无回执、断线重连、超时后的迟到回执和后续新提交，确认迟到结果不能改变新的提交 owner 或状态。此前“无 deadline”与“Q 直接丢稿”的静态线索不再成立。
