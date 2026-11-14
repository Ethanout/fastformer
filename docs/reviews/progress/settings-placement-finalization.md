# 玩家设置与共享放置效果迁移（2026-11-15）

`fastplace.settings` 中三个类型按实际依赖迁移，不保留旧包转发：

| 类型 | 新包与边界 |
|---|---|
| `FastPlaceSettings` | `server.settings`。读取和保存 ServerPlayer 数据，修改后调用所属历史与选区服务；没有搬入 common。 |
| `PlayerBlockReach` | `server.player`。初始化和复制原版方块交互距离属性。 |
| `FlightSettings` | `common.movement`。不可变飞行参数和双端共用的冲刺距离上限，不访问客户端配置。 |

对应设置测试迁入 `server.settings`，保留包内编码与原模式读取的可见性。客户端配置、飞行界面、协议校验和服务端冲刺均引用共享 FlightSettings。

设置默认值、边界、NBT 字段和保存顺序保持原行为。此阶段没有增加旧格式兼容，也没有因迁包扩大设置内部方法的可见性。

原放置效果契约、注册表、上下文、惰性结果和木框算法迁入 `common.placement.effect` 及其 `woodframe` 子包。共享效果不再接收服务端 FastPlaceSettings：注册表接收效果 ID 的启用谓词，服务端 `PlacementEffectResolver` 传入当前设置的判断。客户端仍按已确认的效果 ID 解析同一算法；优先级顺序和首个匹配规则不变。

ServiceLoader 的服务文件名和实现类路径随真实包路径更新。服务端效果上下文装配迁入 `server.interaction`，不进入共享注册表。

`ForcedPlacement`、扳手事务应用和历史分类归入 `server.interaction`。扳手的资格、距离和纯规则预测拆为 `common.tinker.TinkerPrediction`，客户端不再引用服务端扳手入口。规则 GameTest 使用 testSupport 的便利夹具，不向生产事务 API 添加测试方法。

`InteractionUpdateScope` 归入 `common.placement`，只保留原作用域恢复与调度 tick 标记；它接收本次是否抑制更新，不读取玩家设置，也不保存客户端策略。删除重复的客户端 WeakHashMap，客户端交互、连体破坏和普通破坏预测复用 `ClientInteractionUpdates` 已有的同步策略。`ServerInteractionUpdates` 只读取服务端设置；新增的两个客户端 mixin 只在客户端注册，集成服务器调用仍通过 level 端标记放行。原异常恢复和嵌套作用域顺序不变。

FastCtx 静态检查确认新共享闭包没有对客户端、服务端或旧业务包的引用。统一编译、结构检查、SPI 加载及完整回归由主任务执行；本文不单独宣称完整检查通过。
