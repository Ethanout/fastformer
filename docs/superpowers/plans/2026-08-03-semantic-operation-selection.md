# Semantic Operation Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 ready 选区无需 Enter 即可使用半透明 Gizmo，并以可撤回的连续整体 MOVE/STACK 调整替代单个 `stackVector`。

**Architecture:** 用 `OperationStackRegion` 保存每轴重复区间，用独立的 transform history 隔离选区编辑撤回和调整撤回。客户端输入只决定语义路由；服务端 `OperationSession` 负责事务基线、连续 stack 重算和权威状态，预览与世界任务共享相同格阵枚举。

**Tech Stack:** Java 21、NeoForge 1.21.1、JUnit 5、Minecraft `BlockPos`/`AABB`、现有 `AxisGizmo` 和自定义 payload。

## Global Constraints

- 不使用 mixin，不改变近距离交还原版的边界。
- 棱柱左键不设点；AABB 左键不做普通撤回。
- 中键、右键语义保持不变。
- Enter 不再确认阶段。
- ready 时显示 Gizmo；首次非零变换进入调整状态。
- 调整历史不得穿透到选区编辑历史。
- repeat 只沿世界 XYZ；同轴连续操作线性增减重复数，不同轴区间形成笛卡尔积。
- 不清理或回退当前脏工作树。

---

### Task 1: Stack Region Value Model

**Files:**
- Create: `src/main/java/io/github/fastformer/fastplace/OperationStackRegion.java`
- Test: `src/test/java/io/github/fastformer/fastplace/OperationStackRegionTest.java`

**Interfaces:**
- Produces: `OperationStackRegion.origin()`, `repeat(axis, direction, copies)`, `cellCount()`, `repetitions(limit)`, `min()`, `max()`.

- [ ] **Step 1: Write failing tests** covering origin, same-axis `+X 3` then `+X 1 == 5 cells`, cross-axis Cartesian multiplication, negative expansion, zero-copy identity and overflow-safe count.
- [ ] **Step 2: Run** `./gradlew.bat --no-daemon test --tests io.github.fastformer.fastplace.OperationStackRegionTest --console=plain`; expect compilation/test failure because the type does not exist.
- [ ] **Step 3: Implement immutable interval expansion.** Each axis span is `max-min+1`; positive expansion adds `span*copies` to max, negative expansion subtracts it from min. Clamp endpoints to the existing operation limit and calculate counts with saturating multiplication.
- [ ] **Step 4: Re-run the focused test** and expect PASS.

### Task 2: Separate Transform Transactions and History

**Files:**
- Modify: `src/main/java/io/github/fastformer/fastplace/OperationSession.java`
- Test: `src/test/java/io/github/fastformer/fastplace/OperationSelectionSessionTest.java`

**Interfaces:**
- Consumes: `OperationStackRegion`.
- Produces: `operationReady()`, `adjustmentStarted()`, `stackRegion()`, `beginTransform(operation, axis, direction)`, `updateTransform(totalSteps)`, `finishTransform()`, `undoAdjustment()`.

- [ ] **Step 1: Add failing session tests** for ready-without-confirmation, first transform entering adjustment, one drag producing one history entry, drag-to-zero identity, stack baseline recomputation, stack then move preserving the region, and last undo leaving selection points unchanged.
- [ ] **Step 2: Run focused session tests** and confirm failures reference missing APIs or old confirmation behavior.
- [ ] **Step 3: Replace transform use of `selectionConfirmed`** with `selectionReady()` plus explicit `adjustmentStarted`. Keep selection snapshots and transform snapshots in separate deques. Stage selection remains transient and is not written into either snapshot.
- [ ] **Step 4: Add a drag baseline record** containing operation, axis, direction, starting translation/region/rotation and current total steps. `updateTransform` always recomputes from the baseline; `finishTransform` pushes one transform snapshot only if changed.
- [ ] **Step 5: Run focused tests** and expect PASS.

### Task 3: Payload and Server Manager Wiring

**Files:**
- Modify: `src/main/java/io/github/fastformer/network/OperationTransformPayload.java`
- Modify: `src/main/java/io/github/fastformer/network/OperationPreviewPayload.java`
- Modify: `src/main/java/io/github/fastformer/network/FastPlaceNetwork.java`
- Modify: `src/main/java/io/github/fastformer/fastplace/OperationManager.java`
- Modify: `src/main/java/io/github/fastformer/fastplace/ServerInputDispatcher.java`
- Test: `src/test/java/io/github/fastformer/network/OperationTransformPayloadTest.java`
- Test: `src/test/java/io/github/fastformer/network/OperationPreviewPayloadTest.java`

**Interfaces:**
- Payload transform fields: operation, axis, direction, totalSteps, finish.
- Preview fields: stack region min/max and `adjustmentStarted`; remove confirmation as a client phase gate.

- [ ] **Step 1: Write failing codec/validation tests** for direction, absolute total steps, stack-region round trip and adjustment flag.
- [ ] **Step 2: Run focused network tests** and expect signature/codec failures.
- [ ] **Step 3: Update payload codecs and bump protocol version** because the wire layout changes.
- [ ] **Step 4: Route start/update/finish into session transform transactions.** Empty-left adjustment undo calls `undoAdjustment`; selection undo remains a separate manager method.
- [ ] **Step 5: Run focused network and session tests** and expect PASS.

### Task 4: Semantic Client Input Routing

**Files:**
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientInput.java`
- Test: `src/test/java/io/github/fastformer/client/OperationInputSemanticsTest.java`

**Interfaces:**
- Produces pure routing helpers for left press: selection mode, ready state, adjustment state and Gizmo hit map to `VANILLA`, `SELECTION_UNDO`, `ADJUSTMENT_UNDO`, or `GIZMO_DRAG`.

- [ ] **Step 1: Write failing pure routing tests** for prism/AABB before and after adjustment and for Gizmo precedence.
- [ ] **Step 2: Run focused test** and confirm missing helper failure.
- [ ] **Step 3: Implement routing helper and apply it in both key-mapping and raw mouse callbacks.** Remove prism left point creation/selection/drag paths; preserve right/middle paths. AABB left never emits undo before adjustment.
- [ ] **Step 4: Change Enter** to execute only when an adjustment exists; remove confirmation packet behavior for operations.
- [ ] **Step 5: Send absolute drag totals plus direction** for operation Gizmo transforms.
- [ ] **Step 6: Run focused input tests** and expect PASS.

### Task 5: Gizmo Opacity and Continuous Preview

**Files:**
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientPreview.java`
- Modify: `src/main/java/io/github/fastformer/client/GizmoViewScale.java` only if a shared center-hit radius is needed.
- Test: `src/test/java/io/github/fastformer/client/OperationGizmoPresentationTest.java`

**Interfaces:**
- Produces pure `operationGizmoAlpha(nearCenter)` returning `1.0F` or `0.5F` and shared stack repetition preview enumeration.

- [ ] **Step 1: Write failing presentation tests** for 50/100 percent alpha, ready selection Gizmo availability, and stack-region preview offsets.
- [ ] **Step 2: Run focused tests** and confirm failures.
- [ ] **Step 3: Show Gizmo whenever selection is ready** in an operation-capable stage. Determine center proximity from ray-to-center distance using the view-scaled center radius.
- [ ] **Step 4: Multiply all operation Gizmo lines, solids and rings by the overall alpha** without changing hover/active color selection.
- [ ] **Step 5: Render selection and all repetitions using stack region plus translation.** The displayed AABB center is the current whole-region center.
- [ ] **Step 6: Run focused presentation tests** and expect PASS.

### Task 6: World Task Uses the Same Region

**Files:**
- Modify: `src/main/java/io/github/fastformer/fastplace/OperationManager.java`
- Modify: `src/main/java/io/github/fastformer/fastplace/geometry/OperationGeometry.java`
- Test: `src/test/java/io/github/fastformer/fastplace/OperationStackPlacementTest.java`

**Interfaces:**
- Consumes: `OperationStackRegion.repetitions` and translation.
- Produces: shared `stackDisplacement(bounds, repetition)` targets for validation, journal prediction and placement.

- [ ] **Step 1: Write failing target tests** for `+X3` then `+X1`, negative stacking, XYZ stacking and translation of the complete region.
- [ ] **Step 2: Run focused tests** and confirm old `stackVector` behavior fails.
- [ ] **Step 3: Replace task repeat cursors with region iteration.** Include the origin when moving the whole composite; skip the original origin only when it remains unchanged and copy semantics require it.
- [ ] **Step 4: Use region cell count in max-placement and memory checks** with division-before-multiplication overflow protection.
- [ ] **Step 5: Use identical target enumeration** in validate, journal prediction and place phases.
- [ ] **Step 6: Run focused placement tests** and expect PASS.

### Task 7: Documentation and Full Verification

**Files:**
- Modify: `docs/session_design.md`
- Modify: `docs/input_compatibility.md`
- Modify: `src/main/resources/assets/fastformer/lang/zh_cn.json`
- Modify: `src/main/resources/assets/fastformer/lang/en_us.json`

- [ ] **Step 1: Update interaction documentation** with prism/AABB left semantics, automatic ready Gizmo, adjustment undo and continuous stack examples.
- [ ] **Step 2: Run** `./gradlew.bat --no-daemon compileJava test --console=plain`; expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Run** `./gradlew.bat --no-daemon deployTo233 --console=plain`; expect jar verification and deployment success.
- [ ] **Step 4: Report epistemic boundary:** automated tests and deployment do not prove in-game acceptance; list the exact client acceptance gestures still required.
