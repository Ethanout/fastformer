# Mixin 与工作区提交闭包（2026-11-15）

旧 `fastplace.placement.mixin` 中的 18 个适配器按真实依赖迁移，未修改注入方法、切点、取消条件、优先级或方法正文。

| 新包 | 类型数与职责 |
|---|---|
| `common.mixin.world` | 5：双端放置支撑查询、交互写入标志、邻居抑制、调度 tick 标记和保存。仅引用共享规则。 |
| `server.mixin.history` | 3：原版放置、破坏、使用物品与世界写入的历史观察。 |
| `server.mixin.workspace` | 3：工作区写入观察、随机 tick 暂停与调度 tick 延后。 |
| `server.mixin.sideeffect` | 4：活塞伴随方块保护和落沙／考古方块／滴水石副作用。 |
| `server.mixin.placement` | 3：空闲放置捕获、服务端交互更新策略与连体方块保护。 |

Mixin 配置的 package 不能提升为整个 `io.github.fastformer`，否则业务类也会落入 Mixin 专用加载区域。现有客户端配置继续独立；`neoforge.mods.toml` 随后按 history、placement（common world）、server 顺序声明三个配置，每个 package 只包含对应注入适配器。

对同一 `Level.setBlock(BlockPos, BlockState, int, int)` 的三个 WrapMethod，注册相对次序继续为 `VanillaHistoryWriteMixin` → `InteractionLevelMixin` → `WorkspaceWriteMixin`。三者仍使用原默认优先级 1000；本次没有另设优先级或调整观察、交互改旗标与工作区回调的先后条件。支撑查询作用于其他 Level 查询方法，邻居抑制作用于输出信号更新，没有新增同方法竞争。最终运行时组合须由新世界 GameTest 验证。

旧 `workspace.submission` 的 7 个类型归入 `common.workspace.submission`：不可变计划、预算内验证／组合、源清除语义、提交状态、准入、完成值和失败分类。它们不依赖协议或服务端运行系统，单元测试随被测包迁移。

旧 `network.codec` 的有界工作区 NBT 编解码和包私有解码截止时间流归入 `protocol.codec`，与通用 wire 编解码器同包。保持原版本、压缩限额、解码限额、取消检查及字段；没有保留旧格式读取或旧包别名。

最后的普通放置计划也按职责拆开：`PlacementGeometryPlan`、`PlacementBlockEstimate` 与纯 `SmartFillSearch` 归入 `common.placement.plan`。`PlacementGenerationPlan` 会构造异步生成和服务端写任务，归入 `server.session.execution`；它没有进入共享模型或已满 12 类的任务内部包。算法、饱和计算、限额及生成观察顺序保持原行为。

`QuickShapeCandidateContext` 是发布 revision 对应的纯候选事实，归入 `common.quickshape`；`QuickShapePointerSequence` 持有协议回调作用域和有序输入推进，归入 `server.input`。前者不为引用后者而反向依赖协议。

两个 NeoForge 事件适配器归入 `server.lifecycle`，原 `FastPlaceEvents` 改名 `ServerEvents`。主入口仍在原构造阶段调用注册，监听优先级和玩家替换、换维、启动、停止中的调用顺序未改。旧起形和选区空 package-info 一并删除。FastCtx 确认生产 `fastplace`、`network`、`workspace` 三棵旧目录没有 Java 文件，src/main 也没有对这三个旧包的引用。

命名按实际职责收口：`ServerInputDispatcher` 改为 `ServerInputActions`，表达请求准入、顺序／手势／revision 校验及模式或 Intent 执行；`OperationExecutionSemantics` 改为 `SourceClearingRules`，只负责判断复制、移动或偏移操作是否清除源位置。相应测试和源码守护同步改名，方法契约和分支未改。`FaceRasterDispatcher` 按手册第 10 节的明确名称保留。

本记录说明迁移范围。编译、完整 check、发布包及新世界 GameTest 均由主任务统一执行，不能据此宣称整份手册验收完成。
