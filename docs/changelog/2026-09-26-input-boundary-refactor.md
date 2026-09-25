# 输入请求与拖动调度拆分

- 新增 `InputRequestSequence`，集中检查服务端请求号严格递增。旧请求和重复请求不能将记录倒退。
- 选点与插点请求通过环境和会话检查后才占用请求号。几何与快速起形指针请求也先核对 scope 和 revision，再记录请求号。
- 新增 `PointerDragTicker`，集中执行每 tick 的拖动所有者选择与更新。`FastPlaceClientInput` 保留 NeoForge 事件入口。
- 删除依赖未初始化游戏注册表的遮罩单元测试断言。现有 `SourceMaskOcclusionGameTests` 在游戏环境中检查相同的遮罩行为。
- 光照 GameTest 不再假定共享测试世界的原始亮度固定为 0，继续检查预览各面使用完整亮度。

验证：`check` 通过。`runGameTestServer` 的 110 项必需测试通过。客户端实机交互未验证。
