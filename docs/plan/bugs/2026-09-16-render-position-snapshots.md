# 渲染坐标快照的集合边界

日期：2026-09-16。状态：已完成本页列出的集合替换，完整单元测试通过。

分类：交互体验、资源成本、异步快照隔离。

## 原因与实现

此前复制和源遮罩测试已定位到 JDK 不可变坐标集合的哈希探测热点。继续检查发现，异步预览在提交任务前仍用 `Set.copyOf` 冻结输入。把网格构建移到后台，并不能消除提交前的集合构造成本。

新增 `BlockPositionSets.copyOf`，统一复制集合、冻结可变坐标和返回不可修改结果。它不复用调用方拥有的可修改集合。

接入 `PendingGhostMeshCache`、`GhostMeshCache`、`BuildingShellCache` 的防御性复制分支、`BuildingShellBlocksCache`、`BuildingBlockResult`、`BuildingRenderLayers`、`GeometryRenderLayers` 和 `PreviewBlockOcclusion`。

外壳与遮挡查询中的坐标映射同时改用 `BlockPositionMaps.copyOf`。明确由调用方提供不可变快照的外壳分支仍复用输入。

`BuildingBlockResult` 保留生成超限和生成失败的类型标记。不能把这些标记复制成普通空集合，否则调用方会误判为成功。

## 验证

新增测试修改原始集合与 `MutableBlockPos`，确认分层结果仍保留原坐标。结果集合与迭代器拒绝修改。生成失败标记仍保持原实例，空坐标仍被拒绝。

完整单元测试共 1447 项：1446 项通过，0 失败，0 错误，1 项既有跳过。命令：`gradlew.bat test --console=plain`，用时 36 秒。`git diff --check` 通过。

没有为每个入口单独测量性能，也没有实机帧率证据。此前遮罩和复制的加速倍数不能直接套用到这些入口。

## 未完成事项

`FastPlaceClientPreviewCore` 和体素重叠集合仍存在其他坐标集合冻结入口，需继续检查。`GhostMeshCache` 在构建成功前更新缓存输入，构建抛异常后的重试语义也需独立验证。本轮没有修改该错误路径。

集合的不可修改视图不等于零复制。快照复用、内存峰值、异步生成与实机交互仍需后续验收。

文档检查：STE 0.00 / 100w，中文覆盖有限。
