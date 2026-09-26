# 模块职责重构

基线提交：`562db78`（`Organize source packages, test sources, and project tools`）。

## 代码归属

- 输入会话与调度控制器保留在同一包内。独立规则、状态、通用手势与交互意图各自分组。选区操作和快速起形拥有各自的输入事件与捕获状态。
- 历史调度、撤销重做与持久化协调统一放在 `fastplace.history`。恢复日志放在 `fastplace.recovery`。内存准入和方块快照分别放在 `fastplace.world.memory`、`fastplace.world.snapshot`。
- 服务端提交账本放在 `server.submission`。共享提交结果放在 `workspace.submission`，消除工作区模型对网络层的依赖。
- 几何控制点、交互模型和文本分别放在 `fastplace.geometry.controlpoint`、`interaction`、`text`。工作区成本与预算放在 `workspace.geometry`。

## 行为边界

`BuildingPreviewCache` 从预览入口接管生成任务、结果降级、缓存失效和渲染帧缓存。修饰键读取与错误记录通过参数传入。`BuildingShellRenderer` 接管外壳绘制，显式接收透明度和动画相位。

恢复模块读取自己的日志清单，再以所有者与操作标识调用历史协调接口。历史模块不再读取恢复模块的私有清单格式。存档格式与网络枚举顺序不变。

测试按被测模块迁移。跨模块测试通过仅存在于测试源码集的访问类调用内部钩子。生产状态字段保持封装。架构检查新增工作区不得依赖网络层的约束。

## 验证

- 关闭构建缓存，执行 `clean check runGameTestServer`：通过。
- JUnit：311 个测试类，1,836 个测试，无失败或错误，1 个跳过。逐一比较测试源码与 XML 报告，没有遗漏。
- GameTest：118 个必需测试全部通过。
- 源码结构、发布 JAR 和测试钩子检查：通过。
- 七种独立进程恢复场景全部通过：`FirstWrite`、`BeforeHistoryBatch`、`AfterHistoryBatch`、`SealedHistory`、`PublishedHistory`、`MultipleHistory`、`MixedHistory`。

`docs/principles/pseudocode/` 保持不变，开发运行仍使用常规 `run/`。大型预览入口、历史管理器和恢复日志仍保留各自的协调流程，此次没有把这些流程拆成大量互相暴露状态的小类。
