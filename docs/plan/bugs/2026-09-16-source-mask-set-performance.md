# 源遮罩集合冻结导致长时间停顿

日期：2026-09-16。状态：遮罩路径已修复，13 项定向测试通过。

分类：交互体验、资源成本。

## 证据与修复

新增测试构造 110000 个源方块，提取遮罩，发布两次相同内容，再清空遮罩。修复前单项耗时 65.747 秒。

运行期间的线程栈位于 `ImmutableCollections$SetN.probe`，经 `Set.copyOf` 进入 `SourceMaskRenderFilter.immutableCopy`。当时线程已运行约 24 秒，CPU 时间约 23 秒。源位置提取也使用同一类集合冻结。

`SourceBlockRenderMask.maskedSourcePositions` 和 `SourceMaskRenderFilter.immutableCopy` 现在返回各自独占集合的不可修改视图。集合构造完成后没有外部可修改引用，位置仍冻结为不可变坐标。此处不改变遮罩的渲染语义。

## 验证

修复后同项测试耗时 0.257 秒。测试检查内容数量、位置查询、相同内容不增加修订号，以及清空当前遮罩后旧快照仍保持原内容。结果集合和旧快照集合均拒绝修改。

13 项遮罩测试全部通过。命令：`gradlew.bat test --tests '*SourceBlockRenderMaskTest' --tests '*SourceMaskRenderFilterTest' --console=plain`。`git diff --check` 通过。

计时来自单元测试，不代表实际游戏帧率。本轮没有重新运行完整单元测试或 GameTest。

## 剩余架构检查

渲染缓存中仍有位置集合的 `Set.copyOf`，包括 `PendingGhostMeshCache`、`GhostMeshCache`、`BuildingRenderLayers`、`GeometryRenderLayers` 与 `PreviewBlockOcclusion`。需要检查其输入规模和快照所有权，再替换集合边界。

不能直接返回输入集合，因为异步网格任务必须持有不会随客户端操作改变的输入。本页只修复已定位的遮罩路径，没有证明其他集合入口性能合格。

文档检查：STE 0.00 / 100w，中文覆盖有限。
