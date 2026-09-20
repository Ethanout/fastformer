package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationStackRegion;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
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
      Object[] task = {null};
      UUID[] owner = {null};
      long baseline = MemoryReservation.reservedBytes();
      helper.succeedWhen(() -> {
         if (task[0] == null) {
            owner[0] = UUID.randomUUID();
            task[0] = create(level, target, kind[0]);
            setField(task[0], "journalPreparation", new WorldJournalPreparation(command -> {
               throw new RejectedExecutionException("Injected journal scheduling failure");
            }));
            register(owner[0], task[0]);
         }
         service(level, task[0]);
         helper.assertTrue(!active(owner[0]), "waiting for journal failure");
         helper.assertTrue(!WorldHistoryManager.busy(owner[0]), "unwritten task created recovery");
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
      Object[] task = {null};
      WorldTaskContext[] context = {null};
      boolean[] injected = {false};
      long baseline = MemoryReservation.reservedBytes();
      helper.succeedWhen(() -> {
         if (task[0] == null) {
            task[0] = create(level, target, kind[0]);
            context[0] = new WorldTaskContext(level.getServer(), UUID.randomUUID());
         }
         if (!injected[0]) {
            if (field(task[0], "operationCommit") == null) {
               advanceToCommit(helper, level, context[0], task[0]);
            }
            WorldOperationCommit commit = (WorldOperationCommit)field(task[0], "operationCommit");
            helper.assertTrue(commit != null && commit.completion().isDone(), "waiting for commit preparation");
            helper.assertTrue(level.getBlockState(target).is(Blocks.GOLD_BLOCK), "fixture did not write before publication");
            // Replace only the completed publication result. No live worker or journal is discarded.
            setField(field(commit, "history"), "future", CompletableFuture.failedFuture(
               new IllegalStateException("Injected history publication failure")));
            register(context[0].owner(), task[0]);
            service(level, task[0]);
            helper.assertTrue(!active(context[0].owner()), "failed publication retained writer");
            helper.assertTrue(WorldHistoryManager.busy(context[0].owner()), "failed publication did not create recovery");
            injected[0] = true;
         }
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(context[0].owner()), "waiting for publication failure recovery");
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
      var task = (ClientWorkspacePlacementTask)create(level, target, 1);
      var context = new WorldTaskContext(level.getServer(), owner);
      boolean[] targetChanged = {false};

      helper.succeedWhen(() -> {
         helper.assertTrue(task.acquireLease(context), "waiting for workspace write lease");
         OperationTaskResult result = task.tick(context, level, oneCellBudget());
         if (!targetChanged[0] && level.getBlockState(target).is(Blocks.GOLD_BLOCK)) {
            // Simulates a block update that removes an unsupported placement
            // after the write phase but before transaction finalization.
            level.setBlock(target, Blocks.STONE.defaultBlockState(), 2);
            targetChanged[0] = true;
         }
         if (!targetChanged[0]) {
            helper.assertTrue(result == OperationTaskResult.ACTIVE, "waiting for workspace target write");
            helper.assertTrue(false, "workspace target write is still pending");
         }
         if (result != OperationTaskResult.COMPLETE) {
            helper.assertTrue(result == OperationTaskResult.ACTIVE,
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

   private static void advanceToCommit(GameTestHelper helper, ServerLevel level, WorldTaskContext context, Object task) {
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
               placement.prepareCommit();
            }
         }
      } else {
         WorldOperationTask operation = (WorldOperationTask)task;
         helper.assertTrue(operation.acquireLease(context), "waiting for operation lease");
         OperationTaskResult result = operation.tick(context, level, oneCellBudget());
         helper.assertTrue(result == OperationTaskResult.ACTIVE || result == OperationTaskResult.COMPLETE,
            "operation failed before publication injection: " + result);
      }
   }

   private static Object create(ServerLevel level, BlockPos target, int kind) {
      level.setBlock(target, Blocks.STONE.defaultBlockState(), 2);
      BlockPos source = target.above(2);
      level.setBlock(source, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      if (kind == 0) {
         return PlacementTask.ready(Set.of(target), new PlacementTaskPlan(Blocks.GOLD_BLOCK.defaultBlockState(), null,
            OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension()));
      }
      if (kind == 1) {
         var part = new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.CLIPBOARD,
            Map.of(target, new ClientBlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState(), null)), WorkspaceTransform.IDENTITY, false);
         return new ClientWorkspacePlacementTask(UUID.randomUUID(), new OperationWorkspacePlan(List.of(part)),
            PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension());
      }
      return new SelectionOperationTask(OperationSelectionVolume.cuboid(source, source, source, source), OperationMode.MOVE,
         OperationConflictMode.REPLACE, true, target.subtract(source), OperationStackRegion.origin(),
         PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension());
   }

   private static void register(UUID owner, Object task) {
      if (task instanceof PlacementTask placement) FastPlaceManager.addTaskForTest(owner, placement);
      else OperationManager.addTaskForTest(owner, (WorldOperationTask)task);
   }

   private static boolean active(UUID owner) {
      return FastPlaceManager.taskActive(owner) || OperationManager.taskActive(owner);
   }

   private static void service(ServerLevel level, Object task) {
      if (task instanceof PlacementTask) FastPlaceManager.tickWorld(level.getServer());
      else OperationManager.tickWorld(level.getServer());
   }

   private static void assertReleased(GameTestHelper helper, ServerLevel level, long baseline) {
      helper.assertTrue(!WorldWriteCoordinator.busy(level.getServer(), level.dimension()), "failed task retained write lease");
      helper.assertTrue(MemoryReservation.reservedBytes() == baseline, "failed task retained memory reservation");
   }

   private static Object field(Object object, String name) {
      try {
         var field = object.getClass().getDeclaredField(name);
         field.setAccessible(true);
         return field.get(object);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException(exception);
      }
   }

   private static void setField(Object object, String name, Object value) {
      try {
         var field = object.getClass().getDeclaredField(name);
         field.setAccessible(true);
         field.set(object, value);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException(exception);
      }
   }

   private static WorldTaskBudget oneCellBudget() {
      try {
         Constructor<WorldTaskBudget> constructor = WorldTaskBudget.class.getDeclaredConstructor(
            int.class, int.class, long.class, LongSupplier.class);
         constructor.setAccessible(true);
         return constructor.newInstance(1, 1, 0L, (LongSupplier)() -> 0L);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException(exception);
      }
   }
}
