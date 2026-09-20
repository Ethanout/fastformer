# 选区会话生命周期

选区会话新增生命周期 owner。它使用 `IDLE`、`POINTING`、`FOCUSED` 和 `SUBMITTING` 阶段。事件通过 `onLifecycleEvent` 进入，阶段切换返回不可变转换记录。草稿事件和手动加入首点会先进入 `POINTING`。

环境切换调用瞬时清理。该清理结束拖动、编辑和 hover，生成新的交互对象会话身份，但保留草稿和部件。完整清理仍由 `clearLiveInteraction` 执行，并删除草稿和部件。草稿恢复不恢复旧提交阶段。

新增三项生命周期测试，覆盖连续切换、环境退出和显式清理边界。选区草稿状态机仍持有点、范围和 prism 底面数据。控制器的网络提交跟踪器尚未迁入生命周期 owner。

`./gradlew test --tests '*SelectionSessionLifecycleTest' --tests '*SelectionDraftStateMachineTest'` 成功，耗时 18 秒。客户端实机验收未执行。

随后执行 `./gradlew test check` 成功，耗时 29 秒。全量编译和测试实际执行。客户端实机验收未执行。
