# 2026-09-16 公共组重复模型审计

范围：`client/operation`（`ClientOperationController` 公共 SCALE、`WorkspaceTransform`、`WorkspacePreviewComposer`、`WorkspaceSelectionBounds`）、`ClientOperationDraftCodec` 及其测试。

本轮不改生产、测试、input、render 或服务端。未运行 Gradle。2026-09-16 只读复核确认 G1 仍在，并否决固定两层方案。repeat-layer 栈只评估过，不是当前推荐。当前推荐是 `2026-09-16-workspace-source-target-model.md` 的 A + GesturePlan。等待预算代理完成 composer/controller/validator 后再整体实施。

## 结论摘要

1. 公共（多部件）SCALE 的"整体作为堆叠单元"模型在写入侧已成立。单元步距写入 `repeatStride`，等于整体选择框的基础范围；一步重复一个整体，等于 `wholeStepCells` 个单元；整体范围来自含空气的选择框 union。见证据 A。
2. 已修复一个可复现的放大错误：基线含棱柱部件时输入给出方块单位的步数，写入侧仍乘整体单元数。
3. G1 已确认仍在：混合点阵下公共手势重排已放置副本。`WorkspaceTransform` 仍只有一个 `(repeats, repeatStride)` 层。
4. 固定两层（M1：内层加一层组重复）只覆盖 G1 的第一次公共组。第三次不同分组会再次改写或延长外层，副本再次移动。repeat-layer 栈评估过同一缺口，仍把 WORLD `blocks` 既当源键又当展开输入，且用顶层 stride 判断同一分组。两者都不是当前推荐。
5. 当前推荐：A + GesturePlan。源引用与目标格网分离。手势结束时烘焙一次。手势作用域用 `GesturePlan` 固定。见 `2026-09-16-workspace-source-target-model.md`。
6. 单部件手柄的多部件作用域仍属 G2 / BUG-AT。GesturePlan 关闭它。本轮不改 input。
7. LOCKED 只禁源 AABB 面编辑，不禁选择与 Gizmo。未选中的变换部件必须能点回。见决策文档 R4 验收。本轮不改 resolver。
8. 本文件是 G1 证据与否决记录。它不是完成记录。A + GesturePlan 未实施。

## 当前模型契约

- 每个部件一个重复层：`WorkspaceTransform(repeats, repeatStride, scale, rotation, translation)`。
- 展开顺序（`WorkspacePreviewComposer.resolveCore`）：scale → repeats（位移 = 重复索引 × |stride|，stride 为 0 时用该部件占用方块宽度）→ 逐轴旋转 → 平移。
- 网络提交：`OperationWorkspacePlan.Part(id, source, blocks, transform, pendingDelete)`。服务端 `OperationWorkspaceValidator.validate` 调用同一个展开函数，并用 WORLD 部件的 `blocks` 键作为源清除坐标。

因此 `blocks` 必须是源位置的内容。把展开结果烘焙进 `blocks` 会让服务端清除目标格而不是源格，产生重复内容。这一点排除了"客户端烘焙整体"的客户端单边方案。

- 草稿：`ClientOperationDraftCodec` 逐字段保存 transform（Translation、Rotation、Scale、RepeatMin、RepeatMax、RepeatStride）。草稿版本 1。计划编解码版本 1。未知版本必须拒绝。草稿抛 `VersionMismatchException`。计划抛 `IOException("Unsupported workspace plan version")`。
- 输入：`RepeatStrideSemantics.stride` 返回一步重复的方块行程。公共组用整体 union，单部件手柄用命中部件的整体框。`RepeatDragQuantizer.copiesForOffset` 把它转成 `totalSteps`（整体单位）。基线含棱柱时 `repeatsWholeGroup` 为假，`totalSteps` 退回方块单位。

## 证据 A：公共第二次堆叠

部件 A：5 宽，位于 x=0..4。部件 B：2 宽，位于 x=10..11。两者已有 `repeats 0..1`、`repeatStride 12`。

- `wholeBox` = union(resolve) = x=0..23，范围 24。盒内含 x=5..9 的组间空气和 x=12..21 的组内空隙。
- `baseBox` = union(resolveBase) = x=0..11，范围 12。
- `wholeStepCells` = 24 ÷ 12 = 2。一次拖动步把重复区间从 0..1 扩到 0..3。
- A 的方块：x=0..4、12..16、24..28、36..40。B 的方块：10、11、22、23、34、35、46、47。
- 第一次堆叠放置的副本（A 的 0..16、B 的 10..23）没有移动。新内容 x=24..47 等于整体 x=0..23 平移 24。

测试：`GroupRepeatTest.commonSecondStackRepeatsTheWholeGroupAndKeepsBothParts`、`GroupRepeatTest.firstCommonStackUsesTheWholeGroupBoxIncludingTheAirBetweenParts`、`WorkspaceSelectionBoundsTest.wholeBoxHoldsPlacedCopiesWhileBaseBoxHoldsOneCell`。

说明：WA-3 之前的写入侧对公共组使用"每部件自己的范围"作为步距（`2026-09-15-architecture-transactions.md` BUG-AO:107-110）。这组测试同时是那次修复的回归证据：旧代码把 A 与 B 的 stride 写成 5 和 2，副本会落到 5..9 与 12..13。

同一组上的第二次堆叠仍走同一点阵，只延长区间。在当前单层模型里它不需要新层。第三次不同分组不能再靠延长同一层。当前推荐改为手势结束时烘焙目标格网，而不是压新层。

## 已修复：E2 方块单位步长被整体单元数放大

触发链：

1. 基线含棱柱部件时 `RepeatStrideSemantics.repeatsWholeGroup` 为假。
2. `FastPlaceClientInput.updateWorkspaceGizmoDrag` 因此发送方块单位的 `totalSteps`（例如 3）。
3. 旧写入侧无条件计算 `groupDelta = totalSteps × cellsPerGroup`。第二次手势的 `cellsPerGroup` 为 2，于是 3 格变成 6 格。

影响：混合组里方盒部件的重复数量被放大，与用户拖动距离不一致。棱柱部件自身的缩放正确。

修复：`ClientOperationController` 只在 `RepeatStrideSemantics.repeatsWholeGroup(operation, baseline)` 为真时乘以 `WorkspaceSelectionBounds.wholeStepCells(...)`，否则保持步数为输入给出的值。

测试：`GroupRepeatTest.mixedPrismGroupKeepsTheBlockValuedStepUnamplified`（重复端点为 4 而不是 7，棱柱缩放仍为 4.0）。

## 未解缺口

### G1 混合点阵：公共手势重排已放置副本（P1，已确认）

触发：一个部件先用单部件手柄堆叠（stride = 自身单元范围），随后与另一个部件一起做公共堆叠。草稿来自 WA-3 之前的构建时同样触发。

复现数字：

- 初始：A（5 宽，0..4）`repeats 0..1`、stride 5 → 方块 0..9。B（10..11）`repeats 0..1`、stride 2 → 方块 10..13。
- 整体 = 0..13，范围 14。基础单元 = 0..11，范围 12。`wholeStepCells` = 14 ÷ 12 = 1。
- 当前结果：两者 stride 被改写为 12，重复区间扩到 0..2。A 的方块变成 0..4、12..16、24..28。B 变成 10、11、22、23、34、35。**A 的 5..9 与 B 的 12..13 消失。**

不可能性证明：要求同时满足"保留 A 的偏移 0 与 5"和"整体 0..13 按 14 刚性重复"。A 的方块偏移必须是 0、5、14、19 等，相邻差为 5、9、5，不是任何单一 stride 的等差序列。当前 `WorkspaceTransform` 只有一个 `(repeats, repeatStride)` 层，无法表达该布局。

回归证据：`GroupRepeatTest.placedCopiesSurviveACommonSecondStackAfterPerPartFirstStacks`，使用 `@Disabled`，断言目标行为（副本保留 + 整体按 14 重复）。启用它需要先落地 A + GesturePlan，不是固定两层，也不是 repeat-layer 栈。

### 固定两层仍失败

M1 给每个部件加 `groupRepeats` 与 `groupStride`。G1 的第一次公共组可以通过：A 保留内层 stride 5，外层 stride 14。

第三次手势选择 A 与 C（C 位于 x=40，宽 3）。A+C 的整体不是 14。

- 改写外层 stride：A 在 14..23 的副本移动。这是上一层的 G1。
- 延长外层 14：新副本跟随 A+B，不跟随 A+C。
- 改写内层：部件自己的点阵消失。

两个字段不能记录三个分组单元。同一组上的第二次堆叠（证据 A）仍走同一点阵，只延长区间。第三次不同分组不能靠再加一层固定字段。当前推荐是烘焙后的目标格网，见决策文档。

### G2 单部件手柄的多部件作用域（归 BUG-AT）

`FastPlaceClientInput.beginWorkspaceGizmoDrag` 保存的基线始终是全部已选部件，`updateTransformGesture` 没有部件 ID。因此单部件手柄的 SCALE 会对组内每个部件按自己的整体框写 stride，而输入只按命中部件量化：组被撕成不同点阵，且下一次公共手势会重排它们。修复需要 `GesturePlan`：手势开始时固定目标 ID 与作用模式。repeat-layer 栈不关闭 G2。本轮不改 input。

### G3 零 stride 的回退宽度不一致（低）

`WorkspaceSelectionBounds.axisAligned` 在 stride 为 0 时用选择框范围，`WorkspacePreviewComposer.resolveValues` 用占用方块宽度。含空气且 stride 为 0 的历史草稿会让手柄框与实际副本不一致。新写入始终写非零 stride，所以只影响旧数据，并且首次新手势后自愈。

## 已评估、已否决：repeat-layer 栈

本节保留评估记录。它不是当前推荐。当前推荐见 `2026-09-16-workspace-source-target-model.md`。

评估目标当时是：任意多次单部件堆叠与公共组堆叠切换时，已放置副本不移动。WORLD `blocks` 仍是源体素。只在 transform 中展开。否决原因：旋转后世界轴包络不能当旋转前 stride。顶层 stride 不能标识同一分组。同一份 `blocks` 仍混用源键与展开输入。G2 需要 GesturePlan，层列表不提供。

每个部件持有 `List<RepeatLayer>`。一层是 `(OperationStackRegion repeats, BlockPos stride)`。现有 5 参数构造器仍表示第 0 层，已有调用点不变。

建议写入规则（未证明对旋转后手势充分，见未决）：

1. 从不改已有层的 stride。
2. 若顶层 stride 等于本次手势单元（公共组用 `baseBox`，单部件用 `resolveBase`），按 `wholeStepCells` 延长该层。
3. 否则压入新层。stride = 本次目标的当前整体包络。repeats 从 origin 起步并走一步。

展开顺序提案：scale，然后第 0 层，然后后续层，然后旋转，然后平移。一层位移 = 该层索引 × 该层 stride。当前管线已经是 scale，然后一层 repeat，然后 rotate，然后 translate。把多层放在 rotate 前，与今天一层的位置相同。旋转后的世界轴包络不能直接当作旋转前的层 stride。见未决。

预算与深度：

- `validTransform` 必须拒绝超过深度上限的层列表。建议上限 8，具体数字未锁定。
- `WorkspaceGeometryCost` 必须把每一层的 `cellCount` 相乘（饱和乘法）。漏乘会低估写入预算。
- 一层的 `ENDPOINT_LIMIT`（128）仍适用。多层乘积必须进入 `WorkspaceGeometryBudget`。

编解码：

- 草稿 `ClientOperationDraft.CURRENT_VERSION` 现为 1。计划 `OperationWorkspacePlanCodec.VERSION` 现为 1。
- 新层列表需要升版本。旧读者把缺失的额外层读成空列表。未知版本必须拒绝，不得静默截断。
- 分阶段：先合入空额外层，使展开与今天一致。控制器随后才可压层。UI 写出非空额外层之前必须先升计划版本。旧服务端拒绝新版本。这是协议要求。

撤销：

- `ClientOperationWorkspace` 按不可变部件快照记录历史。新 transform 字段随下一次 `finishEdit` 快照进入撤销栈。
- 堆叠手势不得改写 `blocks` 或 `sourceSnapshot`。历史按身份为共享方块映射计费。
- 世界历史撤销不在本方案范围内。

不要在未拆源引用时把展开结果写进 WORLD `blocks`。那会让服务端清除目标格。当前推荐的烘焙目标是单独的目标格网，源键不动。拆开双 map 仍不填回缺失的箱子 NBT。BUG-AA 要等身份协议。

## 文件范围（当时 repeat-layer 评估，未改，不是当前实施清单）

必须改的生产文件：

| 文件 | 原因 |
|---|---|
| `WorkspaceTransform.java` | 层列表 |
| `WorkspacePreviewComposer.java` | 多层展开 |
| `WorkspaceSelectionBounds.java` | 包络含每一层 |
| `WorkspaceGeometryCost.java` | 层乘积 |
| `OperationWorkspaceValidator.java` | 深度上限与预算 |
| `ClientOperationController.java` | 延长或压层 |
| `ClientOperationDraft.java` | 草稿版本 |
| `ClientOperationDraftCodec.java` | 层字段 |
| `OperationWorkspacePlanCodec.java` | 计划版本与拒绝未知版本 |
| `WorkspaceInteractionResolver.java` | 命中与描边 |

当前实施清单在决策文档。本表只记录当时评估过的层列表文件，避免与 A + GesturePlan 的文件范围混淆。

## 需要其他所有者配合

- `fastplace`：计划编解码新字段、未知版本拒绝、验证器深度与预算回归。
- `client/input`：单部件手柄的目标部件 ID（或手势计划），用于关闭 G2。
- `client/render`：若落地多层，命中与描边必须包含每一层。`GeometryFrame.repeatStrideCells` 今天只发布一层。

## 本轮已落地的改动与测试

改动（2026-09-16 写入侧，早于本复核）：

- `WorkspaceSelectionBounds`：新增 `wholeBox(parts)`、`baseBox(parts)`、`wholeStepCells(whole, base, axis)`；控制器改用它，公共组的整体/单元框只有一处定义。
- `ClientOperationController.updateTransformGesture`：整体单元步数改用 `wholeStepCells`；只有 `RepeatStrideSemantics.repeatsWholeGroup` 为真时才乘整体单元数。

新增测试：

- `GroupRepeatTest.firstCommonStackUsesTheWholeGroupBoxIncludingTheAirBetweenParts`
- `GroupRepeatTest.commonSecondStackRepeatsTheWholeGroupAndKeepsBothParts`
- `GroupRepeatTest.mixedPrismGroupKeepsTheBlockValuedStepUnamplified`
- `GroupRepeatTest.placedCopiesSurviveACommonSecondStackAfterPerPartFirstStacks`（`@Disabled`，G1 的目标行为）
- `WorkspaceSelectionBoundsTest.wholeBoxHoldsPlacedCopiesWhileBaseBoxHoldsOneCell`

repeat-layer 栈未实施，也不再作为推荐。编解码升版本与双 map 烘焙留给决策文档，且须等预算代理完成后再整体落地。

## 未决（repeat-layer 评估留下的缺口，已由 A + GesturePlan 接管）

下列条目证明层列表不够。它们不是“仍待选模型”。

1. 旋转后的整体包络不能直接当作旋转前的层 stride。当前管线是 scale → repeat → rotate。`WorkspaceSelectionBounds.wholeBox` 返回旋转后的世界轴包络。A 的处理：手势结束时烘焙一次到世界格。拖动增量不烘焙，避免非 90 度累计漂移。
2. 顶层 stride 相等不足以标识同一次分组。A 不比较 stride。分组身份是 `GesturePlan.targetIds` 加烘焙时的包络。
3. 深度上限与层乘积不再需要。目标格网按已烘焙格计数。共享源实例只计一次。两图同时存活必须两笔都计。
4. `GeometryFrame.repeatStrideCells` 今天只发布一层。烘焙后的下一次 STACK 读目标包络，不再叠加未烘焙层。
5. 双 map 拆开源键与目标格。它不填回箱子物品。BUG-AA 仍在。不要从本文件恢复 live 比较。见 `2026-09-16-source-change-adjust-feedback.md`。

## 未完成与实机缺口

- 未运行 Gradle。`@Disabled` 用例仍表达 G1 的目标行为。当前模型无法通过。
- G1 未实现。需要 A + GesturePlan 与协议字段。固定两层不够。repeat-layer 栈已否决。
- G2 未实现：需要 GesturePlan 的目标部件 ID，属 BUG-AT。
- 实机缺口：两个以上部件连续两次堆叠时，检查组内空隙、整体外框、Gizmo 手柄位置和命中范围；含空气选择框；先单部件堆叠再组成公共组；旋转后再做公共组；第三次不同分组。
- 本篇中文草稿的英文机械检查只识别少量英文词，不代表中文表述认证或程序正确性认证。
