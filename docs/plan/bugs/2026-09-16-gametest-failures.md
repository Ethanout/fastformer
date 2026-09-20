# GameTest run failures: root cause

Date: 2026-09-16. This audit changed no production file. Read section 0 first: it
corrects the causal claims of the later sections.

Source log: `.dsh-tmp/gametest-20260916-1402.log`.
Command: `.\gradlew.bat runGameTestServer --no-configuration-cache --console=plain`.
Result: 50 tests, 18 failed, process exit 18, Gradle exit 1.

## 0. Correction: the confirmed cause

A later run with lease diagnostics settled the question. This section corrects the
causal claims of sections 1, 6, 8, 9 and 10. The earlier reasoning stays in place
as the record of the investigation.

Confirmed cause in three parts:

1. `TaskCommitFailureGameTests.workspaceFinalizationRecordsObservedBlockState`
   (batch `workspace_finalization`, which ran before `defaultBatch`) took a world
   lease, then returned from its `succeedWhen` lambda while the task still ran.
   `GameTestHelper.succeedWhen` treats a normal return as success, so the test
   passed, and the transaction and lease cleanup never ran.
2. The held lease then blocked the shared dimension. `defaultBatch` and every
   later batch met a blocked dimension.
3. The diagnostic log `.dsh-tmp/gametest-primary-lease-20260916.log` line 336
   names the owner: `c8209903-cf9a-430f-967d-12be734a0a1c`, generation 17. No
   pending unused journal was present.

The lease-leak mechanism of this document is therefore correct, and the source is
different from the guess here. The lease came from an early `return` in a test
lambda in the earlier `workspace_finalization` batch. It did not come from
`clearServer`, and it did not come from a `defaultBatch` test.

A second, hidden defect surfaced after the test repair: an empty history batch
was treated as a failure. The final block state equaled the original state, the
history batch was legitimately empty, and `WorldHistoryPublication` and
`WorldOperationCommit` rejected it. Production is repaired. See
`docs/plan/bugs/2026-09-16-empty-history-commit-verification.md`.

Repairs applied, all verified:

1. The test now asserts a wait while the task runs, so it cannot pass early.
2. Publication records its own completion instead of judging success by the
   presence of history entries. Cancellation, journal failure and asynchronous
   exceptions still fail.
3. `workspaceCompletesAtRepresentativeSizes` yields 1 ms only while it waits for
   its task. The 20000-tick cap is unchanged. The cause was the test clock:
   `GameTestServer` does not wait for normal tick intervals, so the asynchronous
   disk commit exhausted the budget. The task finished about 193 ms after the
   reported timeout.

Result: the full GameTest set passes, 50 of 50 required tests, 12.62 s of test
time. Log `.dsh-tmp/gametest-primary-paced-20260916.log`. The unit set passes:
229 test classes, 1292 tests, 0 failures, 0 errors, 1 skipped.

`WorldHistoryManager.clearServer` is an independent contract risk. It is NOT the
proven cause of this round. Section 10 keeps that analysis as an open risk only.

## 1. Result in one paragraph (original hypothesis, superseded by section 0)

The 18 failures have one common blocker: the shared test dimension carries a
world-write lease or a pending unused journal, and the lease registry belongs to
the server, not to the test. The first test that asks for the dimension reports a
refusal. The tests that follow report a wait and then time out.

This section is a STRONG HYPOTHESIS, not a proven single fault. Two parts are not
yet proven:

1. The owner of the blocking lease is unknown. The log does not print it.
2. `WorldWriteCoordinator.busy` (line 172-175) returns true for an owner lease
   AND for a pending unused journal. The current evidence does not separate them.

A separate cause for one or more of the 17 later failures is therefore still
possible. Section 9 gives the one diagnostic run that settles both questions.

## 2. Test-time semantics (verified, not assumed)

Codex is right: a failed assertion inside `succeedWhen` does not end the test.

Evidence from the real sources, extracted from
`.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.233/neoforge-21.1.233-sources.jar`:

1. `GameTestHelper.succeedWhen` (line 794) creates a sequence and calls
   `thenWaitUntil(criterion)`.
2. `GameTestSequence.thenWaitUntil` (line 18) adds one `GameTestEvent`.
3. `GameTestInfo.tickInternal` (line 152) calls
   `sequences.forEach(s -> s.tickAndContinue(this.tickCount))` on every tick
   below the timeout.
4. `GameTestSequence.tickAndContinue` (lines 73-78) catches and drops
   `GameTestAssertException`.

Consequences for this log:

1. The criterion runs again on the next tick after a failed assertion.
2. The message in the log is the message of the LAST tick, not of the first
   tick. A first-tick failure can differ from the reported one.
3. A test fails by timeout at line 146 through `tickAndFailIfNotComplete`, or it
   succeeds when the criterion passes. It does not fail on the first assertion.

## 3. The failed tests, in run order

| Test | Batch | Reported wait |
|---|---|---|
| `managerLoadsOlderHistoryPageBeforeMultiUndo` | defaultBatch | manager did not accept the retried multi-undo |
| `cancelledJournalCompletionCannotWrite` | cancelled_first_write | waiting for cancellation-test lease |
| `failedHistoryPublicationRollsBackAllTaskTypes` | history_publication_failure | waiting for placement preparation |
| `overlappingMoveRecoversAfterSourceClear` | selection_startup_recovery | waiting for recovery-test write lease |
| `cancelledWorkspaceRestoresPartialWrite` | task_lifecycle | waiting for workspace lease |
| `cancelledPlacementRestoresPartialWrite` | task_lifecycle | placement lease unavailable |
| `unchangedPlacementCompletesWithoutRecovery` | task_lifecycle | waiting for unchanged placement |
| `workspaceCompletesAtRepresentativeSizes` | task_lifecycle | waiting for workspace size 1 |
| `placementCompletesAtRepresentativeSizes` | task_lifecycle | waiting for placement size 1 |
| `clientOnlyWorkspaceKeepsFloatingGrass` | task_lifecycle | waiting for client-only workspace placement |
| `selectionCopyCommitsAcrossSegmentBoundaries` | task_lifecycle | waiting for selection write lease |
| `selectionMovePreservesOverlappingTargets` | task_lifecycle | waiting for overlapping move write lease |
| `cancelledOverlappingMoveRestoresOwnedWritesAndPreservesExternalChange` | task_failure_recovery | waiting for overlapping move lease |
| `workspaceWriteConflictRecoversOwnedWritesAndPreservesExternalChanges` | task_failure_recovery | waiting for workspace lease |
| `journalWakeupServicesOrdinaryPlacement` | placement_wakeup | waiting for placement lease |
| `containerCallbackFailureRestoresAllTaskTypes` | write_callback_failure | waiting for container callback |
| `rejectedJournalSchedulingReleasesAllTaskTypes` | journal_admission_failure | waiting for journal failure |
| `partialTasksWaitForDimensionAndResume` | dimension_availability | placement lease unavailable |

The first failure is the last test of `defaultBatch`. The other 17 failures are
all in batches that run after `defaultBatch`. Sixteen of the 17 name a lease or a
preparation wait.

## 4. Fault injection that is not a defect

These log lines are expected. They are not failures.

1. `Could not persist FastFormer history batch` and
   `Could not persist FastFormer history index`, with
   `AccessDeniedException: .history-*.tmp -> index.dat` (lines 386, 501, 609,
   996). The tests `failedIndexPublicationRetriesFromMemory` and
   `shutdownResubmitsFailedHistoryBeforeClearingIt` create a DIRECTORY at the
   `index.dat` path (`WorldHistoryPersistenceGameTests.java:446`). The atomic
   move then fails on purpose. Both tests delete the directory again
   (lines 479-480).
2. `FastFormer left recovery journal ... untouched` (lines 640, 662, 706). These
   come from `JournalRecoveryGameTests`, which writes a corrupt journal and an
   unknown-dimension journal on purpose.
3. `FastFormer restored startup journals but the world save did not complete`
   (line 698). This comes from `startupScanRetainsJournalUntilDurableSaveSucceeds`,
   which passes `() -> false` as the save probe on purpose
   (`JournalRecoveryGameTests.java:170`).
4. `Recovery correction positions are not aligned with the base journal`
   (line 706). This comes from `recoveryConsumesAllCorrections`, which builds the
   misaligned journal on purpose.

No test failed because of items 1 to 4.

## 5. Root cause of the first failure

`managerLoadsOlderHistoryPageBeforeMultiUndo` calls
`WorldHistoryManager.requestUndo(player, 2)` at line 162. The call returns false.

`WorldHistoryManager.request` (`WorldHistoryManager.java:308-369`) returns false
for these reasons, in this order:

1. `!ServerInputDispatcher.canOperate(player)` (line 312). This needs
   `player.isCreative() && FastPlaceSettings.load(player).enabled()
   && PersistentRecoveryJournal.writesAllowed()` (`ServerInputDispatcher.java:35-39`).
2. `busy(player)`, a manager task, or an active manager (lines 313-318).
3. `history == null` (line 323).
4. `history.size(undo) == 0` (line 343).
5. `WorldWriteCoordinator.busy(server, next.dimension())` (line 354).

The test proves that items 2 to 4 do not hold. Line 152 asserts
`!WorldHistoryManager.busy(owner)`. Line 157 asserts the durable order. Line 158
asserts the order `[newest, older]`. The page plan is absent after the merge, and
`history.size(undo)` is 2.

Item 1 does not hold on every tick either. The test toggles the setting at line
161, so `enabled()` alternates between ticks. On every second tick `enabled()` is
true and the permission check passes.

Item 5 is the remaining reason, and it explains why the test never recovers.
`WorldWriteCoordinator.busy` (`WorldWriteCoordinator.java:172-175`) returns true
when the dimension has an owner or a pending unused journal:

```java
return currentOwner(server, dimension) != null || pendingUnused(server, dimension) != null;
```

`requestUndo` then returns false on every tick, for the full 100000-tick budget.
The assertion at line 162 is the last one to fail, so the log shows its message.

## 6. Why 17 more tests fail

`WorldWriteCoordinator.LEASES` and `WorldWriteCoordinator.PENDING_UNUSED` are
static maps keyed by `(server, dimension)`. The clear points are:

| Call | Site | Time |
|---|---|---|
| `clearAll()` | `PlayerLifecycleEvents.java:85`, `onServerStarted` | once per server |
| `clear(server)` | `PlayerLifecycleEvents.java:102` and `:110`, stopping and stopped | once per server |

`GameTestServer` runs all 50 tests on ONE server instance and ONE level. No call
resets the coordinator between tests. A lease that one test leaves behind
therefore blocks the shared dimension for the rest of the run.

The blocking call in every later test is `task.acquireLease(context)`. It reaches
`PlacementTask.acquireLease` (`PlacementTask.java:611-621`), which calls
`WorldWriteCoordinator.takeOver(context.server(), plan.dimension(), owner)`.
`takeOver` (`WorldWriteCoordinator.java:102-122`) returns null when the dimension
is already held by another owner, and `acquireLease` then returns false. The
tests assert that false value with messages such as "placement lease unavailable"
and "waiting for workspace lease". That is exactly the shape of the 17 messages.

One test can leak the lease by ending while its task still holds it. That is the
confirmed way of section 0. Two further ways were suspected in this investigation
and are not proven for this round:

1. A test that succeeds from `succeedWhen` while its task still runs, so the
   terminal tick never releases the lease. CONFIRMED, see section 0.
2. `WorldHistoryManager.clearServer` drops a task and its lease. The two shutdown
   GameTests call it in the middle of a run. NOT the cause of this round. Kept as
   an open contract risk in section 10.
3. A pending unused journal. The diagnostic run found none. NOT the cause of this
   round.

Both ways need one instrumented run to tell apart. Section 9 gives that step.

## 7. The test also has its own defect (secondary, now fixed)

Note: this defect is real and separate from the confirmed cause of section 0. The
main agent moved the permission initialization of this test out of the retry loop.

This defect is real but it is not the root cause. It makes the first failure
report misleading and it wastes the tick budget.

In `managerLoadsOlderHistoryPageBeforeMultiUndo` the phase-1 block runs on every
tick until it passes. The block does this:

```java
FastPlaceSettings.load(player).toggleEnabled(player);       // line 161
helper.assertTrue(
   WorldHistoryManager.requestUndo(player, 2),              // line 162
   "manager did not accept the retried multi-undo"
);
phase[0] = 2;                                               // line 166
```

`phase[0] = 2` runs only after the assertion passes. `toggleEnabled`
(`FastPlaceSettings.java:112-115`) flips the flag and saves. It does not cancel
history and it does not touch a lease. The result on a retry is this:

1. Tick A: the flag is false. `requestUndo` is refused by the permission check.
2. Tick B: the flag is true. `requestUndo` runs the real test.
3. Tick C: the flag is false again.

So half of the retries test nothing. Section 3 of the review brief is confirmed:
the toggle is a test-side side effect, and the production refusal reason is the
world-write lease of section 5. Both hold at the same time. A correct repair
needs a separate fix for each.

## 8. Repair plan (superseded by section 0)

The steps below were the plan at the time. Section 0 records what was actually
done and verified. Steps 1 and 2 of this list are close to the applied repair;
steps 3, 4 and 5 are dropped or reclassified.

Historical plan:

Steps 1 and 2 are test-only and are authorized. Step 3 is a production repair
that needs more evidence first.

1. Move the permission toggle out of the retried block. Set the flag once per
   phase, and guard the retry with its own boolean, so a retry re-runs only
   `requestUndo`.
2. Record the enabled state in the test. Assert the expected value after each
   toggle, so a wrong permission state fails at its own line and not at the
   `requestUndo` line.
3. Do not add a global `WorldWriteCoordinator.clear(server)` to each test. A
   shared server can run tests in parallel, so that call can revoke the valid
   lease of another test. It also hides the production defect instead of
   repairing it.
4. Repair the production defect only after the diagnostic run of section 9 names
   the owner. `WorldHistoryManager.clearServer` may need to release the world
   lease of each task it drops, but that change touches recovery guarantees:
   check the shutdown flush, the partial-write path and the asynchronous journal
   callback before the edit. A lease that outlives its task can also be the
   intended protection for a pending journal cleanup.
5. Do not extend a timeout. The first failure is a refusal, not a slow path. A
   longer timeout only delays the report.

## 9. Evidence grade and the next run

Note the correction: section 0 replaces the open items of this section. The run
happened, the owner is named, and no pending unused journal was found.

The method at the time of writing was source reading plus log correlation. No
build, no test run, and no instrumented run.

Sections 2, 5, 6 and 7 are proven from source and from the log. At the time, the
identity of the test that blocked the dimension was NOT proven, and the owner did
not separate a held lease from a pending unused journal. Section 0 closes both
gaps.

Filtering: no per-test filter exists for this task. `net.minecraft.server.Main`
(line 195) passes `GameTestRegistry.getAllTestFunctions()` to
`GameTestServer.create`, and the only supported knob is the system property
`neoforge.enabledGameTestNamespaces` (`GameTestHooks.getEnabledNamespaces`,
line 65), which selects by namespace, not by test. A Gradle `--tests` filter does
not apply. Any run must therefore use the full set.

Tests in one batch run CONCURRENTLY. `GameTestRunner.runBatch`
(`GameTestRunner.java:90-133`) spawns every test of the batch and adds all of them
to the shared `GameTestTicker.SINGLETON` (`GameTestRunner.java:173`). No order
inside a batch is guaranteed, and one blocked test can starve the others. This is
also why a global `WorldWriteCoordinator.clear(server)` per test is unsafe: it can
revoke the live lease of a test that still runs.

Diagnostic run, temporary and removable:

1. Print one line in `WorldWriteCoordinator.acquire`, `takeOver`, `release`,
   `releaseCurrentLease`, `clear`, `clearAll`, `retainUnusedJournal` and
   `retryUnusedJournal`. Include the event, the owner, the dimension and the
   generation.
2. Print one line in `busy` when it returns true. Include the owner of the
   blocking lease and the pending-unused flag. This separates the two causes of
   section 1.
3. Add the test phase to each line: the declaring class and method of the first
   stack frame whose name ends in `GameTests`.
4. Run `.\gradlew.bat runGameTestServer --no-configuration-cache --console=plain`
   once. Save the result to a new log file.
5. Read the escape order from the new log. The first `busy=true` line names the
   blocking owner. The earlier `acquire` line with that owner names the blocking
   test. The `release` lines for that owner show whether a release is missing.

Remove the diagnostic lines after the run. Do not ship them.

## 10. clearServer and lease semantics (verified by reading)

This section answers the question "may `WorldHistoryManager.clearServer` release
the leases of the tasks it drops". The answer is no, not by a plain release.

What the drop actually does:

1. `clearServer` (`WorldHistoryManager.java:735-747`) calls
   `cancelJournalPreparation()` and `releaseMemoryReservation()` on each active
   and pending task, and then clears `OWNERS`.
2. `cancelJournalPreparation` calls `journalPreparation.cancel()`
   (`ClientWorkspacePlacementTask.java:494`, `SelectionOperationTask.java:787`,
   `WorldHistoryManager.java:1943`). It never touches the lease.
3. `WorldJournalPreparation.cancel` (`WorldJournalPreparation.java:141-151`) sets
   the cancelled flag and, only while a creation future is in flight with no
   journal object yet, schedules `discardUnused` on completion. It does not
   release a lease.
4. The lease release lives in `releaseAfterCancellation`
   (`WorldJournalPreparation.java:160-163`), which calls
   `WorldWriteCoordinator.releaseAfterUnusedJournal(lease, journal, future)`.
   `clearServer` does not call it.

So a lease held by a dropped task stays in `LEASES` with no release path:

1. The task object is unreachable after `OWNERS.clear()`.
2. No `PENDING_UNUSED` entry exists, because only `retainUnusedJournal`
   (`WorldWriteCoordinator.java:277`) creates one.
3. `retryUnusedJournal` (`WorldWriteCoordinator.java:288`) only reads
   `PENDING_UNUSED`, so it can never free this lease.
4. `releaseCurrentLease` needs the server, and `clearServer` has no server
   parameter.

Production is safe by pairing, not by `clearServer` itself. Every production call
site clears the coordinator next to it:

| Site | Line | Companion call |
|---|---|---|
| `onServerStarted` | `PlayerLifecycleEvents.java:83` | `clearAll()` at line 85 |
| `onServerStopping` | line 99 | `clear(event.getServer())` at line 105 |
| `onServerStopped` | line 110 | `clear(event.getServer())` at line 114 |

The javadoc of `clearServer` (line 734) says "when a server instance is stopping".
The two shutdown GameTests call it in the middle of a run
(`WorldHistoryPersistenceGameTests.java:352` and `:487`), so they break that
contract.

CORRECTION: this is a contract risk, not the cause of this round. Section 0 shows
that the actual blocking lease came from an early `return` in
`TaskCommitFailureGameTests.workspaceFinalizationRecordsObservedBlockState`, in
the earlier `workspace_finalization` batch. The two shutdown GameTests ran later
in the failing log (`history_commit_protocol` and `history_shutdown_sealed`) and
cannot explain a block that started before `defaultBatch`.

Why an unconditional release is the wrong repair: a dropped task can hold a
prepared journal, and that file is the durable proof of an unfinished
transaction. Release the lease, let another owner write, then replay the journal
at startup, and the newer writes are lost. The code already has the safe shape:
`releaseAfterUnusedJournal` either discards the unused journal and releases, or
registers it as pending cleanup, which keeps the dimension busy until the durable
proof is gone. The registration is generation-checked, so a late callback cannot
free a successor lease.

Two repair candidates, in order of risk:

1. Test side, low risk. Do not call a server-lifecycle method mid-run. Pair the
   call with `WorldWriteCoordinator.clear(server)`, or give the shutdown scenario
   its own isolated server. This matches the documented contract.
2. Production hardening, higher risk. Change `clearServer` to hand each dropped
   task's lease to its journal preparation, instead of dropping it. That needs a
   no-argument form of `releaseAfterCancelledJournal`, because the current
   signature takes a `WorldTaskContext` that `clearServer` cannot build. A
   diagnostic run must show the residue first, so the change has a before and an
   after.
