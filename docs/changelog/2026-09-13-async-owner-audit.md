# 2026-09-13 异步 owner 与版本令牌审计

本次只记录静态证据，不替代客户端生命周期验收。

- `BuildingPreviewGenerationOwner` 只持有一个 Future；替换或取消时先取消旧 Future，
  取回结果时同时比较 Future 身份与 `BuildingPreviewKey`，因此旧生成结果不能覆盖新候选。
- `PendingGhostMeshCache` 为每次方块集合变化递增 `version`，异步结果携带请求版本，
  发布前必须匹配当前版本；清理预览也会递增版本并取消 Future。
- `ClientPreviewState` 对建筑会话、参数和效果分别维护 revision 水位，并拒绝较旧回执；
  重连恢复还要求 revision 与待恢复快照一致。
- `ClientInputStateMachine` 以请求 ID/传输 UUID 锁定提交 owner，`ClientOperationController`
  以 `pendingWorkspaceTransferId` 过滤工作区迟到结果；工作区编辑以 `EditToken` 保护。

这些局部 owner/版本保护已存在，但尚未形成覆盖换维度、断线、取消、迟到提交回执及资源
释放的单一协议。TODO 中“单一异步 owner 与版本令牌”及客户端实机验收仍保持未完成。
