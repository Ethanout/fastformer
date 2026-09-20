# Client operation draft: transform and restore boundary audit

Date: 2026-09-16. Read-only. No production file changed.

## 1. The question

`ClientOperationDraftCodec.decodeTransform` checks finiteness only. The server
`OperationWorkspaceValidator.validTransform` checks scale, translation, endpoint
and stride bounds. `WorkspaceTransform` validates nothing.

This audit separates two claims:

1. What a direct constructor call can build in memory (pure construction).
2. What a user or a file can reach on the production path (real reachability).

## 2. What each layer checks

| Layer | Check |
|---|---|
| `WorkspaceTransform` compact constructor (28-34) | null-coalescing only, no bound, no exception |
| `OperationStackRegion` constructor (15-24) | `min <= max` on each axis, no absolute bound |
| `OperationStackRegion` writer methods (30-70) | clamp to `ENDPOINT_LIMIT = 128` |
| `ClientOperationDraftCodec.decodeTransform` (334-342) | tag presence and type, finiteness of three vectors |
| `OperationWorkspacePlanCodec.readTransform` (149-157) | no finiteness check, no bound |
| `OperationWorkspaceValidator.validTransform` (89-116) | finiteness, scale `[1/1024, 1024]`, `|translation| <= 3.0E7`, endpoints `<= 128`, `|stride| <= 1.6E7` |

The codec also omits a presence check for `RepeatMin`, `RepeatMax` and
`RepeatStride`. `CompoundTag.getLong` returns `0L` for a missing key, so such a
draft decodes to `BlockPos.ZERO` without an error.

`BlockPos.of(long)` packs the value. X and Z take 26 bits and Y takes 12 bits. A
hostile long therefore truncates. X and Z reach about `±33,554,431`, and Y
reaches `-2,048` to `2,047`. An `int` maximum is not reachable through this
decode. A test that treats `Integer.MAX_VALUE` as network-reachable is therefore
wrong for this path.

## 3. Pure construction

`new WorkspaceTransform(anyVec3, anyVec3, anyRegion, anyStride, anyVec3)` succeeds
for every value, including `NaN` and `1.0E300`, because the compact constructor
does not examine the values.

A region of `0..128` on three axes gives `cellCount()` 129^3 = 2,146,689. The
constructor accepts it. Only the writer methods clamp to 128.

`WorkspacePreviewComposer.resolveCore` (281-329) then calls
`transform.repeats().repetitions(Integer.MAX_VALUE)` at line 304. That method
allocates an `ArrayList` with the full capacity before the loop, so this state
allocates 2,146,689 `BlockPos` objects in one list, then multiplies the scaled
source map once for each repetition.

## 4. Real reachability

### 4.1 The client render guard and the paths that bypass it

`WorkspacePreviewComposer` has two entry families:

- Guarded: `resolveForRendering` (416-420) and `geometryFrame` (47-55) test
  `canResolveForRendering` (422-428) against `CLIENT_RENDER_BLOCK_LIMIT = 100_000`.
- Unguarded: `resolveValues` (31-33) and `resolve` (411-413).

`resolve` calls `resolveValues` with no test. A grep over `src/main` gives three
production callers of the unguarded pair:

| Caller | UI path | Note |
|---|---|---|
| `WorkspaceContentPreparer.java:19` | Ctrl+C | reached through `ClientOperationController.copySelected:339-356` |
| `ClientOperationController.java:923` | every accepted submission | builds `committedTargets` before `clearWorkspace()` |
| `ClientOperationController.java:845` | ROTATE gizmo drag | only while the drag runs |

The Ctrl+C path is the shortest. It needs no server, no file and no hand edit.

### 4.2 The reachable sequence

1. Select a cuboid region and confirm it. The workspace part holds that
   selection and a cube of blocks.
2. Run one SCALE drag per axis with `totalSteps = 128`, which the input clamp at
   `FastPlaceClientInput.java:2166` permits. `ClientOperationController` builds
   `groupDelta` and clamps it again at line 827-831, so the endpoint reaches 128
   on each axis after three drags.
3. The resulting transform passes `validTransform`: every endpoint is exactly at
   the limit, and the scale stays inside its own limit.
4. Press Ctrl+C. `copySelected` calls `clipboardParts`, which calls the unguarded
   `resolve`, which expands 129^3 repetitions on the client thread.

The cost is a 2,146,689-entry repetition list plus one scaled-map copy for each
entry. The render guard would refuse the same part for preview. It does not run
on this path.

### 4.3 The server side keeps the same state

`OperationWorkspaceValidator.validate` (29-87) tests `validTransform` first, so
this state passes. `WorkspaceGeometryCost.of` then gives
`projectedUpperBound = writtenUpperBound * repeatCells`. For a small source of
8 blocks that value is about 17.2 million. The default `maxPlacement` is
20,972,152 (`FastPlaceSettings.java:34`), so the budget check at line 55 also
passes. The server then expands the same 129^3 space at line 74-76 and builds the
write map before the `writes.size() > maxBlocks` test at line 82.

The client render limit is 100,000. The server placement limit is about 209 times
larger. One state is therefore refused for preview and accepted for a world
write.

### 4.4 What the file can add

A draft file that holds a value which decode accepts but `validTransform` rejects
reaches the live workspace through `attemptDraftLoad`
(`ClientSessionManager.java:265-291`) and `restoreDraftState`
(`ClientOperationWorkspace.java:342-362`). No step validates the transform.

The user-visible result is the chain in section 5. The `Selection` tag is
optional in the codec (line 181-182). A part without it has `selection() == null`
and no production writer creates such a part, so this shape needs a hand-edited
file. It reaches the unguarded `resolveValues` at
`WorkspaceSelectionBounds.java:95`, which is the only caller that a part without
a selection can reach.

### 4.5 A saved draft can corrupt itself

`encodeVec3` (344-350) writes any double. `decodeVec3` (352-360) rejects a
non-finite value. A non-finite vector is therefore saved and reported as
`CORRUPT` on the next load. No writer path stores a non-finite value, so this
needs a hand-edited file also.

## 5. The confirmed consequence chain

A draft with an out-of-range scale or translation passes decode, lives in the
workspace, and reaches the server on submit.

1. `validTransform` rejects the part.
2. `validate` returns `Result.failed(List.of())`. The invalid part id list is
   empty (`OperationWorkspaceValidator.java:43` and `141-143`).
3. The task writes nothing, so `hasWrites()` is false and
   `retryableWorkspaceFailure` is true (`OperationManager.java:480`).
4. The client keeps the draft and shows
   `fastformer.message.operation_submit_rejected`
   (`ClientOperationController.java:937-963`).

The message names neither the part nor the cause, because the id list is empty.

## 6. Server ordering is correct

On the server the order holds. `composeDesired`
(`ClientWorkspacePlacementTask.java:230-251`) validates at line 231 before any
capture at line 240 and before any write. The phase order is VALIDATE, JOURNAL,
WRITE, FINALIZE, FINAL_JOURNAL.

The `live` parameter of `validate` is null-checked at line 30 and then unused. No
`live.read` call exists in the file. The submit path never reads the world during
validation.

## 7. Test coverage that exists

- `OperationWorkspaceValidatorTest.java:61-71` builds
  `new OperationStackRegion(BlockPos.ZERO, new BlockPos(1_000_000, 0, 0))` and
  asserts that `validTransform` returns false. This test confirms both facts in
  section 2: the constructor accepts the value, and the validator rejects it.
- `OperationStackRegionTest.java:90-101` asserts the `Integer.MAX_VALUE` clamp to
  128 and `cellCount()` 129^3.
- `ClientOperationDraftCodecTest.java` has four tests. None covers a finite but
  out-of-range scale, endpoint or stride.
- `WorkspaceTransformTest.java` has one test, for `withRepeatStride` axis
  preservation.

No test drives a writer sequence to a `validTransform` violation, and no test
calls `repetitions(Integer.MAX_VALUE)` on a 129^3 region.

## 8. Reachability summary

| State | Buildable by a constructor | Reachable from the UI | Reachable from a file |
|---|---|---|---|
| scale outside `[1/1024, 1024]` | yes | by repeated SCALE drags | yes |
| translation above 3.0E7 | yes | only by automation | yes |
| endpoints above 128 | yes | no, the writers clamp | yes |
| stride above 1.6E7 | yes | no | yes |
| non-finite vector | yes | no writer path | yes |
| 129^3 repeats that pass validation | yes | yes, three SCALE drags | yes |
| part without a selection | yes | no production writer | yes |

## 9. Evidence grade and open items

The method is source reading plus arithmetic on the writer formulas. No build, no
test run, and no in-game measurement. The 129^3 state and its cost need one
measurement to confirm.

Open questions that this audit did not settle:

1. Is the missing constructor validation deliberate, or an oversight? The class
   comment does not say.
2. Must the unguarded `resolveValues` callers carry their own guard, or must the
   guard live inside `resolveValues`?
3. `WorkspacePreviewComposer.resolvedForPart` (126-140) has no production caller.
   Treat it as dead code until a caller appears.
4. The client render limit is 100,000 and the server placement limit is about
   20.9 million. Is that difference intended?
