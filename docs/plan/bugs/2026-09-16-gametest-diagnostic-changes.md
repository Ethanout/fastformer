# Change record: GameTest lease diagnostic run

Date: 2026-09-16. Two changes only. Both are recorded here before the run.

## 1. Temporary diagnostic in WorldWriteCoordinator

File: `src/main/java/io/github/fastformer/fastplace/world/WorldWriteCoordinator.java`.

Purpose: name the owner that blocks the shared test dimension, and separate a held
lease from a pending unused journal.

The diagnostic adds one helper, `DIAG`, and one call at each state change:

| Event | Site | Meaning |
|---|---|---|
| `acquire-ok` / `acquire-fail` | `acquire` (line 80) | new exclusive transaction |
| `takeover-ok` / `takeover-fail` | `takeOver` (line 102) | successor inherits a lease |
| `release` | `release` (line 136) | release result |
| `release-held` | `release` (line 136) | release refused, pending journal owns the proof |
| `release-current` | `releaseCurrentLease` | owner-keyed release |
| `busy=true` | `busy` (line 183) | a query found the dimension blocked |
| `retain-unused` | `retainUnusedJournal` | a journal became pending |
| `retry-unused-kept` / `retry-unused-dropped` | `retryUnusedJournal` | cleanup retry result |
| `clear-drops-lease` | `clear` | clear dropped a live lease |
| `clearall-drops-lease` | `clearAll` | server start or stop dropped a live lease |

Each line carries the actor, the dimension, the current holder, the generation,
the pending-journal flag, and the test phase.

Field meanings:

1. `actor` is the owner that made the call.
2. `holder` is the owner that holds the dimension at that moment.
3. `pendingUnused` separates a held lease from a pending unused journal. This is
   the separation that section 1 of `2026-09-16-gametest-failures.md` asks for.
4. `at` is the test phase: the class and method of the first stack frame whose
   class name ends in `GameTests`. A test that takes a lease through
   `WorldHistoryManager.tickWorld` or through `task.tick` is still on the stack,
   so the phase names the owning test.

Repeat suppression: the helper logs a line only when the state key changes. The
key is the event, the actor, the dimension, the holder, the pending flag, the
detail and the phase. So a per-tick retry produces one line, not one line per
tick, followed by `(suppressed N repeats)` on the next change. Without this guard
a 100000-tick wait fills the log.

Every diagnostic site sits under the class lock, because `acquire`, `takeOver`,
`release`, `releaseCurrentLease`, `busy`, `clear`, `clearAll` and
`retainUnusedJournal` are all `synchronized`, and `retryUnusedJournal` is only
reached from one of them.

## 2. Test fix in managerLoadsOlderHistoryPageBeforeMultiUndo

File: `src/main/java/io/github/fastformer/fastplace/world/WorldHistoryPersistenceGameTests.java`.

Defect: the phase-1 block re-ran on every tick, and `phase[0] = 2` ran only after
the assertion. So `toggleEnabled` ran again on every retry, and the operation
permission alternated. Half of the retries tested the permission check instead of
the retried undo.

Change, in three parts:

1. Normalize the permission to ON before the test starts its two toggles. The mock
   player is shared, so an earlier test can leave the flag OFF. The new code
   asserts `enabled()` after the normalization.
2. Assert the flag after each toggle. The phase-0 toggle must produce OFF. The
   phase-1 toggle must produce ON. A wrong permission state now fails at its own
   line, not at the `requestUndo` line.
3. Guard the phase-1 toggle with `permissionRestored[0]`, so a retry re-runs only
   `requestUndo`.

Enabled state, proven from source:

1. `FastPlaceSettings.load` (line 45) reads the flag from the player data and
   returns a new instance. A first call stores the default.
2. `FastPlaceSettings.enabled()` (line 108) returns that instance value.
3. `toggleEnabled` (line 112) flips the field and calls `save` (line 322), which
   writes the new value into the player data.
4. So `load(...).enabled()` after a toggle reads the persisted value, and the new
   assertions test the real state.

## 3. Removal

Both changes are removable. Delete the `DIAG` block, delete the two `DIAG_LOCK`,
`diagLastKey` and `diagSuppressed` fields, delete `diagPhase`, and revert the
`busy`, `acquire`, `takeOver`, `release`, `releaseCurrentLease`, `retainUnusedJournal`,
`retryUnusedJournal`, `clear` and `clearAll` bodies to their earlier form. The
test fix of section 2 stays.

## 4. Not done

1. No production repair. `WorldHistoryManager.clearServer` is unchanged.
2. No global `WorldWriteCoordinator.clear(server)` in the tests.
3. No timeout change.

## 5. Patch state at hand-off

CORRECTION: the removal was deliberate, not a lost edit. The main agent removed
the diagnostic after the evidence was archived and after all 50 GameTests passed.
Do not restore it.

The section 1 diagnostic was applied and read back for verification (the file
reached 438 lines). It produced the holder evidence before removal: log
`.dsh-tmp/gametest-primary-lease-20260916.log` line 336 names owner
`c8209903-cf9a-430f-967d-12be734a0a1c`, generation 17, with no pending unused
journal. That line identified
`TaskCommitFailureGameTests.workspaceFinalizationRecordsObservedBlockState` as the
blocker. The original diagnostic log is kept. The lease generation and
pending-journal ownership logic stay in production. See
`docs/plan/bugs/2026-09-16-empty-history-commit-verification.md`.

An earlier note in this file said a concurrent editor removed the patch and that
it had to be applied again. That note was wrong on both counts: the removal was
intentional, and no further run is needed.

The section 2 test fix is in the tree:

1. The main agent moved the permission normalization out of `succeedWhen` to
   lines 113-117, directly after the mock player is created. That is the correct
   position: the earlier draft had it after the first `requestUndo`, where a false
   starting value could never reach it.
2. The phase-0 disabled assertion stays at lines 153-156.
3. The phase-1 `permissionRestored[0]` guard stays at lines 171-180.

Do not edit those lines again.

## 6. Sandbox note for the runner (historical)

Note: the runs are complete. The main agent ran them with full access. This note
stays only as a record of a real environment trap.

`.\gradlew.bat` needs write access to `C:\Users\26297\.gradle\wrapper\dists\gradle-9.2.1-bin\9fgc9dy84duywbml6zqd0l4mo\`.
The wrapper opens `gradle-9.2.1-bin.zip.lck` for read and write. Java reports a
denied open as `FileNotFoundException`, so the failure looks like a missing file
and not like a permission error. Two probes confirm the cause: an
`OpenWrite`-style open of that `.lck` gives "Access to the path ... is denied",
and a `Set-Content` probe in the same folder gives `UnauthorizedAccessException`.
The distribution is already extracted and its `.ok` marker exists, so no download
is needed. The run needs full file access.
