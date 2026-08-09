# Client Multi-selection Transform Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a client-edited, ten-part operation workspace with local/group transforms, Axiom-style block previews, persistent copy/paste, staged deletion, and server-validated atomic application.

**Architecture:** `ClientOperationWorkspace` owns all ephemeral editing state and history. Pure planners resolve transforms, occupied bounds, voxel rotations, overlap composition, and paste anchors identically for preview and submit. The server receives a compact workspace plan only on apply, re-reads world sources, validates untrusted clipboard data, and executes through the existing recovery-journal path.

**Tech Stack:** Java 21, NeoForge 1.21.1, Minecraft block-state/NBT codecs, JUnit 5, existing `AxisGizmo`, custom payloads and world-operation journal.

## Global Constraints

- At most ten parts; Ctrl+0 addresses slot 10 and new parts reuse the smallest free slot.
- Editing, selection, history, Gizmo feedback and clipboard state are client-only.
- Server authority remains only for permission, current-world validation, limits, recovery and writes.
- All geometric spacing uses actual occupied integer block bounds, never floating prism bounds.
- Preview and submission share the same transform/voxel/composition functions.
- Do not commit or clean the existing dirty worktree.

---

### Task 1: Pure workspace state and command history

**Files:**
- Create: `src/main/java/io/github/fastformer/client/operation/ClientSelectionPart.java`
- Create: `src/main/java/io/github/fastformer/client/operation/ClientOperationWorkspace.java`
- Create: `src/main/java/io/github/fastformer/client/operation/WorkspaceTransform.java`
- Test: `src/test/java/io/github/fastformer/client/operation/ClientOperationWorkspaceTest.java`

**Interfaces:**
- `ClientSelectionPart(int id, PartSource source, Map<BlockPos, BlockSnapshot> blocks, WorkspaceTransform transform, boolean pendingDelete)`
- `ClientOperationWorkspace.addPart(...)`, `removeSelectedParts()`, `toggleSelected(int)`, `selectOnly(int)`, `selectAll()`, `undo()`
- `ClientOperationWorkspace.apply(WorkspaceCommand)` records one before/after snapshot per complete gesture.

- [ ] Write failing tests for slots 1..10, smallest-hole reuse, atomic capacity rejection, Ctrl selection semantics, active id, selected removal and one-command undo.
- [ ] Run `./gradlew.bat --no-daemon test --tests io.github.fastformer.client.operation.ClientOperationWorkspaceTest --console=plain` and verify RED.
- [ ] Implement immutable part values and snapshot-based workspace commands without Minecraft client singletons.
- [ ] Run the focused test and verify GREEN.

### Task 2: Occupied bounds, local/group transforms and rotation

**Files:**
- Create: `src/main/java/io/github/fastformer/client/operation/OccupiedBlockBounds.java`
- Create: `src/main/java/io/github/fastformer/client/operation/PixelPerfectAngles.java`
- Create: `src/main/java/io/github/fastformer/client/operation/WorkspaceTransformPlanner.java`
- Create: `src/main/java/io/github/fastformer/client/operation/VoxelRotation.java`
- Test: `src/test/java/io/github/fastformer/client/operation/WorkspaceTransformPlannerTest.java`
- Test: `src/test/java/io/github/fastformer/client/operation/VoxelRotationTest.java`

**Interfaces:**
- `OccupiedBlockBounds.from(Collection<BlockPos>)`, `union(...)`, `width(Axis)`, `center()`
- `PixelPerfectAngles.snap(double radians)` considers all reduced `atan2(a,b)`, `a,b in 0..10` across the full circle.
- `WorkspaceTransformPlanner.childMove/groupMove`, `childRepeat/groupRepeat`, `childRotate/groupRotate`
- `VoxelRotation.rotate(Map<BlockPos, BlockSnapshot>, Vec3 pivot, Quaternionf rotation)` returns deterministic destination voxels.

- [ ] Write failing tests for prism-like sparse occupancy, group union, five-copy same-axis repeat, group repeat spacing, local versus common pivots, 90-degree exact rotation and Alt angle snapping.
- [ ] Run both focused test classes and verify RED.
- [ ] Implement bounds and planners; use inverse destination sampling for non-orthogonal rotations and stable source-coordinate tie breaking.
- [ ] Rotate block states only for exact quarter turns; preserve state for non-orthogonal rotations.
- [ ] Run focused tests and verify GREEN.

### Task 3: Transform mode, two-level Gizmos and input routing

**Files:**
- Modify: `src/main/java/io/github/fastformer/fastplace/OperationStageMode.java`
- Modify: `src/main/java/io/github/fastformer/client/OperationGizmoPresentation.java`
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientInput.java`
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientPreview.java`
- Create: `src/main/java/io/github/fastformer/client/operation/WorkspaceInputSemantics.java`
- Test: `src/test/java/io/github/fastformer/client/operation/WorkspaceInputSemanticsTest.java`
- Modify: `src/test/java/io/github/fastformer/client/OperationGizmoPresentationTest.java`

**Interfaces:**
- `OperationStageMode.TRANSFORM` exposes MOVE, SCALE-as-REPEAT and ROTATE together; modes are TRANSFORM/SWEEP/LOFT.
- `WorkspaceInputSemantics.hitRoute(ctrl, targetKind)` distinguishes world creation from part/Gizmo/label selection.
- A `WorkspaceGizmoTarget(partId, common)` identifies child versus public semantics.

- [ ] Write failing tests for merged operations, Ctrl hit priority, selected/unselected drag, Alt consumed gesture, Ctrl+A, Ctrl+digits and Delete routing.
- [ ] Run focused tests and verify RED.
- [ ] Rename stage values and migrate payload compatibility together with a protocol bump.
- [ ] Bind key and mouse routes to `ClientOperationWorkspace`; suppress per-drag transform payloads.
- [ ] Build one Gizmo per part plus a common center Gizmo when selected count is greater than one.
- [ ] Run focused tests and verify GREEN.

### Task 4: Exact block preview and deletion presentation

**Files:**
- Create: `src/main/java/io/github/fastformer/client/operation/WorkspacePreviewPlan.java`
- Create: `src/main/java/io/github/fastformer/client/operation/WorkspacePreviewComposer.java`
- Create: `src/main/java/io/github/fastformer/client/operation/SourceBlockRenderMask.java`
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientPreview.java`
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientShaders.java`
- Create: `src/main/resources/assets/fastformer/shaders/core/operation_delete_lines.json`
- Create: `src/main/resources/assets/fastformer/shaders/core/operation_delete_lines.vsh`
- Create: `src/main/resources/assets/fastformer/shaders/core/operation_delete_lines.fsh`
- Test: `src/test/java/io/github/fastformer/client/operation/WorkspacePreviewComposerTest.java`

**Interfaces:**
- `WorkspacePreviewComposer.compose(workspace)` returns source ghosts, breathing targets, delete overlays, overlap edges, child frames and the common frame.
- `SourceBlockRenderMask.contains(dimension, pos)` is queried by the block render hook and cleared on cancel/disconnect.

- [ ] Write failing tests for moved-source masking, copy-source preservation, transformed target blocks, delete overlay membership, higher-id overlap precedence and mask cleanup.
- [ ] Run the focused test and verify RED.
- [ ] Compose model-level preview from the same resolved voxel map used for submission.
- [ ] Render source blocks translucent, targets with breath alpha, pending deletion with animated red diagonal UVs, and the common box with platinum moving dashes/corner brackets.
- [ ] Add the narrow client block-render hook required to suppress only masked source positions.
- [ ] Run focused tests and `compileJava`.

### Task 5: Persistent clipboard and paste anchoring

**Files:**
- Create: `src/main/java/io/github/fastformer/client/operation/OperationClipboard.java`
- Create: `src/main/java/io/github/fastformer/client/operation/OperationClipboardStore.java`
- Create: `src/main/java/io/github/fastformer/client/operation/PastePlacement.java`
- Modify: `src/main/java/io/github/fastformer/client/FastPlaceClientInput.java`
- Test: `src/test/java/io/github/fastformer/client/operation/OperationClipboardStoreTest.java`
- Test: `src/test/java/io/github/fastformer/client/operation/PastePlacementTest.java`

**Interfaces:**
- Versioned compressed NBT clipboard at `config/fastformer-operation-clipboard.nbt.gz`, written to a sibling temporary file and atomically replaced.
- `PastePlacement.inWorkspace(...)` offsets along the smallest occupied-bounds axis by its full width, ties X/Y/Z.
- `PastePlacement.atSurface(...)` aligns the opposite clipboard face center to the ray-hit point.

- [ ] Write failing round-trip, corruption-preservation, multi-part preview-copy, smallest-axis, tie and six-face anchor tests.
- [ ] Run focused tests and verify RED.
- [ ] Implement palette-based block/NBT encoding with exact limits and no silent unknown-block substitution.
- [ ] Wire Ctrl+C/V; empty-session paste enters TRANSFORM, active paste selects only the new parts.
- [ ] Run focused tests and verify GREEN.

### Task 6: Apply protocol and server validation

**Files:**
- Create: `src/main/java/io/github/fastformer/network/OperationWorkspaceApplyPayload.java`
- Create: `src/main/java/io/github/fastformer/fastplace/OperationWorkspacePlan.java`
- Create: `src/main/java/io/github/fastformer/fastplace/OperationWorkspaceValidator.java`
- Modify: `src/main/java/io/github/fastformer/network/FastPlaceNetwork.java`
- Modify: `src/main/java/io/github/fastformer/fastplace/ServerInputDispatcher.java`
- Modify: `src/main/java/io/github/fastformer/fastplace/OperationManager.java`
- Test: `src/test/java/io/github/fastformer/network/OperationWorkspaceApplyPayloadTest.java`
- Test: `src/test/java/io/github/fastformer/fastplace/OperationWorkspaceValidatorTest.java`

**Interfaces:**
- Payload carries at most ten part descriptors, source digests, transforms and palette-compressed clipboard blocks.
- Validator returns all-or-nothing `ValidatedWorkspacePlan`; source changes and invalid NBT report affected part ids.
- Execution composes lower id first, higher id last, XYZ repeat order, transparent air, then uses the existing journal task.

- [ ] Write failing codec, ten-part limit, malformed palette/NBT, source-change, overlap-order, transparent-air and no-write-on-validation-failure tests.
- [ ] Run focused tests and verify RED.
- [ ] Implement bounded codecs and register the bumped protocol.
- [ ] Re-read world sources and reconstruct targets with shared pure transform functions before any snapshot/write.
- [ ] Feed one combined source-clear/delete/target map to the existing recovery-journal operation task.
- [ ] Run focused tests and verify GREEN.

### Task 7: Integration, language, regression and deployment

**Files:**
- Modify: `src/main/resources/assets/fastformer/lang/zh_cn.json`
- Modify: `src/main/resources/assets/fastformer/lang/en_us.json`
- Modify: `docs/session_design.md`
- Modify: `docs/input_compatibility.md`
- Test: existing operation, codec, journal and input suites.

- [ ] Replace Move/Repeat and Rotate node labels with Transform/变换; add capacity, clipboard, invalid-source, delete-pending and paste-anchor messages.
- [ ] Update interaction docs with all finalized shortcuts and child/common Gizmo semantics.
- [ ] Run `./gradlew.bat --no-daemon compileJava test --console=plain` and require `BUILD SUCCESSFUL`.
- [ ] Inspect changed files for accidental edits and unresolved old MOVE_STACK/ROTATE-node assumptions.
- [ ] Run `./gradlew.bat --no-daemon deployTo233 --console=plain` and require `verifyModJar` and `deployTo233` success.
