package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
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
 * Submission admission against the global write gate.
 *
 * <p>The gate closes for the rest of a server session when a disk journal cannot be
 * recovered safely. A submission must then be refused before any ledger record or task,
 * so the client keeps its draft and a later retry uses a new transfer.
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OperationWriteGateGameTests {
   private OperationWriteGateGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "operation_write_gate", timeoutTicks = 200)
   public static void closedWriteGateRefusesAWorkspaceSubmission(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      OperationWorkspacePlan plan = new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.WORLD, blocks, WorkspaceTransform.IDENTITY, false
      )));
      try {
         helper.assertFalse(WorldTaskFeature.tickSafely("operation-write-gate-test", () -> {
            throw new IllegalStateException("expected test failure to close the write gate");
         }), "the write gate did not close");
         helper.assertFalse(PersistentRecoveryJournal.writesAllowed(), "writes are still allowed");
         helper.assertFalse(OperationManager.applyWorkspace(player, transferId, plan).isQueued(),
            "a workspace submission was accepted while the write gate was closed");
         helper.assertFalse(OperationManager.taskActive(player), "a refused submission started a task");
         helper.assertTrue(WorkspaceSubmissionLedger.outcomeFor(
            helper.getLevel().getServer(),
            player.getUUID(),
            helper.getLevel().dimension().location(),
            transferId
         ) == OperationSubmissionOutcome.UNKNOWN, "a refused submission left a ledger record");
      } finally {
         PersistentRecoveryJournal.resetWriteGateForTest();
      }
      helper.succeed();
   }

   /**
    * A task that is queued when the gate closes is handed to recovery.
    *
    * <p>The admission waits for the real idle boundary first. A queued task takes a
    * per-dimension write lease, and a neighbouring batch may still hold one, so an immediate
    * admission would be refused for a reason that has nothing to do with the gate.</p>
    *
    * <p>{@code succeedWhen} takes a runnable and retries it while it throws, so the wait
    * asserts instead of returning a value. The guard flag admits exactly once, and a retry
    * re-checks the handed-over record, so it can never report success without the handover.</p>
    */
   @GameTest(template = "fastformergametests.empty", batch = "operation_write_gate", timeoutTicks = 600)
   public static void closedWriteGateHandsQueuedWorkToRecovery(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      BlockPos pos = helper.absolutePos(new BlockPos(3, 1, 3));
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      OperationWorkspacePlan plan = new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.WORLD, blocks, WorkspaceTransform.IDENTITY, false
      )));
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      boolean[] admitted = {false};
      boolean[] handedOver = {false};
      RuntimeException[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (phaseFailure[0] != null) {
            throw phaseFailure[0];
         }
         helper.assertTrue(
            !WorkspaceReplayGameTests.admissionBlocked(player),
            "waiting for the write lease of a neighbouring batch"
         );
         if (!admitted[0]) {
            admitted[0] = true;
            try {
               helper.assertTrue(OperationManager.applyWorkspace(player, transferId, plan).isQueued(),
                  "the open gate refused a workspace submission");
            } catch (RuntimeException exception) {
               phaseFailure[0] = exception;
               throw exception;
            }
         }
         if (!handedOver[0]) {
            try {
               helper.assertTrue(OperationManager.taskActive(player), "an accepted submission queued no task");
               PersistentRecoveryJournal.blockNewWrites();
               helper.assertFalse(PersistentRecoveryJournal.writesAllowed(), "writes are still allowed");
               WorldTaskFeature.tick(server);
               helper.assertFalse(OperationManager.taskActive(player), "a blocked task stayed in the queue");
               helper.assertTrue(WorkspaceSubmissionLedger.outcomeFor(
                  server, player.getUUID(), dimension, transferId
               ) == OperationSubmissionOutcome.FAILED_RETRYABLE,
                  "a handed-over task without writes did not report a retryable receipt");
               WorldTaskFeature.tick(server);
               helper.assertFalse(OperationManager.taskActive(player), "the second tick queued work again");
            } catch (RuntimeException exception) {
               phaseFailure[0] = exception;
               throw exception;
            } finally {
               PersistentRecoveryJournal.resetWriteGateForTest();
            }
            handedOver[0] = true;
         }
         // The handed-over task releases its lease asynchronously.
         helper.assertTrue(!WorkspaceReplayGameTests.admissionBlocked(player), "waiting for the released write lease");
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }
}
