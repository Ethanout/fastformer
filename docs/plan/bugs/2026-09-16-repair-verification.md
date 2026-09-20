# 2026-09-16 修复结果复核

最新检查见 [审计验证状态](2026-09-16-verification-status.md)。单元测试通过，但 GameTest 有 18 项必需测试失败，整体审计未完成。

## 历史工作树验证记录

- 最新验证：主代理修正两个错误的历史测试断言后，`gradlew test --console=plain` 成功。随后串行执行 `gradlew check build --console=plain` 成功，包含 `verifyModJar`。旋转缓存、历史预算边界与重连超时审计仍未交付完整修复，不能据此关闭这些问题。
- 旋转缓存交付后，定向缓存、选择外框和控制器测试成功。随后重新执行 `gradlew test --console=plain` 成功，`gradlew check build --console=plain` 也成功并完成 `verifyModJar`。缓存预算测试覆盖帧上限、解析总方块上限、同 ID 新实例失效和独立淘汰。仍没有客户端实机帧时间证据。

- 后续旋转与历史重构的全量测试执行 1198 项：2 项失败，1 项跳过。失败位于 `ClientOperationWorkspaceTest` 的 no-op 与预算断言，修复进行中。此结果取代下方较早的全量成功结论。
- 修复测试同步任务的 `work/**` 保留规则后，在默认临时目录定向运行 `VoxelRotationFrameTest`、`RotationFrameContractTest`、`WorkspaceSelectionBoundsTest` 成功。只证明这三个测试类的覆盖范围，不代表实机验收完成。
- 后续静态审计发现旋转外框在多个每帧消费者中重复展开体素。已安排复用解析结果与有界缓存的修复，并检查无旋转缩放分支是否遗漏实际变换中心。修复未交付前，不标记旋转架构审计完成。

- 服务端代理追加 `WorldWriteCoordinatorTest` 时截断了旧测试方法的结束部分，主代理补回清理断言与花括号。随后完整执行 `gradlew test --console=plain`，结果成功。该次执行覆盖当前工作树的生产与测试源码，包括 SR-1/SR-2、草稿加载、输入与渲染测试；仍不替代客户端实机验收。

- 渲染代理确认棱柱面中心失败来自测试算术错误：三个顶点的 x 均值应为 `8/3`，不是 `4/3`；生产算法未改。代理另修复双层 shell 路径漏乘全局淡出透明度。四个渲染测试类的定向重跑在服务端并发接口迁移期间止于主源码编译，尚无本轮渲染测试执行结果；等待服务端交付后统一重跑，不能记为通过。

- 草稿加载代理补上无客户端实例时的提示保护和三个中英文提示键；新增重试恢复测试遗漏两个导入，主代理已补齐。随后 `gradlew test --tests '*ClientDraftLoadTest' --console=plain` 成功。该定向结果不替代等待其他模块完成后的全量验证。

- 已确认并删除 `ClientOperationController.updateTransformGesture` 中重复的 `groupSelectionBounds` 声明。此前完整测试在主源码编译阶段停止。
- 删除重复声明后运行 `gradlew test --console=plain`：主源码和测试源码均编译通过；共执行 1157 项测试，7 项失败，1150 项通过。
- 失败包括 `ClientDraftLoadTest` 的六个空指针异常，以及 `OperationPreviewRendererTest.prismFaceCenterSitsHalfAnExtrusionAboveTheBaseCentroid` 的断言失败。已交给对应原代理定位；尚未验证修复。
- 此结果只证明当前编译与自动化测试状态，不代表游戏内验收通过。下面保留此前复核记录供追踪。

本轮复核发现，上一轮新增测试不能直接作为修复完成的证据。

## 已修正

- `WorkspaceContentPreparerTest` 把 `BlockPos` 传给要求 `Vec3` 的平移接口，导致测试编译失败。现改用 `Vec3`。
- 同一测试调用 `Set.getFirst()`，导致测试编译失败。现用 `containsKey` 检查保留的源坐标。
- `HudFadeTimerTest` 原回退测试全部落在保持期内，无法发现重复消耗时间。现检查淡出期回退、恢复原时间、继续前进三个阶段，预期透明度依次为 128、128、64。

## 尚未完成

- `WorkspaceGeometryCost` 使用源体素数量限制缩放输出数量，低估最近邻放大的输出。已交给几何代理修正，并要求复核消费者。
- 验证器的部分预算测试使用待删除部件，绕过被测预算分支。需改为覆盖实际生产路径的测试。
- Minecraft 映射任务本次已通过。首轮复核在测试编译阶段发现上述两个错误，未运行测试。修正后运行 HUD、内容准备和交互解析三个测试类，共 29 项，28 项通过，1 项失败。失败项为 `WorkspaceInteractionResolverTest.failedOutlineSurfaceDoesNotResolveAsASelectionTarget`，仍需定位。

这些结论不表示全部审计或游戏内验证完成。文稿检查得分为 0.00，但英文检查器对中文的覆盖有限。

## 选择测试复核补充

失败测试的射线同时进入部件标签命中范围。因此，返回部件意图不证明失败表面过滤失效。测试已改用避开标签的射线，并检查同一射线在无失败坐标时仍能选中部件。

重跑在测试编译阶段停止：几何代理正在更换预算接口，配套测试尚未更新完成。这是并发修改的中间状态，不能据此判断最终修复失败。等待接口与测试同步后统一运行。

另已安排两路独立修复：标签与准星提示的一致性，以及剪贴板保存失败时的内存状态。代理完成报告仍需代码复核和测试支持。
