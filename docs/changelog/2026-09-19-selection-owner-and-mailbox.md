# 选区所有权与回执队列迁移

## 已实现

- `ClientSelectionSession` 持有草稿、部件容器及选择集。`ClientPlayerSession.operationWorkspace()` 仅转发，不再新建容器。
- 阶段查询从当前草稿、选择集和服务端设点条件计算。删除缓存阶段字段、刷新方法和容器变更回调。查询不保存第二份状态。
- 环境清理由选区会话清理活动数据，保留形状模式。单独清除草稿不删除已有部件。磁盘草稿格式未变。
- 九类客户端网络回执进入 `ClientTickMailbox`，在客户端 tick 消费时检查连接与会话身份。离开环境后清除待处理事件。
- 邮箱按固定批次处理。处理期间新增事件留到下一批，旧 epoch 结果被丢弃。异常时释放未交付事件，不重复派发。

## 自动化证据

- 选区所有权调整后执行 `./gradlew test` 成功，测试实际执行。新增四项阶段测试及一项玩家归属测试。
- `ClientTickMailboxTest` 九项、`ClientPayloadDispatcherTest` 三项、`ClientSelectionSessionTest` 十项全部通过。
- 随后 `./gradlew check` 成功。测试任务复用本轮结果，jar 校验实际执行。
- 网络队列迁移后、选区所有权调整前执行 `./gradlew check runGameTestServer` 成功。81 项必需 GameTest 通过，该结果不代表本轮所有权调整后的 GameTest 证据。

## 剩余边界

- 物理输入仍未统一入队。不能只延迟草稿点击而让 Enter、Q 立即执行，否则事件会乱序。
- 阶段目前仍为只读投影，尚未完成 `enter / onEvent / tick / exit` 生命周期。
- 标签命中与显示仍各自计算位置。交互组件、hover、拖动捕获和完整显示快照仍需迁移。
- 后台计算、磁盘完成、服务端任务及历史 owner 尚未全部迁移。
- 未执行客户端实机、性能采样和真实故障恢复验收。
