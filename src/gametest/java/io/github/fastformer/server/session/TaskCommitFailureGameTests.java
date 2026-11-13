package io.github.fastformer.server.session;

import io.github.fastformer.server.task.runtime.TaskRuntimeFixtures;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.block.WorldTaskFixtures;
import io.github.fastformer.server.task.runtime.LeasedJournaledTask;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.TaskBudgets;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskCommitFailureGameTests {
   private TaskCommitFailureGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "journal_admission_failure", timeoutTicks = 20000)
   public static void rejectedJournalSchedulingReleasesAllTaskTypes(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos target = helper.absolutePos(new BlockPos(1, 2, 1));
      int[] kind = {0};
      LeasedJournaledTask[] task = {null};
      UUID[] owner = {null};
      long baseline = MemoryReservation.reservedBytes();
      helper.succeedWhen(() -> {
         if (task[0] == null) {
            owner[0] = UUID.randomUUID();
            task[0] = create(level, target, kind[0], new WorldJournalPreparation(command -> {
               throw new RejectedExecutionException("Injected journal scheduling failure");
            }));
            register(level, owner[0], task[0]);
         }
         service(level, task[0]);
         helper.assertTrue(!active(level, owner[0]), "waiting for journal failure");
         helper.assertTrue(!HistorySystem.of(helper.getLevel().getServer()).busy(owner[0]), "unwritten task created recovery");
         helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "journal failure wrote without WAL");
         assertReleased(helper, level, baseline);
         task[0] = null;
         kind[0]++;
         helper.assertTrue(kind[0] == 3, "running next journal failure scenario");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "history_publication_failure", timeoutTicks = 20000)
   public static void failedHistoryPublicationRollsBackAllTaskTypes(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos target = helper.absolutePos(new BlockPos(1, 2, 1));
      int[] kind = {0};
      LeasedJournaledTask[] task = {null};
      WorldTaskContext[] context = {null};
      boolean[] injected = {false};
      var failPublication = new AtomicBoolean();
      long baseline = MemoryReservation.reservedBytes();
      helper.succeedWhen(() -> {
         if (task[0] == null) {
            failPublication.set(false);
            task[0] = create(level, target, kind[0], new WorldJournalPreparation(command -> {
               if (failPublication.get()) throw new RejectedExecutionException("Injected history publication rejection");
               command.run();
            }));
            context[0] = new WorldTaskContext(level.getServer(), UUID.randomUUID());
         }
         if (!injected[0]) {
            if (task[0].operationCommit() == null) {
               advanceToCommit(helper, level, context[0], task[0], () -> failPublication.set(true));
            }
            WorldOperationCommit commit = task[0].operationCommit();
            helper.assertTrue(commit != null && commit.completion().isDone(), "waiting for commit preparation");
            helper.assertTrue(level.getBlockState(target).is(Blocks.GOLD_BLOCK), "fixture did not write before publication");
            helper.assertTrue(commit.poll() == JournalPreparation.FAILED, "publication rejection did not fail the commit");
            register(level, context[0].owner(), task[0]);
            service(level, task[0]);
            helper.assertTrue(!active(level, context[0].owner()), "failed publication retained writer");
            helper.assertTrue(HistorySystem.of(helper.getLevel().getServer()).busy(context[0].owner()), "failed publication did not create recovery");
            injected[0] = true;
         }
         HistorySystem.of(level.getServer()).tickWorld(level.getServer());
         helper.assertTrue(!HistorySystem.of(helper.getLevel().getServer()).busy(context[0].owner()), "waiting for publication failure recovery");
         helper.assertTrue(level.getBlockState(target).is(Blocks.STONE), "publication failure did not restore original");
         assertReleased(helper, level, baseline);
         task[0] = null;
         injected[0] = false;
         kind[0]++;
         helper.assertTrue(kind[0] == 3, "running next publication failure scenario");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_finalization", timeoutTicks = 20000)
   public static void workspaceFinalizationRecordsObservedBlockState(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos target = helper.absolutePos(new BlockPos(1, 2, 1));
      UUID owner = UUID.randomUUID();
      var task = (SubmittedWorkspaceTask)create(level, target, 1);
      var context = new WorldTaskContext(level.getServer(), owner);
      boolean[] targetChanged = {false};

      helper.succeedWhen(() -> {
         helper.assertTrue(task.acquireLease(context), "waiting for workspace write lease");
         TaskStep result = TaskRuntimeFixtures.tickBatch(task, context, level, oneCellBudget());
         if (!targetChanged[0] && level.getBlockState(target).is(Blocks.GOLD_BLOCK)) {
            // Simulates a block update that removes an unsupported placement
            // after the write phase but before transaction finalization.
            level.setBlock(target, Blocks.STONE.defaultBlockState(), 2);
            targetChanged[0] = true;
         }
         if (!targetChanged[0]) {
            helper.assertTrue(result == TaskStep.ACTIVE, "waiting for workspace target write");
            helper.assertTrue(false, "workspace target write is still pending");
         }
         if (result != TaskStep.COMPLETE) {
            helper.assertTrue(result == TaskStep.ACTIVE,
               "workspace finalization stopped with " + result + " at " + task.phaseName());
            helper.assertTrue(false, "workspace finalization is still pending");
         }
         helper.assertTrue(task.failedTargetPositions().isEmpty(), "finalization rejected an observed world state");
         helper.assertTrue(task.transaction().afterAt(target).state().is(Blocks.STONE),
            "finalization did not record the observed target state");
         task.releaseCommittedTransactionState();
         task.releaseMemoryReservation();
         task.releaseLease(context);
      });
   }

   private static void advanceToCommit(GameTestHelper helper, ServerLevel level, WorldTaskContext context,
      LeasedJournaledTask task, Runnable beforePublication) {
      if (task instanceof PlacementTask placement) {
         helper.assertTrue(placement.prepare() && placement.ensureMemoryReservation() && placement.acquireLease(context),
            "waiting for placement preparation");
         if (!placement.snapshotsComplete()) {
            placement.validateSnapshots(level, oneCellBudget());
            return;
         }
         if (placement.prepareJournal(context) != JournalPreparation.READY) {
            return;
         }
         if (placement.blocks().hasNext()) {
            BlockPos pos = placement.blocks().next();
            placement.consumed();
            placement.place(context, level, pos);
         }
         if (placement.readyToFinalize()) {
            placement.releaseGenerationState();
            placement.resizeMemoryReservationForTransaction();
            if (placement.finalizeSnapshots(level, oneCellBudget())) {
               beforePublication.run();
               placement.prepareCommit();
            }
         }
      } else {
         WorldTask operation = (WorldTask)task;
         helper.assertTrue(operation.acquireLease(context), "waiting for operation lease");
         // This fixture has one target, so its WAL append is complete before the first write.
         if (operation.hasWrites()) beforePublication.run();
         TaskStep result = TaskRuntimeFixtures.tickBatch(operation, context, level, oneCellBudget());
         helper.assertTrue(result == TaskStep.ACTIVE || result == TaskStep.COMPLETE
               || task.operationCommit() != null,
            "operation failed before publication injection: " + result);
      }
   }

   private static LeasedJournaledTask create(ServerLevel level, BlockPos target, int kind) {
      return create(level, target, kind, new WorldJournalPreparation());
   }

   private static LeasedJournaledTask create(ServerLevel level, BlockPos target, int kind, WorldJournalPreparation journal) {
      level.setBlock(target, Blocks.STONE.defaultBlockState(), 2);
      BlockPos source = target.above(2);
      level.setBlock(source, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      if (kind == 0) {
         return WorldTaskFixtures.placement(Set.of(target), new PlacementTaskPlan(Blocks.GOLD_BLOCK.defaultBlockState(), null,
            OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension()), journal);
      }
      if (kind == 1) {
         var part = new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.CLIPBOARD,
            Map.of(target, new ClientBlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState(), null)), WorkspaceTransform.IDENTITY, false);
         return WorldTaskFixtures.workspace(UUID.randomUUID(), new OperationWorkspacePlan(List.of(part)),
            PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension(), journal);
      }
      return WorldTaskFixtures.selection(OperationSelectionVolume.cuboid(source, source, source, source), OperationMode.MOVE,
         OperationConflictMode.REPLACE, true, target.subtract(source), OperationStackRegion.origin(),
         PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension(), journal);
   }

   private static void register(ServerLevel level, UUID owner, Object task) {
      if (task instanceof PlacementTask placement) QuickShapeSystem.of(level.getServer()).tasks.put(owner, placement);
      else OperationSystem.of(level.getServer()).tasks.put(owner, (WorldTask)task);
   }

   private static boolean active(ServerLevel level, UUID owner) {
      return QuickShapeSystem.of(level.getServer()).taskActive(owner) || OperationSystem.of(level.getServer()).taskActive(owner);
   }

   private static void service(ServerLevel level, Object task) {
      if (task instanceof PlacementTask) QuickShapeSystem.of(level.getServer()).tickWorld(level.getServer(), io.github.fastformer.server.history.HistorySystem.of(level.getServer()));
      else OperationSystem.of(level.getServer()).tickWorld(level.getServer(), io.github.fastformer.server.history.HistorySystem.of(level.getServer()));
   }

   private static void assertReleased(GameTestHelper helper, ServerLevel level, long baseline) {
      helper.assertTrue(!io.github.fastformer.server.recovery.RecoverySystem.of(level.getServer()).writeLeases().busy(level.dimension()), "failed task retained write lease");
      helper.assertTrue(MemoryReservation.reservedBytes() == baseline, "failed task retained memory reservation");
   }

   private static WorldTaskBudget oneCellBudget() {
      return TaskBudgets.singleCellBudget();
   }
}
