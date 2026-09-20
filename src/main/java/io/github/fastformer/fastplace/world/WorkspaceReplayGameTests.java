package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.ServerInputDispatcher;
import io.github.fastformer.fastplace.WorkspaceAdmission;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The replay path of one workspace submission against the ledger.
 *
 * <p>A client can send the same transfer twice, for example when it retries an upload that
 * the server already answered. The second admission must not start the work again, must
 * not answer a failure for work that already applied, and must not let a later report
 * replace a stronger state.</p>
 *
 * <p>These tests drive the real admission call and the real ledger, so the wiring between
 * them is covered and not only the decision type.</p>
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkspaceReplayGameTests {
   private WorkspaceReplayGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "workspace_replay", timeoutTicks = 200)
   public static void aSecondAdmissionOfRunningWorkReportsNoFailure(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      OperationWorkspacePlan plan = plan(helper, 1, 1);
      try {
         // The ledger is the authority on whether work still runs, so this test records the
         // running state directly. A real queued task would take a per-dimension write lease,
         // which a neighbouring batch may still hold, and the admission would then be
         // refused for a reason that has nothing to do with this test.
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);

         WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan);
         helper.assertTrue(
            replay.kind() == WorkspaceAdmission.Kind.REPLAYED, "a second admission was not recognised as a replay"
         );
         helper.assertTrue(
            replay.recorded() == OperationSubmissionOutcome.IN_PROGRESS,
            "the running state was not reported for the replay"
         );
         // This is the defect. The old path answered a retryable failure here, and the
         // ledger then recorded a failure for work that still runs.
         helper.assertFalse(replay.sendsResult(), "a replay of running work sent a result packet");
         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.IN_PROGRESS,
            "the replay changed the recorded running state"
         );
      } finally {
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_replay", timeoutTicks = 200)
   public static void aReportedFailureDoesNotReplaceAppliedWork(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);
         OperationSubmissionOutcome applied = WorkspaceSubmissionLedger.finish(
            server, player.getUUID(), dimension, transferId, true, false, false
         );
         helper.assertTrue(applied == OperationSubmissionOutcome.APPLIED, "the applied state was not recorded");

         // A refused replay reports a retryable failure. The ledger may not accept it,
         // because the world already holds this work.
         OperationSubmissionOutcome afterFailure = WorkspaceSubmissionLedger.finish(
            server, player.getUUID(), dimension, transferId, false, true, false
         );
         helper.assertTrue(
            afterFailure == OperationSubmissionOutcome.APPLIED,
            "a retryable failure replaced applied work"
         );
         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.APPLIED,
            "the stored state is no longer applied"
         );

         // The applied entry still answers, so a reconnect query learns the truth.
         WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper, 3, 3));
         helper.assertTrue(
            replay.kind() == WorkspaceAdmission.Kind.REPLAYED
               && replay.recorded() == OperationSubmissionOutcome.APPLIED,
            "the replay did not report the applied state"
         );
         helper.assertTrue(replay.sendsResult(), "an applied replay sent no result");
         helper.assertTrue(
            replay.deliveredOutcome() == OperationSubmissionOutcome.APPLIED,
            "the applied replay delivered another state"
         );
      } finally {
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_replay", timeoutTicks = 200)
   public static void aHandedOverTaskKeepsItsRecoveryState(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);
         OperationSubmissionOutcome recovery = WorkspaceSubmissionLedger.finish(
            server, player.getUUID(), dimension, transferId, false, true, true
         );
         helper.assertTrue(
            recovery == OperationSubmissionOutcome.RECOVERY_REQUIRED,
            "a recovery handoff was not recorded"
         );

         // A later retryable failure report must not reopen the option to send the work
         // again, because the journal owns the outcome.
         OperationSubmissionOutcome afterFailure = WorkspaceSubmissionLedger.finish(
            server, player.getUUID(), dimension, transferId, false, true, false
         );
         helper.assertTrue(
            afterFailure == OperationSubmissionOutcome.RECOVERY_REQUIRED,
            "a retryable failure replaced a recovery handoff"
         );

         WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper, 5, 5));
         helper.assertTrue(replay.sendsResult(), "a recovery replay sent no result");
         helper.assertFalse(replay.retryable(), "a recovery replay offered a retry");
      } finally {
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_replay", timeoutTicks = 200)
   public static void aClosedWriteGateDoesNotHideAnExistingResult(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         // The world already holds this work.
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);
         WorkspaceSubmissionLedger.finish(server, player.getUUID(), dimension, transferId, true, false, false);

         // The gate closes for the rest of the session, as it does after a journal failure.
         helper.assertFalse(WorldTaskFeature.tickSafely("workspace-replay-gate-test", () -> {
            throw new IllegalStateException("expected test failure to close the write gate");
         }), "the write gate did not close");
         helper.assertFalse(PersistentRecoveryJournal.writesAllowed(), "writes are still allowed");

         // The dispatcher must examine the ledger before the gates. A gate refuses new work,
         // and a replay is not new work: it reports a result that already exists.
         WorkspaceAdmission admission =
            ServerInputDispatcher.applyWorkspace(player, transferId, plan(helper, 7, 7));

         helper.assertTrue(
            admission.kind() == WorkspaceAdmission.Kind.REPLAYED,
            "the closed gate hid an existing result"
         );
         helper.assertTrue(
            admission.recorded() == OperationSubmissionOutcome.APPLIED,
            "the replay lost the applied state"
         );
         helper.assertTrue(admission.sendsResult(), "an applied replay sent no result");
         helper.assertFalse(admission.retryable(), "an applied replay offered a retry");
      } finally {
         PersistentRecoveryJournal.resetWriteGateForTest();
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   /**
    * A queued admission reports nothing of its own.
    *
    * <p>This is the only test of this class that queues a real task, because the queued path
    * is the thing under test. A queued task takes a per-dimension write lease, and a
    * neighbouring batch may still hold one, so the admission waits for the real idle
    * boundary and then happens exactly once.</p>
    *
    * <p>{@code succeedWhen} takes a runnable and retries it while it throws, so the wait
    * asserts instead of returning a value. The one-time admission keeps its own failure and
    * raises it again on every later pass, because a skipped assertion would pass on its own.
    * The wait checks only the boundary that was promised: a cancel may legitimately have
    * written a terminal state, so the ledger is not asserted here.</p>
    */
   @GameTest(template = "fastformergametests.empty", batch = "workspace_replay", timeoutTicks = 600)
   public static void aQueueingAdmissionSendsNoResultOfItsOwn(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      boolean[] admitted = {false};
      Throwable[] admissionFailure = {null};
      helper.succeedWhen(() -> {
         // The write lease of a neighbouring batch must settle before the single admission.
         helper.assertTrue(
            !admissionBlocked(player), "waiting for the write lease of a neighbouring batch"
         );
         runPhaseOnce(admitted, admissionFailure, () -> {
            WorkspaceAdmission admission = OperationManager.applyWorkspace(player, transferId, plan(helper, 9, 9));
            helper.assertTrue(admission.isQueued(), "the admission did not queue a task");
            // The queued task reports its own result. An answer here would be a second packet
            // for one transfer, and the two could contradict each other.
            helper.assertFalse(admission.sendsResult(), "a queued admission sent a result of its own");
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.IN_PROGRESS,
               "the queued transfer has no open record"
            );
            OperationManager.cancelTask(player);
         });
         // The lease of the queued task is released asynchronously.
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   /**
    * True when one admission of this player would be refused for a busy reason.
    *
    * <p>This mirrors the condition that {@code OperationManager} applies. It is the real
    * boundary, not a reset: the test waits for the state instead of clearing it.</p>
    */
   public static boolean admissionBlocked(ServerPlayer player) {
      return OperationManager.taskBusy(player)
         || FastPlaceManager.taskBusy(player)
         || WorldHistoryManager.busy(player)
         || WorldWriteCoordinator.busy(player.getServer(), player.serverLevel().dimension());
   }

   /**
    * Runs one admission phase exactly once inside a {@code succeedWhen} body.
    *
    * <p>{@code succeedWhen} retries its body while the body throws. A phase that already ran
    * must therefore not be skipped on a later pass: a skipped assertion would let the test
    * pass without its evidence. This helper keeps the failure of the phase and raises it
    * again on every later pass.</p>
    *
    * @param ran     one-element flag that records whether the phase ran
    * @param failure one-element holder for the failure of the phase
    * @param phase   the one-time work, including its assertions
    */
   public static void runPhaseOnce(boolean[] ran, Throwable[] failure, Runnable phase) {
      if (failure[0] != null) {
         throwUnchecked(failure[0]);
      }
      if (ran[0]) {
         return;
      }
      ran[0] = true;
      try {
         phase.run();
      } catch (RuntimeException | Error problem) {
         failure[0] = problem;
         throw problem;
      }
   }

   private static void throwUnchecked(Throwable problem) {
      if (problem instanceof RuntimeException runtime) {
         throw runtime;
      }
      if (problem instanceof Error error) {
         throw error;
      }
      throw new IllegalStateException("An admission phase failed with a checked exception", problem);
   }

   private static OperationWorkspacePlan plan(GameTestHelper helper, int x, int z) {
      BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      return new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.WORLD, blocks, WorkspaceTransform.IDENTITY, false
      )));
   }
}
