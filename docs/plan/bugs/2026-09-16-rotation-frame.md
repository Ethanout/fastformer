# 2026-09-16 旋转坐标系审计（旋转后放置线框失效、线框与方块面分离）

范围：`client/operation` 的 `VoxelRotation`、`WorkspacePreviewComposer`、`WorkspaceSelectionBounds`，
以及 `client/render` 读取这些边界的路径。
未修改输入、服务端、网络、协议。
判定依据是当前工作树代码。

## 结论

渲染外框与 Gizmo 的中心，与体素旋转的中心不一致。共两处独立偏差，都已在本次修复中统一。

## 证据

### E-1 两处使用了不同的中心来源

- 体素路径：`WorkspacePreviewComposer.resolveValues` 的旋转阶段。中心取当前占用方块的整数包围盒中心：
  `WorkspacePreviewComposer.java` 原第 57-59 行 `OccupiedBlockBounds.from(rotated.keySet()).center()`，
  并由 `VoxelRotation.rotateValues(source, pivot, axis, radians)` 使用。
- 渲染路径：`WorkspaceSelectionBounds.rotatedCuboid` 原第 110-136 行。中心取**选区包围盒**中心
  `base.getCenter()`。调用者是 `WorkspaceInteractionResolver.selectionBounds:346-356` 与
  `interactionBounds:391-399`，两者都优先使用选区边界。
- 含空气选区时两者不同。`WorkspaceInteractionResolver:384-390` 的注释明确要求 Gizmo 与命中区跟随
  选区范围，因此渲染侧不会改用占用方块的范围。

### E-2 多轴旋转的中心规则不同

- 体素路径对每个轴**重新**从当前结果计算中心（原 `resolveValues` 的轴循环）。
- 渲染路径 `rotatedCuboid` 对三个轴只用一个固定中心（原第 152 行 `Vec3 pivot = base.getCenter();`）。
- 因此即使选区不含空气，只要旋转包含两个以上轴，两者就会分离。这就是“旋转矩阵后放置线框失效”。

### E-3 数值验证（半圈绕 Y，方盒 x=0 至 4，仅 (0,0,0) 一个方块）

- 显示中心（选区）为 `(2.5, 0.5, 0.5)`，占用中心为 `(0.5, 0.5, 0.5)`。
- 绕占用中心转 90 度：外框落到 x 0..1、z -4..1，方块仍在 (0,0,0)，两者贴合。
- 绕选区中心转 90 度：外框落到 x 2..3，方块 (0,0,0) 落在框外，偏移 2 格。
- 该偏移量大于体素离散化最多 1 格的误差，因此可稳定区分。
- 同一偏差已由上一轮记录，见 `docs/plan/bugs/2026-09-15-architecture-transactions.md` BUG-AM 第 126-142 行。
  该记录还指出：`OperationWorkspaceValidator` 提交时同样调用 `resolveValues`，因此中心选择进入实际目标计划。

## 统一坐标契约（已实施）

**契约：体素管线是唯一权威。它发布自己使用的几何帧，所有渲染消费者按该帧变换选区外框。**

`WorkspacePreviewComposer.GeometryFrame` 三个字段：

1. `scaleAnchor`：缩放阶段的锚点中心。非空表示该阶段真的改变了尺寸。
2. `repeatStrideCells`：重复阶段的单次步距格数。非空表示存在重复。
3. `rotationSteps`：按应用顺序排列的 `VoxelRotation.RotationStep(axis, radians, pivot)`。

规则：

- 缩放锚点、重复步距、每个轴的旋转中心都由体素管线计算一次并发布。渲染侧不再自行从选区边界推导。
- 渲染侧把外框的八个角依次施加 `rotationSteps`，与方块使用同一函数 `VoxelRotation.rotatePoint`。
- 超出渲染预算时不发布帧（`geometryFrame` 返回 null），渲染侧保留原有的单中心外框。这是有意的降级。
- 选择分区大小不变，仍按选区尺寸缩放以保留空气余量；只有锚点改用管线的中心。

## 实施

- `client/operation/transform/VoxelRotation.java`
  - 新增 `RotationStep`、`RotationResult`、`rotateStage(Map, Vec3)`、`rotatePoint(Vec3, Vec3, Axis, double)` 与常量 `POSITION_EPSILON`。
  - `rotateStage` 就是原来 `resolveValues` 的轴循环，逐轴重算中心，并记录中心。体素结果不变。
  - `rotatePoint` 是唯一旋转公式，外框与方块共用。
- `client/operation/preview/WorkspacePreviewComposer.java`
  - 轴循环改为调用 `VoxelRotation.rotateStage`，因此中心规则只有一个来源。
  - 新增 `geometryFrame(source, transform)` 与 `GeometryFrame` 记录。
  - `resolveValues` 行为不变，改为委托私有 `resolveCore`。
- `client/operation/preview/WorkspaceSelectionBounds.java`
  - `axisAligned` 接受帧：用 `scaleAnchor` 作为中心，用 `repeatStrideCells` 作为步距。
  - `rotatedCuboid` 接受帧：按 `rotationSteps` 逐轴变换八个角。无帧时保留原单中心行为。
  - 新增包内可见 `transformedBox(AABB, WorkspaceTransform, GeometryFrame)` 作为契约测试入口。

## 回归测试

- `src/test/java/io/github/fastformer/client/operation/transform/VoxelRotationFrameTest.java`
  - `secondAxisPivotsOnTheCentreTheFirstAxisProduced`：第二轴中心等于第一轴结果的中心，且与第一轴中心不同。
  - `rotationStageMatchesApplyingEachAxisInOrder`：发布的结果与手工逐轴应用一致。
  - `rotationStageReportsNoStepForAnUnrotatedTransform`。
  - 修正记录：该测试第一版用 L 形三格集合，其包围盒中心在自己的 90 度 Y 旋转下不变，
    因此“中心应当改变”的假设不成立，第 41 行断言失败。生产实现正确。
    已改用两格直线集合，其包围盒中心在旋转后确实移动（(1.0,0.5,0.5) 变为 (1.5,0.5,1.0)），
    断言同时覆盖“第二轴重算中心”与“两个中心不同”，不再依赖偶然的对称性。
- `src/test/java/io/github/fastformer/client/operation/preview/RotationFrameContractTest.java`
  - `quarterTurnEnvelopeRotatesAboutTheOccupiedCentreNotTheSelectionCentre`：精确断言 x 0..1、z -4..1。
  - `multiAxisEnvelopeUsesEveryPublishedStepAndStaysOnTheBlocks`：帧的三个步骤与管线一致，外框覆盖方块。
  - `scaledEnvelopeAnchorsOnTheOccupiedCentre`、`repeatedEnvelopeUsesThePipelineCellStride`。
  - `identityTransformPublishesNoFrame`。
  - 覆盖要求中的含空气选区、重复、缩放与多轴组合。容差一格，原因写在测试类注释中。

## 未做的修改与理由

- 未改变体素中心规则本身。`OperationWorkspaceValidator`（`fastplace`）用 `resolveValues` 生成提交计划，
  改它会改变实际放置结果，超出本次范围且需要服务端计划一并验证。
- 未修改 `client/render` 代码。`WorkspaceInteractionResolver` 继续调用 `WorkspaceSelectionBounds`，
  修复在这条路径内自动生效。
- 未修改输入、服务端、网络、协议。

## 验证状态（重要）

- 生产代码编译已验证：全量 Gradle 曾在包含本次三个生产文件与两个测试文件的代码上完成编译并执行测试，
  失败点只是测试断言（见下）。因此本轮改动的编译问题已被排除。
- `RotationFrameContractTest` 在该次执行中未被报告为失败。`OperationPreviewRendererTest` 的失败已在更早一轮修复。
- `VoxelRotationFrameTest` 的修正结果**尚未执行验证**：随后 Gradle 不可用
  （Wrapper 下载 Gradle 9.2.1 被网络拒绝；本机缓存离线解析缺 `gson-2.10.1` 元数据），
  本次未产生新的测试结果。修正后的算术已逐步手工核对。
- 最近一次落盘结果位于 `build/test-results/test/`，报告时间 `2026-09-16 01:56:38 +08:00`。
- 环境恢复后需要重跑：`VoxelRotationFrameTest`、`RotationFrameContractTest`，
  以及既有 `VoxelRotationTest`、`WorkspaceSelectionBoundsTest`、`WorkspacePreviewComposerTest`。

## 实机缺口

- 未启动客户端。需要实机确认：旋转后外框与 Gizmo 贴合方块面；多轴旋转后仍贴合；缩放与重复后仍贴合。
- 提交一致性仍需实机验证：目标计划由 `OperationWorkspaceValidator` 生成，本次未改动它。
- 未运行 Gradle。测试由主代理统一执行。
- `WorkspaceSelectionBounds.java` 在本轮期间曾被另一代理改动（新增 `wholeBox`、`baseBox`、`wholeStepCells`、
  `unionBoxes`）。本次编辑已避开这些方法。
