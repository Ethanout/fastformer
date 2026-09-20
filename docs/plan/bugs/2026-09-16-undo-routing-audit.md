# Undo routing audit: local workspace undo and world undo

Date: 2026-09-16
Scope: `BUG-AC`, `BUG-AI` from `docs/plan/bugs/2026-09-15-architecture-data.md`
Status: fixed, static verification only

## 1. Summary

One root cause produced both defects. The undo decision took
`ClientOperationController.active()`, which reports visible workspace content, as
the proof that the local workspace owns an undo step. Content and undo duty are
different states, thus:

- `BUG-AI`: an empty workspace rejected Ctrl+Z although its undo chain held
  valid steps.
- `BUG-AC`: an exhausted history consumed Ctrl+Z instead of passing the press to
  the world undo request, against
  `docs/principles/interaction_language/interaction_rules.md:29`.

The repair moves the ownership decision into the input layer and names the owner
of the press. The stack and the workspace stay unchanged.

## 2. Design rule

`docs/principles/interaction_language/interaction_rules.md:29`:

> Enter 提交非空工作区。Ctrl+Z 优先撤回客户端工作区，再请求世界撤回。

The rule gives Ctrl+Z two owners in sequence. The client workspace takes the
first turn. The world request takes the second turn only when the workspace has
no step to run.

## 3. The world undo path

The press reaches the world request through this chain:

1. `FastPlaceClientInput.onKey` (lines 249-265) reads Ctrl+Z or Ctrl+Y with
   `action == 1` and a physical Ctrl.
2. The handler sends `WorldUndoPayload.INSTANCE`.
3. `FastPlaceNetwork.handleWorldUndo` (line 488) receives it.
4. `ServerInputDispatcher.worldUndo` (line 566) calls
   `requestWorldUndo(player, 1, true)`.
5. `undoRoute(canOperate, historyBusy, sessionActive, fastPlaceTaskActive,
   operationTaskActive)` (lines 619-639) selects
   `BLOCKED | SESSION | FAST_PLACE_TASK | OPERATION_TASK | HISTORY`.

Two guards can stop the chain:

- `onKey` returns at line 224 when `inputRoute == BLOCKED`.
- `handleWorkspaceShortcut` returns true at line 233, which returns from `onKey`
  before line 249.

## 4. Root cause detail

The old call passed one boolean fact to the decision:

```java
WorkspaceKeyboardSemantics.decide(command, action, key, controlDown, ClientOperationController.active())
```

`active()` reports `!workspace().isEmpty()`. The undo chain is independent of
that value:

- `ClientOperationWorkspace.undoSize()` (line 250) counts stack nodes only.
- `ClientOperationEventStack.trim` keeps at least one node, thus a valid local
  step survives the removal of the last part.
- Reachable empty-with-history states: paste, then Ctrl+A, then Ctrl+Delete.
  Also `clearHistory()` after the last edit, and an undo that removes the last
  part.

The old handler also dropped the result of `undo()`:

```java
case UNDO -> ClientOperationController.undo();
```

`ClientOperationWorkspace.undo()` (line 233) returns false when `locked` is true
or when `editBaseline != null`. The handler consumed the key in both cases and
reported nothing.

## 5. The repair

### 5.1 Three press owners

`WorkspaceKeyboardSemantics.Decision` now names the owner of one press:

| Method | True for | Effect in `onKey` |
|---|---|---|
| `accepted()` | the workspace runs the command | the handler returns true |
| `handsPressToWorldUndo()` | `NO_LOCAL_HISTORY`, `OTHER_SESSION_ACTIVE` | the handler returns false, thus the world request runs |
| `consumesPress()` without an accepted command | `BLOCKED_INPUT_PHASE`, `EDIT_IN_PROGRESS`, `SUBMISSION_PENDING` | the handler returns true, thus the world request does not run |

A busy rejection never returns `handsPressToWorldUndo()`. A world undo must not
run during the operation of another owner, and the local history must stay
untouched.

### 5.2 Decision order for undo

`decideUndo` tests the facts in this order:

1. `BLOCKED` key route gives `BLOCKED_INPUT_PHASE`. This is a guard, because
   `onKey` returns at line 224 before the call.
2. `submissionPending()` gives `SUBMISSION_PENDING`.
3. `editInProgress()` gives `EDIT_IN_PROGRESS`.
4. A key route that another session holds gives `OTHER_SESSION_ACTIVE`. That
   route is `BUILDING`, `GEOMETRY`, or `CANCEL`.
5. A local undo depth above zero gives the accepted `UNDO` command.
6. An undo depth of zero gives `NO_LOCAL_HISTORY`.

A busy workspace is tested before the route and before the history. A locked or
editing workspace has no step to run and must still hold the press.

The route gives the local duty in two cases. `OPERATION` is an operation
session. `VANILLA` is the idle phase, where the input machine reports that no
session is present. The second case is the correction in section 8.

### 5.3 Extra finding

A pending submission locks the workspace. `ClientOperationWorkspace.undo()`
returns false, and `ClientOperationController.undo()` returns false, while the
old handler consumed the key and reported nothing. The new handler reports
`fastformer.message.operation_submit_pending` and holds the press. This finding
was not in the original defect record.

## 6. Files

- `src/main/java/io/github/fastformer/client/input/WorkspaceKeyboardSemantics.java`
  - `decide(Command, Dispatch keyRoute, Dispatch pasteRoute, WorkspaceFacts)`.
  - `record WorkspaceFacts(boolean workspaceActive, int localUndoDepth, boolean
    editInProgress, boolean submissionPending)`. A negative depth throws
    `IllegalArgumentException`. `WorkspaceFacts.inactive()` reports an idle empty
    workspace.
  - `enum Rejection`: `NONE`, `PHASE_BLOCKED`, `WORKSPACE_INACTIVE`,
    `NO_LOCAL_HISTORY`, `OTHER_SESSION_ACTIVE`, `BLOCKED_INPUT_PHASE`,
    `EDIT_IN_PROGRESS`, `SUBMISSION_PENDING`.
  - `Decision.handsPressToWorldUndo()` and `Decision.consumesPress()`.
- `src/main/java/io/github/fastformer/client/input/ObservedInputState.java` (new)
  - `stateFor(Sessions)` holds the observation order as a pure function.
  - `record Sessions(restoringTask, placementTask, operationSession,
    workspaceHasParts, selectionConfirmed, geometrySession, buildingSession)`.
- `src/main/java/io/github/fastformer/client/input/FastPlaceClientInput.java`
  (lines 390-441)
  - The call site builds `WorkspaceFacts` from `active()`,
    `workspaceUndoDepth()`, `workspaceEditInProgress()`, and
    `workspaceSubmissionPending()`.
  - A `handsPressToWorldUndo()` result returns false, so the world request keeps
    the press.
  - `observedInputState()` collects the live flags into
    `ObservedInputState.Sessions` and delegates the order.
  - `reportWorkspaceShortcutRejection` shows
    `fastformer.message.operation_submit_pending` for `SUBMISSION_PENDING` only.
- `src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java`
  - Added the read-only accessors `workspaceUndoDepth()` and
    `workspaceEditInProgress()`.
  - `active()`, `undo()`, `submitWorkspace()`, `applyWorkspaceResult()`, the
    source-match check, and `clearWorkspace()` stay unchanged.
- `src/test/java/io/github/fastformer/client/input/WorkspaceKeyboardSemanticsTest.java`
  - Rewritten for the new interface. The assertions were extended, not deleted.

## 7. Verification

| Check | Command | Result |
|---|---|---|
| Main sources | `& .\.dsh-tmp\check-all.ps1 main` | `javac exit=0`, 420 files |
| Test sources | `& .\.dsh-tmp\check-all.ps1 test` | `javac exit=0`, 227 files |
| Related tests | `.dsh-tmp/run-tests.ps1` with eight classes | 93 tests, 93 passed |
| Full suite, project Gradle, before the correction | `.\gradlew.bat test --no-configuration-cache --console=plain` | `BUILD SUCCESSFUL in 30s`; 226 suites, 1221 tests, 0 failures, 0 errors, 1 skipped |
| Full suite, project Gradle, after the correction | `.\gradlew.bat test --no-configuration-cache --console=plain` | `BUILD SUCCESSFUL in 25s`; 226 suites, 1228 tests, 0 failures, 0 errors, 1 skipped |
| Full suite, local harness, before the correction | `java @.dsh-tmp/args-junit-all.txt` | 1217 tests found, 1204 passed, 12 failed, 1 skipped |

The main-thread Gradle run owns the full-suite result in this record. The local
javac and JUnit harness supplied the intermediate checks only. The `main` and
`test` compile results and the two related-test runs above come from that harness.

Test classes in the related run: `WorkspaceKeyboardSemanticsTest`,
`ClientInputStateMachineTest`, `OperationInputSemanticsTest`,
`InputContextBoundaryTest`, `InteractionContextTest`,
`SubmissionKeyboardSemanticsTest`, `FastPlaceClientPreviewCoreTest`,
`ClientOperationControllerTest`.

The Gradle result covers the current tree only. It is an integration test of this
tree, and it says nothing about in-game interaction or about later code changes.

### 7.0 The 12 local-harness failures

The project Gradle run reports 0 failures, thus the 12 local-harness failures come
from the harness classpath and not from the change. The first failure gives
`NoSuchMethodError: com.google.common.collect.ImmutableMap.toImmutableMap` at
`net.minecraft.network.chat.TextColor.<clinit>`. A Guava version in
`.gradle/caches/modules-2` precedes the version that Minecraft needs. The other
11 failures then report
`NoClassDefFoundError: Could not initialize class net.minecraft.network.chat.TextColor`.
All 12 tests use `Component` text, and none of them touch undo routing.

### 7.1 Test coverage added

- An empty workspace with local history accepts `UNDO` (`BUG-AI`).
- An undo depth of zero leaves the press to the world request (`BUG-AC`).
- A building or geometry route leaves the press to the world request, even with
  local history present. A stale chain must not take the undo of a live session.
- A busy workspace holds the press in every fact combination, for every route.
- `undoRoutingAgreesWithTheRealInputDispatchValues` builds all nine states
  through `ClientInputStateMachine` and reads `dispatch(InputKind.KEY)`. It does
  not assume `Dispatch` constants by hand.
- The production path is covered end to end: session flags give the observed
  state through `ObservedInputState`, the state gives the real
  `dispatch(InputKind.KEY)` value, and that value gives the decision. The case
  `Sessions.idle()` with undo depth 1 gives `IDLE`, then `VANILLA`, then the
  accepted local `UNDO`.
- The same path with undo depth 0 gives `NO_LOCAL_HISTORY`, and with a building
  flag it gives `OTHER_SESSION_ACTIVE`.
- A negative undo depth is rejected.

## 8. Remaining risk

- The evidence is a static call-chain examination. No key press was tested in a
  running game.
- The face-drag and gizmo-drag `EDIT_IN_PROGRESS` path is not verified with a
  real gesture. `operationSession` excludes `workspaceFaceDrag`, because
  `beginWorkspaceFaceDrag` selects and activates the part first. The
  `consumesPress()` result fits the intent "a drag must not change the
  workspace", but a game test must confirm it.
- `handleWorkspaceShortcut` still runs only when `event.getAction() == 1` and
  `minecraft.screen == null`. This matches the old behavior.
- The test suite drives `ObservedInputState` with flag values, not with the live
  preview core. `FastPlaceClientPreview` needs a running client, so the test
  covers the order and the flags separately.

## 9. Follow-up

- Test the face-drag and gizmo-drag Ctrl+Z path in a running game.
- Decide whether an undo during an open edit must cancel the edit and then run
  the step, or only hold the press. The current code holds the press.
- The `VANILLA` route also occurs while the client waits for the server preview
  after the first selection point. Ctrl+Z in that window now runs a local step
  when one exists. Confirm that this is the wanted behavior in a running game.

## 10. Correction: the first repair missed the real entry path

Section 5.2 in the first version gave `NO_OPERATION_SESSION` to every key route
that was not `OPERATION`. That rule was too wide. It moved the defect from the
operation route to the idle route instead of repairing it.

### 10.1 The unfixed path

1. Ctrl+V puts clipboard parts into the client workspace.
   `ClientOperationController.paste:358-392` reads the clipboard, calls
   `workspace().addParts(parts)`, and calls `refreshSourceMask()`. The method
   sends no packet and starts no server selection. Thus `serverPreview` stays
   inactive.
2. Ctrl+A and Ctrl+Delete remove the parts.
   `ClientOperationWorkspace.removeSelectedParts:86-97` records the state before
   the removal, so the undo chain continues with an empty part map.
3. Ctrl+Z reads the key route. `operationActive()` is false, because
   `PREVIEW_STATE.operation().active()`, `ClientOperationController.active()` and
   `selectionSessionActive()` are all false. `observedInputState` then gives
   `IDLE`, whose key route is `VANILLA`.
4. The first version returned `NO_OPERATION_SESSION` for `VANILLA`, so
   `handleWorkspaceShortcut` returned false, and the world undo request in
   `onKey:249-265` sent `WorldUndoPayload`.

The workspace held a real step, and the world received the press.

### 10.2 The corrected rule

`ownsLocalUndoDuty(keyRoute)` gives the local duty for `OPERATION` and for
`VANILLA`. The other routes keep the press away from a stale chain:

| Route | Owner | Reason |
|---|---|---|
| `BLOCKED` | the input machine | `BLOCKED_INPUT_PHASE` holds the press |
| `OPERATION` | the local workspace | an operation session is present |
| `VANILLA` | the local workspace | the idle phase, where no session is present |
| `BUILDING` | the building session | its own undo outranks a stale chain |
| `GEOMETRY` | the geometry session | its own undo outranks a stale chain |
| `CANCEL` | the world request | a cancellation is in effect |

The busy checks moved before the route test, so `SUBMISSION_PENDING` and
`EDIT_IN_PROGRESS` hold the press on every route.

### 10.3 Evidence grade

The route comes from the real `ClientInputStateMachine.State` dispatch table. The
test builds every state through the machine and reads `dispatch(InputKind.KEY)`.
The session flags come from `ObservedInputState` values, not from a live client.

The main thread ran the full Gradle suite after the correction: `BUILD SUCCESSFUL
in 25s`, 226 suites, 1228 tests, 0 failures, 0 errors, 1 skipped. The count grew
by 7 tests against the run before the correction (1221), which matches the new
production-path cases.

No key press was tested in a running game. The in-game result for the paste,
select, remove, and undo sequence stays open.
