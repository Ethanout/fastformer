# 2026-09-13 配置策略静态审计

## 范围

本记录只覆盖 `FastPlaceSettings` 的配置表达、默认值、NBT 迁移和运行时读取边界。它不是客户端实机验收，也不表示“语义事件与阶段状态统一”已经完成。

## 结论

- 几何模式、冲突模式、选区模式和放置更新模式使用枚举字段，并通过 `readEnum` 在缺失或非法值时回退到明确默认值。
- `operationSelectionMode=CONVEX_HULL` 在读取和设置时都迁移到 `CUBOID`，避免恢复已移除的模式。
- `enabled`、`middleConfirmEnabled` 和 `emptyHandWrench` 是独立的持久化开关；当前没有发现它们被组合推导阶段语义的代码证据。
- `middleConfirmEnabled` 只表达中键确认能力。当前配置没有“Enter 是否提交”或等价的提交策略字段，因此 TODO 中的 Enter/滚轮统一语义仍需由输入与阶段状态审计完成，不能通过新增布尔值推断。
- `maxPlacement`、两种历史上限和角度在读取及设置入口进行范围/有限性校验；NBT 版本号或显式迁移版本字段目前未发现，因此未来新增配置键仍需补充迁移策略和回归测试。

## 证据与剩余工作

`FastPlaceSettingsTest` 已覆盖默认值、枚举往返、非法枚举回退、旧 `smartWoodFrame` 迁移和历史上限钳制。上述测试不能证明输入状态机、Enter 提交或客户端显示顺序已完成；这些项目保留在 `current_todo.md`，等待对应实现和客户端验收证据。
