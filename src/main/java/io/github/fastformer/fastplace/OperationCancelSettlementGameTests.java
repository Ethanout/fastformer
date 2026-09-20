package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Cancel of a queued workspace task must settle the ledger.
 *
 * <p>Recovery never finishes that record. An {@code IN_PROGRESS} entry never ages out,
 * so a cancel that only removes the task leaves the client waiting. These cases drive
 * the production cancel entry. A later cancel or closed-gate handover cannot replace a
 * stronger recorded state, which is the proof that the shared settler ran once.</p>
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OperationCancelSettlementGameTests {

   private OperationCancelSettlementGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_cancel_before_write", timeoutTicks = 600)
   public static void cancelBeforeWriteSettlesARetryableFailure(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      MinecraftServer server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      boolean[] cancelled = {false};
      Throwable[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (!cancelled[0]) {
            helper.assertTrue(!admissionBlocked(player), "waiting for the write lease of a neighbouring batch");
         }
         runPhaseOnce(cancelled, phaseFailure, () -> {
            helper.assertTrue(
               OperationManager.applyWorkspace(player, transferId, plan(helper)).isQueued(),
               "the open gate refused a workspace submission"
            );
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.IN_PROGRESS,
               "the queued transfer has no open record"
            );
            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.CANCELLED_BEFORE_WRITE,
               "a cancel before write did not report the empty snapshot"
            );
            helper.assertFalse(
               OperationManager.taskActive(player),
               "a cancel before write left the task in the queue"
            );
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a cancel before write left the ledger open"
            );
            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
               "a second cancel found a task"
            );
            OperationManager.handOverBlockedTasks(server);
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a later handover replaced the cancel result"
            );
            WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper));
            helper.assertTrue(
               replay.kind() == WorkspaceAdmission.Kind.REPLAYED
                  && replay.recorded() == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a replay after cancel before write did not report the retryable failure"
            );
            helper.assertTrue(replay.retryable(), "a cancel before write did not allow a later retry");
            helper.assertFalse(
               OperationManager.transferActive(player.getUUID(), transferId),
               "a replay after cancel queued the same transfer again"
            );
         });
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_cancel_after_write", timeoutTicks = 20000)
   public static void cancelAfterWritesSettlesRecoveryRequired(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      ServerLevel level = helper.getLevel();
      MinecraftServer server = level.getServer();
      var dimension = level.dimension().location();
      BlockPos pos = helper.absolutePos(BlockPos.ZERO);
      boolean[] cancelled = {false};
      Throwable[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (!cancelled[0]) {
            helper.assertTrue(!admissionBlocked(player), "waiting for the write lease of a neighbouring batch");
         }
         runPhaseOnce(cancelled, phaseFailure, () -> {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
            ClientWorkspacePlacementTask task = workspaceTask(level, transferId, pos, Blocks.GOLD_BLOCK);
            ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
            helper.assertTrue(
               new ReversibleBlockSnapshot(
                  pos, Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState().getFluidState(), null
               ).placeAt(level, pos, PlacementUpdateMode.CLIENT_ONLY.flags()),
               "the fixture could not write the gold block"
            );
            ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
            task.transaction().recordBefore(before);
            task.transaction().recordAfter(pos, after);
            OperationManager.addTaskForTest(player.getUUID(), task);
            WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);
            helper.assertTrue(task.hasWrites(), "the fixture did not record a write");
            helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "the fixture left the original block");

            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.ROLLBACK_STARTED,
               "a written cancel did not start recovery"
            );
            helper.assertFalse(
               OperationManager.taskActive(player),
               "a written cancel left the task in the queue"
            );
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a written cancel left the ledger open"
            );
            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
               "a second cancel found a task"
            );
            OperationManager.handOverBlockedTasks(server);
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a later handover replaced the recovery result"
            );
            WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper));
            helper.assertTrue(
               replay.kind() == WorkspaceAdmission.Kind.REPLAYED
                  && replay.recorded() == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a replay after recovery cancel did not report recovery"
            );
            helper.assertFalse(replay.retryable(), "a recovery cancel offered a retry");
         });
         WorldHistoryManager.tickWorld(server);
         helper.assertTrue(!WorldHistoryManager.busy(player.getUUID()), "waiting for recovery to finish");
         helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "recovery did not restore the original block");
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_cancel_preparation_fault", timeoutTicks = 20000)
   public static void aCancelPreparationFaultKeepsTheOpenLedger(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      ServerLevel level = helper.getLevel();
      MinecraftServer server = level.getServer();
      var dimension = level.dimension().location();
      BlockPos pos = helper.absolutePos(BlockPos.ZERO);
      boolean[] cancelled = {false};
      Throwable[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (!cancelled[0]) {
            helper.assertTrue(!admissionBlocked(player), "waiting for the write lease of a neighbouring batch");
         }
         runPhaseOnce(cancelled, phaseFailure, () -> {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
            ClientWorkspacePlacementTask task = workspaceTask(level, transferId, pos, Blocks.GOLD_BLOCK);
            ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
            helper.assertTrue(
               new ReversibleBlockSnapshot(
                  pos, Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState().getFluidState(), null
               ).placeAt(level, pos, PlacementUpdateMode.CLIENT_ONLY.flags()),
               "the fixture could not write the gold block"
            );
            ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
            task.transaction().recordBefore(before);
            task.transaction().recordAfter(pos, after);
            OperationManager.addTaskForTest(player.getUUID(), task);
            WorkspaceSubmissionLedger.begin(server, player.getUUID(), dimension, transferId);
            WorldJournalPreparation original = journalPreparation(task);
            try {
               setJournalPreparation(task, null);
               helper.assertTrue(
                  OperationManager.cancelTask(player) == TaskCancellationResult.RECOVERY_BLOCKED,
                  "a cancel-preparation fault did not report the block"
               );
               helper.assertTrue(
                  OperationManager.transferActive(player.getUUID(), transferId),
                  "a cancel-preparation fault dropped the task"
               );
               helper.assertTrue(
                  WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                     == OperationSubmissionOutcome.IN_PROGRESS,
                  "a cancel-preparation fault settled the ledger"
               );
               helper.assertTrue(task.hasWrites(), "a cancel-preparation fault extracted the snapshot");
            } finally {
               setJournalPreparation(task, original);
            }
            WorkspaceSubmissionLedger.tick(server);
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.IN_PROGRESS,
               "an open cancel record aged out"
            );
            WorkspaceAdmission replay = OperationManager.applyWorkspace(player, transferId, plan(helper));
            helper.assertTrue(
               replay.kind() == WorkspaceAdmission.Kind.REPLAYED
                  && replay.recorded() == OperationSubmissionOutcome.IN_PROGRESS,
               "a replay of a blocked cancel did not keep the open record"
            );
            helper.assertFalse(replay.sendsResult(), "a replay of blocked work sent a result");

            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.ROLLBACK_STARTED,
               "the restored cancel did not hand the snapshot to recovery"
            );
            helper.assertFalse(
               OperationManager.taskActive(player),
               "the restored cancel left the task in the queue"
            );
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "the restored cancel left the ledger open"
            );
            helper.assertTrue(
               OperationManager.cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
               "a later cancel found a task after the restored cancel"
            );
            OperationManager.handOverBlockedTasks(server);
            helper.assertTrue(
               WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a later handover replaced the restored cancel result"
            );
         });
         WorldHistoryManager.tickWorld(server);
         helper.assertTrue(!WorldHistoryManager.busy(player.getUUID()), "waiting for recovery to finish");
         helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "recovery did not restore the original block");
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_cancel_completed", timeoutTicks = 20000)
   public static void aCompletedTaskKeepsItsAppliedResultOnCancel(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      ServerLevel level = helper.getLevel();
      MinecraftServer server = level.getServer();
      var dimension = level.dimension().location();
      BlockPos pos = helper.absolutePos(BlockPos.ZERO);
      boolean[] admitted = {false};
      Throwable[] phaseFailure = {null};
      helper.succeedWhen(() -> {
         if (!admitted[0]) {
            helper.assertTrue(!admissionBlocked(player), "waiting for the write lease of a neighbouring batch");
         }
         runPhaseOnce(admitted, phaseFailure, () -> {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            helper.assertTrue(level.getBlockState(pos).isAir(), "the completion target was not empty");
            helper.assertTrue(
               OperationManager.applyWorkspace(player, transferId, clipboardPlan(pos, Blocks.GOLD_BLOCK)).isQueued(),
               "the open gate refused a workspace submission"
            );
         });
         if (OperationManager.transferActive(player.getUUID(), transferId)) {
            OperationManager.tickWorld(server);
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            helper.assertTrue(false, "waiting for the queued workspace to finish");
         }
         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.APPLIED,
            "a finished workspace did not record applied work"
         );
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "a finished workspace did not write the target");
         helper.assertTrue(
            OperationManager.cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
            "cancel after completion found a task"
         );
         OperationManager.handOverBlockedTasks(server);
         helper.assertTrue(
            WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.APPLIED,
            "cancel after completion replaced the applied result"
         );
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "cancel after completion changed the applied block");
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   private static boolean admissionBlocked(ServerPlayer player) {
      return OperationManager.taskBusy(player)
         || FastPlaceManager.taskBusy(player)
         || WorldHistoryManager.busy(player)
         || WorldWriteCoordinator.busy(player.getServer(), player.serverLevel().dimension());
   }

   private static void runPhaseOnce(boolean[] ran, Throwable[] failure, Runnable phase) {
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
      throw new IllegalStateException("A cancel settlement phase failed with a checked exception", problem);
   }

   private static ClientWorkspacePlacementTask workspaceTask(
      ServerLevel level, UUID transferId, BlockPos pos, net.minecraft.world.level.block.Block block
   ) {
      return new ClientWorkspacePlacementTask(
         transferId,
         clipboardPlan(pos, block),
         PlacementUpdateMode.CLIENT_ONLY,
         16,
         level.dimension()
      );
   }

   /**
    * A null preparation makes {@code cancelJournalPreparation} throw before the snapshot
    * leaves the task. The existing unit test covers a throw after extraction. This case
    * only covers the preparation fault.
    */
   private static WorldJournalPreparation journalPreparation(ClientWorkspacePlacementTask task) {
      try {
         var field = ClientWorkspacePlacementTask.class.getDeclaredField("journalPreparation");
         field.setAccessible(true);
         return (WorldJournalPreparation)field.get(task);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Could not read the journal preparation", exception);
      }
   }

   private static void setJournalPreparation(
      ClientWorkspacePlacementTask task, WorldJournalPreparation preparation
   ) {
      try {
         var field = ClientWorkspacePlacementTask.class.getDeclaredField("journalPreparation");
         field.setAccessible(true);
         field.set(task, preparation);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Could not replace the journal preparation", exception);
      }
   }

   private static OperationWorkspacePlan plan(GameTestHelper helper) {
      return clipboardPlan(helper.absolutePos(BlockPos.ZERO), Blocks.STONE);
   }

   private static OperationWorkspacePlan clipboardPlan(BlockPos pos, net.minecraft.world.level.block.Block block) {
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new ClientBlockSnapshot(block.defaultBlockState(), null));
      return new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.CLIPBOARD, blocks, WorkspaceTransform.IDENTITY, false
      )));
   }
}
