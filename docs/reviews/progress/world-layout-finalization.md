# 世界写入职责迁移（2026-11-15）

本阶段删除生产 `fastplace.world`、`fastplace.world.memory` 和 `fastplace.world.snapshot`。没有保留旧包转发壳，也没有修改架构债务基线。

## 职责归属

| 新包 | 类型与职责 |
|---|---|
| `common.world.memory` | 4 个类型：内存准入结果、状态、工作集估算和可释放预留。客户端提交与服务端任务共同使用，不依赖服务端类。 |
| `server.task.metrics` | 3 个类型：阶段、耗时统计、节流的批次反馈。 |
| `server.worldwrite.snapshot` | 5 个类型：可逆快照、方块实体数据、快照读取接口、紧凑映射及内存测量。 |
| `server.worldwrite.transaction` | 5 个类型：事务捕获、变更暂存、批次提交和恢复交接。 |
| `server.worldwrite.effect` | 3 个类型：世界活动规则、写入副作用抑制和调度屏障。 |

`SnapshotMemoryRules` 承担服务端快照的 NBT 保留量估算。原方法接收 `ReversibleBlockSnapshot`，若随 `WorldOperationMemory` 搬到 common 会形成反向依赖；现在通用估算只接收数量和字节，快照测量留在服务端。

`PlacementUpdateMode` 迁入 `common.placement`，客户端预测与服务端写入共用同一枚举。`ProtectedInteractionTick` 是纯布尔访问接口，迁入 `common.tinker`；扳手预测和 mixin 不再依赖旧交互实现包。

现有 `HistoryEntry` 是不可变的可回放服务端条目，接口明确包含 `match(ServerLevel)` 和 `apply(ServerLevel)`。因此历史条目允许引用 `ReversibleBlockSnapshot` 和 `BlockEntitySnapshot` 两种快照原语；它不能引用世界事务、租约或提交能力。两种快照自身包含捕获、比较和回放世界的行为，不能仅因包含数据字段就归入 common。

## 守护与测试归属

源布局检查把根 `server.worldwrite` 的租约隔离与三个子包的世界写入能力分开约束。根包仍不能依赖日志或业务编排；三个子包均不能反向调用客户端、网络、会话、命令、意图或设置系统。

历史条目的例外按 snapshot 包下两个完整类型名限定；编译后架构检查也执行同一边界。架构工具回归覆盖事务、提交、租约及近似类型名，防止包级例外放过其他能力。新包都包含职责说明，快照、事务和副作用包分别为 5、5、3 个类型。

相关单元测试迁入对应新包。旧 `fastplace.world` 中的 5 个 GameTest 按实际职责迁入 `server.task.block`、`server.worldwrite.transaction` 和 `client.render.mask`。打包必需条目随实际生产路径更新。

后续加入快照读取注入后，原 block 包达到 13 类，现按上述三项职责分包。事务拥有 `refreshOwnedNeighbors`，快照不再引用事务；快照回放只使用 effect 的写入副作用抑制。package-private 的观测捕获及提交辅助方法仍留在事务包，没有为搬迁扩大可见性。

## 验证与剩余范围

主任务 k 轮统一验证已通过编译、完整 check、打包检查和新世界 GameTest：503 个测试套件、2736 项单元测试，0 失败、0 错误、1 跳过；326 项必需 GameTest 通过。日志为 `.temp/refactor-final-20261115k.log`，新世界为 `build/gametest-refactor-20261115-k`。架构检查无新增债务、严格边界违规为 0；完整严格验收仍因 368 条既有债务失败。

生产旧顶层包已迁空，但整份架构手册尚未完成。剩余架构债务为 154 条客户端包环、134 条超限包、42 条缺包说明、11 条旧后缀和 27 条单类型包；另有 9 类反射或 Unsafe 测试及真实客户端实测待完成。后续边界与回归清单见[客户端架构闭包审计](client-architecture-closure-audit-20261115.md)。
