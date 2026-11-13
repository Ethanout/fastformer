package io.github.fastformer.server.session;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.workspace.submission.WorkspaceAdmission;
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
               OperationSystem.of(player).applyWorkspace(player, transferId, plan(helper)).isQueued(),
               "the open gate refused a workspace submission"
            );
            helper.assertTrue(
               SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.IN_PROGRESS,
               "the queued transfer has no open record"
            );
            helper.assertTrue(
               OperationSystem.of(player).cancelTask(player) == TaskCancellationResult.CANCELLED_BEFORE_WRITE,
               "a cancel before write did not report the empty snapshot"
            );
            helper.assertFalse(
               OperationSystem.of(player).taskActive(player),
               "a cancel before write left the task in the queue"
            );
            helper.assertTrue(
               SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a cancel before write left the ledger open"
            );
            helper.assertTrue(
               OperationSystem.of(player).cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
               "a second cancel found a task"
            );
            OperationSystem.of(server).handOverBlockedTasks(server, io.github.fastformer.server.history.HistorySystem.of(server));
            helper.assertTrue(
               SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a later handover replaced the cancel result"
            );
            WorkspaceAdmission replay = OperationSystem.of(player).applyWorkspace(player, transferId, plan(helper));
            helper.assertTrue(
               replay.kind() == WorkspaceAdmission.Kind.REPLAYED
                  && replay.recorded() == OperationSubmissionOutcome.FAILED_RETRYABLE,
               "a replay after cancel before write did not report the retryable failure"
            );
            helper.assertTrue(replay.retryable(), "a cancel before write did not allow a later retry");
            helper.assertFalse(
               OperationSystem.of(player).transferActive(player.getUUID(), transferId),
               "a replay after cancel queued the same transfer again"
            );
         });
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         SessionSystem.of(server).submissions().clearOwner(player.getUUID());
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
            SubmittedWorkspaceTask task = workspaceTask(level, transferId, pos, Blocks.GOLD_BLOCK);
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
            OperationSystem.of(player).tasks.put(player.getUUID(), task);
            SessionSystem.of(server).submissions().begin(player.getUUID(), dimension, transferId);
            helper.assertTrue(task.hasWrites(), "the fixture did not record a write");
            helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "the fixture left the original block");

            helper.assertTrue(
               OperationSystem.of(player).cancelTask(player) == TaskCancellationResult.ROLLBACK_STARTED,
               "a written cancel did not start recovery"
            );
            helper.assertFalse(
               OperationSystem.of(player).taskActive(player),
               "a written cancel left the task in the queue"
            );
            helper.assertTrue(
               SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a written cancel left the ledger open"
            );
            helper.assertTrue(
               OperationSystem.of(player).cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
               "a second cancel found a task"
            );
            OperationSystem.of(server).handOverBlockedTasks(server, io.github.fastformer.server.history.HistorySystem.of(server));
            helper.assertTrue(
               SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                  == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a later handover replaced the recovery result"
            );
            WorkspaceAdmission replay = OperationSystem.of(player).applyWorkspace(player, transferId, plan(helper));
            helper.assertTrue(
               replay.kind() == WorkspaceAdmission.Kind.REPLAYED
                  && replay.recorded() == OperationSubmissionOutcome.RECOVERY_REQUIRED,
               "a replay after recovery cancel did not report recovery"
            );
            helper.assertFalse(replay.retryable(), "a recovery cancel offered a retry");
         });
         HistorySystem.of(server).tickWorld(server);
         helper.assertTrue(!HistorySystem.of(player).busy(player.getUUID()), "waiting for recovery to finish");
         helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "recovery did not restore the original block");
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         SessionSystem.of(server).submissions().clearOwner(player.getUUID());
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
               OperationSystem.of(player).applyWorkspace(player, transferId, clipboardPlan(pos, Blocks.GOLD_BLOCK)).isQueued(),
               "the open gate refused a workspace submission"
            );
         });
         if (OperationSystem.of(player).transferActive(player.getUUID(), transferId)) {
            OperationSystem.of(server).tickWorld(server, io.github.fastformer.server.history.HistorySystem.of(server));
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            helper.assertTrue(false, "waiting for the queued workspace to finish");
         }
         helper.assertTrue(
            SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.APPLIED,
            "a finished workspace did not record applied work"
         );
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "a finished workspace did not write the target");
         helper.assertTrue(
            OperationSystem.of(player).cancelTask(player) == TaskCancellationResult.NOT_ACTIVE,
            "cancel after completion found a task"
         );
         OperationSystem.of(server).handOverBlockedTasks(server, io.github.fastformer.server.history.HistorySystem.of(server));
         helper.assertTrue(
            SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
               == OperationSubmissionOutcome.APPLIED,
            "cancel after completion replaced the applied result"
         );
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "cancel after completion changed the applied block");
         helper.assertTrue(!admissionBlocked(player), "waiting for the released write lease");
         SessionSystem.of(server).submissions().clearOwner(player.getUUID());
      });
   }

   private static boolean admissionBlocked(ServerPlayer player) {
      return OperationSystem.of(player).taskBusy(player)
         || QuickShapeSystem.of(player).taskBusy(player)
         || HistorySystem.of(player).busy(player)
         || io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension());
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

   private static SubmittedWorkspaceTask workspaceTask(
      ServerLevel level, UUID transferId, BlockPos pos, net.minecraft.world.level.block.Block block
   ) {
      return new SubmittedWorkspaceTask(
         transferId,
         clipboardPlan(pos, block),
         PlacementUpdateMode.CLIENT_ONLY,
         16,
         level.dimension()
      );
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
