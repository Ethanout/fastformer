# Rotation state pipeline: coordinates turned, block states did not

Date: 2026-09-16. Bounded repair.

## 1. Symptom

A rotated operation moved its blocks but kept every block state as it was. A
north facing furnace stayed north after a quarter turn, so the preview and the
world write disagreed with the shape around them.

A later unit run also showed a lattice defect: two face-adjacent cells at
`(0,0,0)` and `(0,0,-1)` became three cells after `+Y90`.

## 2. Cause

Three defects were present.

1. The value stage was missing. `VoxelRotation.rotateStage` was generic in `T`
   and called `rotateValues`, which passed `Function.identity()` as the value
   transform (`VoxelRotation.java:58` before the repair). So the position stage
   ran and the value stage did nothing. The typed entry `VoxelRotation.rotate`
   that did turn a state had no caller in `src/main`.
2. The quarter turn had the opposite sign. `rotateVector` uses
   `x' = x cos + z sin` and `z' = -x sin + z cos` for the Y axis. A positive
   angle therefore turns north `(0, 0, -1)` to west `(-1, 0, 0)`. The old code
   chose `Rotation.CLOCKWISE_90` for that positive quarter turn.
3. `Math.cos(π/2)` is a tiny residual, not exact 0. `floor` of that residual
   dropped `(0,0,-1)` onto `(0,0,-1)` instead of `(0,0,0)`. The neighbour
   bridge then filled the diagonal gap and produced a third cell
   `(1,0,-1)`.

## 3. Proof of the sign

The sign is not a matter of taste. Two vanilla sources settle it.

1. `Rotation` maps its names to octahedral groups:
   `CLOCKWISE_90` is `OctahedralGroup.ROT_90_Y_NEG`, and `COUNTERCLOCKWISE_90`
   is `OctahedralGroup.ROT_90_Y_POS` (`Rotation.java:13-16`).
2. `Direction.getClockWise()` turns NORTH to EAST, and
   `Direction.getCounterClockWise()` turns NORTH to WEST
   (`Direction.java:189-194` and `:239-244`).

The position stage at a positive quarter turn turns NORTH to WEST. The matching
state rotation is therefore the counterclockwise one. The old pairing was one
sign wrong, which is a 180 degree error in the state against its own cell.

## 4. Repair

1. `VoxelRotation` gains a `ValueRotation<T>` stage. One axis, one angle, one
   value transform.
2. `rotateStage(Map, Vec3)` keeps the position-only meaning. `positionOnly()`
   serves it. Existing value types are unaffected.
3. `rotateStageSnapshots(Map<BlockPos, ClientBlockSnapshot>, Vec3)` is the typed
   entry. It turns each block state with its cell.
4. The quarter turn map is corrected: `+90` gives `COUNTERCLOCKWISE_90`, `-90`
   gives `CLOCKWISE_90`, and `180` gives `CLOCKWISE_180`.
5. `WorkspacePreviewComposer` gains `resolveSnapshots`. It has its own name
   because a second `resolveValues` overload with a different type argument
   would clash with the generic one on erasure. `resolve` and
   `resolveForRendering` now use it.
6. Right-angle sine and cosine are exact `0` / `±1`. `rotatedCell` and the
   envelope both call `rotatePoint`, so they share one formula. A non-right
   angle still uses `Math.sin` / `Math.cos`, so 45° bridging stays as it is.

## 5. One rule for preview and submission

Both sides now reach the same code, so the preview shows the states that the
write performs:

| Stage | Entry | State |
|---|---|---|
| Preview and clipboard | `WorkspacePreviewComposer.resolveSnapshots` | turned |
| Render-budget path | `resolveForRendering` | turned |
| Submission validation | `OperationWorkspaceValidator` line 74 | turned |
| Interaction surface | `WorkspaceInteractionResolver.resolveBasePart` | turned |
| Selection envelope | `WorkspaceSelectionBounds` line 95 | turned, keys only |

## 6. Behavior after the repair

| Rotation | Cell | Block state |
|---|---|---|
| `+90` about Y | NORTH to WEST | NORTH to WEST |
| `-90` about Y | NORTH to EAST | NORTH to EAST |
| `180` about Y | NORTH to SOUTH | NORTH to SOUTH |
| Y, not a quarter turn | moves | unchanged |
| X or Z, any angle | moves | unchanged |

Two face-adjacent cells stay two cells after a right-angle turn. The +Y90 map
of `(0,0,0)` and `(0,0,-1)` is `(1,0,0)` and `(0,0,0)`.

## 7. Limits, held on purpose

1. Vanilla `Rotation` is a Y-axis rotation only. An X or Z turn keeps the state.
   This repair does not add a new orientation rule for those axes.
2. A non-right Y angle keeps the state. Inventing a nearest orientation is out of
   scope.
3. `Rotation.CLOCKWISE_180` is used for a half turn, so a state with a mirror
   asymmetry follows the vanilla result for that rotation.
4. Production code does not add a null-state fallback. Cache tests that do not
   need a Y state rotation use an X rotation instead.

## 8. Tests

Generic JUnit in
`src/test/java/io/github/fastformer/client/operation/transform/VoxelRotationSnapshotTest.java`:

1. Two cells at `(0,0,0)` and `(0,0,-1)` stay two cells after +Y90, at
   `(1,0,0)` and `(0,0,0)`, with values intact.
2. The composer keeps generic values flat on that same two-cell map.

Pure geometry JUnit in
`src/test/java/io/github/fastformer/client/operation/transform/VoxelRotationGeometryTest.java`:

1. The same two-cell +Y90 map, hard-coded.
2. `rotatePoint` uses exact 0 / ±1 on ±90 and 180 for X, Y and Z.
3. Rotated cells match `floor` of `rotatePoint`.
4. Even and odd width lines keep count and face adjacency on every axis, at the
   occupied centre and at a translated pivot.
5. Four quadrant corners stay four cells after a right-angle turn.
6. A 45° turn still bridges a diagonal staircase.

Real furnace GameTests in
`src/main/java/io/github/fastformer/client/operation/transform/VoxelRotationSnapshotGameTests.java`.
Each case checks the cell and the facing together. Ordinary JUnit cannot load
`Blocks` without bootstrap, so these cases are not skipped Assumptions.

Cache tests in `WorkspacePreviewComposerCacheTest` rotate about X, because that
path does not read `snapshot.state()`.

## 9. State of verification

This document was written without a Gradle run. The main agent runs unit tests
and GameTests after this freeze.
