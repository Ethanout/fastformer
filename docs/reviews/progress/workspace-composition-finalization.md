# 工作区组合计算的纯领域收口（2026-11-15）

状态：生产与测试改动已实施；本子代理未运行 Gradle、GameTest 或客户端，等待主代理统一集成验证。

## 结构与所有权

- common/workspace/preview 的全部 7 个类型迁入 common/workspace/composition：Composition、CompositionBudget、CompositionCounter、CompositionBatch、WorkspaceScene、WorkspaceSelectionBounds，以及改名后的 WorkspaceComposer。包说明一并迁移，原 preview 包没有剩余 Java 文件。
- WorkspaceSelectionBounds 与 Composer 放在同包：Composer 依赖 geometry 中的 Cost／Budget，若将 Bounds 放入 geometry，会新增 geometry → composition → geometry 环。WorkspaceGeometryCost 的无用 Composer import 已删除。
- WorkspaceComposer 从 607 行缩为 399 行。删除 FRAME_CACHE、RESOLVED_CACHE、弱引用／LRU／总量计数、resolvedForPart、frameForPart、invalidatePartGeometry、prunePartGeometry 和全部 ForTesting 钩子。
- WorkspaceSelectionBounds 直接调用无状态 geometryFrame。组合输出、变换顺序、预算上限、稀疏缩放和旋转支点算法保持原实现。
- OperationContext 不再在清空、断线和 tick 时修改公共组合缓存。WorkspacePointerResolver 继续拥有自身的实例解析缓存，并按 workspace revision／interaction owner 清理。
- SelectionInteractionScene 仍按部件对象身份复用已发布的包络与 Gizmo；未变化场景不会因公共 LRU 删除而每 tick 重建部件。
- main、test、gametest 的整个引用闭包已更新，包括提交校验器、任务、预览、变换、SelectionBaseline 和客户端工作区准备。

## 测试调整

- 组合／包络相关 6 个既有测试迁至同名 composition 测试包；Composer 测试名同步改名。
- 删除只观察静态缓存内部结构的 WorkspacePreviewComposerCacheTest，去掉该测试使用的 Unsafe 和反射。
- 新增 WorkspaceCompositionIsolationTest：独立源的旋转 frame 与组合结果一致、场景重建可复用 slot ID、预算拒绝不影响下一动作、超预算 frame 保留保守包络路径。
- SelectionInteractionSceneTest 新增变换场景关闭后重开并复用 slot 的回归，检查旧快照不变、新 owner 不复用旧几何，以及稳定场景继续复用。
- 删除 ComposerTest 中重复且不属于公共组合器职责的 SourceBlockRenderMask 测试；该能力仍有自己的客户端测试。

## 已执行与待执行验证

已执行：FastCtx 全 src 搜索，旧类名、旧包名、已删缓存 API 均无残余；composition 包无 ForTesting／WeakReference／静态 LRU。git diff --check 退出码 0；Git 仅报告工作区已有行尾转换提示。

尚未执行：编译、单元测试、架构检查、GameTest 与客户端性能观察。建议主代理集成后运行下列定向套件，再按整批阶段执行完整回归：

- common.workspace.composition 下全部测试。
- SelectionInteractionSceneTest、WorkspacePointerResolverTest、OperationContextTest、GroupRepeatTest。
- WorkspaceGeometryBudgetTest、WorkspaceContentPreparerTest、SelectionDeletionPlanTest、OperationWorkspaceValidatorTest。
- 选区变换／旋转相关 GameTest、verifySourceLayout 与 verifyArchitecture。

本次不修改架构债务基线，不提交、不推送。整份手册的其他缺口仍以完成度审计及主进度为准。
