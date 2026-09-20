package io.github.fastformer.network;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.WorkspaceAdmission;
import io.github.fastformer.fastplace.world.WorkspaceReplayGameTests;
import io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger;
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
 * The request scope map of one workspace submission.
 *
 * <p>The map is keyed by owner and transfer, so the request that queued a task and a later
 * replay of the same transfer share one entry. A replay of work that still runs must leave
 * that entry alone: the running task removes it when it reports, and its result packet has
 * to carry the scope of the request that queued it.</p>
 *
 * <p>These tests drive the real map through the real delivery and recording methods, so the
 * bookkeeping is covered and not only a predicate. Every test cleans up only its own player
 * and its own records, so a neighbouring batch keeps its state.</p>
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkspaceCallbackScopeGameTests {
   private WorkspaceCallbackScopeGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aReplayOfRunningWorkKeepsTheLiveTaskScope(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         // The request that queued the task records its scope before admission.
         FastPlaceNetwork.rememberWorkspaceCallbackScope(player, transferId, 0);
         helper.assertTrue(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "the request scope was not recorded"
         );
         int before = FastPlaceNetwork.callbackScopeCountForTest();

         // The ledger is the authority on the running state. A real queued task would take a
         // per-dimension write lease that a neighbouring batch may still hold, and the
         // admission would then be refused for an unrelated reason.
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);

         WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper, 1, 1));
         helper.assertTrue(
            replay.kind() == WorkspaceAdmission.Kind.REPLAYED,
            "the second admission was not recognised as a replay"
         );
         helper.assertTrue(
            replay.keepsRequestScope(), "the replay did not keep the scope of the running work"
         );
         FastPlaceNetwork.sendAdmissionResult(player, transferId, replay);

         // The running task still owns its scope. Removing it here would make the result
         // packet fall back to the scope of the replay.
         helper.assertTrue(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "the replay removed the scope of the live task"
         );
         helper.assertTrue(
            FastPlaceNetwork.callbackScopeCountForTest() == before,
            "the replay changed the number of remembered request scopes"
         );
      } finally {
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
         FastPlaceNetwork.forgetActivity(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aReplayOfRunningWorkDoesNotReplaceTheLiveTaskScope(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);

         // A replay arrives on a later connection. Its chunk zero must not replace the scope
         // of the request that queued the task.
         FastPlaceNetwork.rememberWorkspaceCallbackScope(player, transferId, 0);

         helper.assertFalse(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "a replay replaced the scope of a live task"
         );
      } finally {
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
         FastPlaceNetwork.forgetActivity(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aTerminalReplayClosesTheRequestScope(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      try {
         // The replay records the scope of the request that carries it, then the answer
         // closes that request.
         FastPlaceNetwork.rememberWorkspaceCallbackScope(player, transferId, 0);
         helper.assertTrue(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "the request scope was not recorded"
         );

         FastPlaceNetwork.sendAdmissionResult(
            player, transferId, WorkspaceAdmission.replayed(OperationSubmissionOutcome.APPLIED)
         );

         helper.assertFalse(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "a delivered result left its request scope behind"
         );
      } finally {
         FastPlaceNetwork.forgetActivity(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aRefusedAdmissionClosesTheRequestScope(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      try {
         FastPlaceNetwork.rememberWorkspaceCallbackScope(player, transferId, 0);

         FastPlaceNetwork.sendAdmissionResult(player, transferId, WorkspaceAdmission.rejected());

         helper.assertFalse(
            FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), transferId),
            "a refusal left its request scope behind"
         );
      } finally {
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
         FastPlaceNetwork.forgetActivity(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aFailureReportDoesNotOverwriteARunningTransfer(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         // A queued task owns this transfer. A decode failure of a duplicate upload reaches
         // the same handler, and it must not record a failure for running work.
         WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);

         FastPlaceNetwork.reportFailedAdmission(player, transferId);

         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.IN_PROGRESS,
            "a failure report overwrote a running transfer"
         );
      } finally {
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 200)
   public static void aFailureReportStillRecordsAnUnknownTransfer(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      try {
         // The upload never reached admission, so the failure is real and the client must
         // learn it instead of waiting.
         FastPlaceNetwork.reportFailedAdmission(player, transferId);

         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.FAILED_RETRYABLE,
            "a real admission failure was not recorded"
         );
      } finally {
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      }
      helper.succeed();
   }

   /**
    * A failure report leaves a live task alone.
    *
    * <p>The task is real, so the admission waits for the write lease of a neighbouring batch
    * and then happens exactly once. {@code succeedWhen} takes a runnable and retries it while
    * it throws, so the wait asserts instead of returning a value, and the one-time phase
    * keeps its own failure to raise it again on every later pass.</p>
    */
   @GameTest(template = "fastformergametests.empty", batch = "workspace_scope", timeoutTicks = 600)
   public static void aFailureReportLeavesALiveTaskAlone(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      boolean[] started = {false};
      RuntimeException[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (phaseFailure[0] != null) {
            throw phaseFailure[0];
         }
         if (!started[0]) {
            helper.assertTrue(
               !WorkspaceReplayGameTests.admissionBlocked(player),
               "waiting for the write lease of a neighbouring batch"
            );
            started[0] = true;
            try {
               WorkspaceAdmission admission = OperationManager.applyWorkspace(player, transferId, plan(helper, 5, 5));
               helper.assertTrue(admission.isQueued(), "the admission did not queue a task");

               // A fault between the queue and the record leaves a live task with no ledger
               // entry. Reproduce that state on purpose and report a failure into it.
               WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
               helper.assertTrue(
                  WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                     == OperationSubmissionOutcome.UNKNOWN,
                  "the record was not removed"
               );
               helper.assertTrue(
                  OperationManager.transferActive(player.getUUID(), transferId), "the task is gone"
               );

               FastPlaceNetwork.reportFailedAdmission(player, transferId);

               // The live task is the authority. The failure report must not record anything.
               helper.assertTrue(
                  WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                     == OperationSubmissionOutcome.UNKNOWN,
                  "a failure report recorded a failure for a live task"
               );
               OperationManager.cancelTask(player);
            } catch (RuntimeException exception) {
               phaseFailure[0] = exception;
               throw exception;
            }
         }
         // The lease of the queued task is released asynchronously.
         helper.assertTrue(
            !WorkspaceReplayGameTests.admissionBlocked(player), "waiting for the released write lease"
         );
         // Only this test's own record and scope leave. Other owners keep theirs.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
         FastPlaceNetwork.forgetActivity(player);
      });
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
