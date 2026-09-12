# 2026-09-13 语义事件与 Enter/滚轮路径审计

本次只记录静态证据，不将客户端或真实服务器验收标记为完成。

## 已确认的路径

- `FastPlaceClientInput.onKey` 对 Enter（键码 257/335）共用一个确认入口。入口先检查 `ClientPlacementRouter.canConfirm` 和原版近方块让行，再按当前操作路由执行工作区提交、选区调整应用或普通确认。
- `ClientPlacementRouter.confirm`、`applyOperation` 和普通确认都通过 `ClientInputStateMachine.submit(...)` 进入 `SUBMITTING`，发包前使旧手势失效；服务端通过 `PlacementActionAckPayload` 结束等待。
- `onMouseScroll` 在 `OPERATION` 且工作区有效时处理选区移动；其余快速起形滚轮路径只发送 `ScrollCandidatePayload` 并记录滚轮反馈。滚轮不直接改写点字段。
- `ClientInputStateMachine` 会阻止 `SUBMITTING`、`PLACING`、`RESTORING` 和 `CANCELLING` 阶段的新输入，并按请求 ID/传输 UUID 忽略迟到或不匹配回执。

## 尚未满足的要求

当前代码没有独立的 `READY_TO_SUBMIT`（或等价的统一阶段 API）。Enter 是否可提交仍由 `active()`、`operationAdjustmentStarted()`、`canConfirm()` 和服务端当前阶段的组合结果决定；因此还不能证明“滚轮定线后所有可提交状态统一发出提交命令”，也没有覆盖 Enter 与异步结果同 tick、空候选、超上限和预览版本过期的客户端验收。

下一步应先建立可审计的阶段/命令转换表或等价测试，再在 `.233` 验证滚轮定线、Enter、取消、迟到结果及失败重试的显示和状态恢复。静态测试通过不能替代该验收。
