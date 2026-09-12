# 2026-09-13 形状分块超时回执

## 修改

`IncomingPayloadTransfers.purgeExpired()` 现在对 workspace 和 shape 分块统一生成过期事件。服务器 tick 会为 shape 超时发送现有的失败结果回执，不再静默删除 shape transfer。重复清理保持幂等。

## 验证

`IncomingPayloadTransfersTest` 新增 shape 超时事件测试；`./gradlew test` 通过。仍需确认生产 shape 上传客户端收到回执后的重试界面。
