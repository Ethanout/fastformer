package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.selection.OperationStackRegion;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.task.*;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FirstWriteGameTests {
   private FirstWriteGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "cancelled_first_write", timeoutTicks = 20000)
   public static void cancelledJournalCompletionCannotWrite(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
      int[] scenario = {0};
      FirstWriteScenario[] active = {null};
      boolean[] queueDrained = {false};
      helper.succeedWhen(() -> {
         if (active[0] == null) {
            active[0] = new FirstWriteScenario(level, origin, 16, scenario[0] == 1);
         }
         FirstWriteScenario current = active[0];
         if (!current.journalCompleted) {
            helper.assertTrue(current.task.acquireLease(current.context), "waiting for cancellation-test lease");
            current.task.tick(current.context, level, oneCellBudget());
            if (!current.io.isEmpty()) {
               current.task.releaseAfterCancelledJournal(current.context);
               current.task.releaseMemoryReservation();
               current.journalCompleted = true;
               current.io.removeFirst().run();
               current.context.withResume(() -> queueDrained[0] = true).enqueueResume();
            }
            helper.assertTrue(false, "waiting for cancelled journal completion");
         }
         helper.assertTrue(queueDrained[0], "waiting for queued callback barrier");
         helper.assertTrue(current.resumes == 0, "cancelled journal resumed its task");
         helper.assertTrue(!current.task.hasWrites(), "cancelled journal wrote blocks");
         for (int index = 0; index < 16; index++) {
            helper.assertTrue(level.getBlockState(position(origin, index).above()).isAir(),
               "cancelled task changed a target");
         }
         current.task.releaseCommittedTransactionState();
         queueDrained[0] = false;
         active[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == 2, "running next cancelled-journal scenario");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "first_write", timeoutTicks = 40000)
   public static void journalCompletionWritesBeforeAnotherTaskPoll(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
      int[] sizes = {1, 16, 256, 512};
      int[] scenario = {0};
      FirstWriteScenario[] active = {null};
      helper.succeedWhen(() -> {
         int count = sizes[scenario[0] % sizes.length];
         if (active[0] == null) {
            active[0] = new FirstWriteScenario(level, origin, count, scenario[0] >= sizes.length);
         }
         FirstWriteScenario current = active[0];
         if (!current.journalCompleted) {
            helper.assertTrue(current.task.acquireLease(current.context), "waiting for first-write lease");
            OperationTaskResult result = current.task.tick(current.context, level, oneCellBudget());
            helper.assertTrue(result == OperationTaskResult.ACTIVE, "task ended before its first journal");
            helper.assertTrue(!current.task.hasWrites(), "task wrote before journal I/O completed");
            if (!current.io.isEmpty()) {
               current.journalCompleted = true;
               current.io.removeFirst().run();
            }
            helper.assertTrue(false, "waiting for journal completion callback");
         }
         helper.assertTrue(current.resumes > 0, "waiting for main-thread journal callback");
         helper.assertTrue(current.resumes == 1, "journal resumed the task more than once");
         if (!current.recovering) {
            helper.assertTrue(current.task.hasWrites(), "journal callback did not write with an exhausted preparation budget");
            helper.assertTrue(current.task.transaction().beforeCount() == 1, "first-write allowance wrote more than one block");
            helper.assertTrue(current.task.transaction().expectedCount() == Math.min(256, count),
               "first write waited for later snapshot segments");
            helper.assertTrue(WorldHistoryManager.acceptStoppedTask(current.context, current.task).recoveryCreated(),
               "first write did not create cancellation recovery");
            current.recovering = true;
         }
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(current.context.owner()), "waiting for first-write recovery");
         for (int index = 0; index < count; index++) {
            BlockPos source = position(origin, index);
            helper.assertTrue(level.getBlockState(source.above()).isAir(), "recovery left a target block");
         }
         active[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == sizes.length * 2, "running next first-write scenario");
      });
   }

   private static WorldTaskBudget oneCellBudget() {
      return WorldTaskBudget.testing(1, 1, 0L, () -> 0L);
   }

   private static BlockPos position(BlockPos origin, int index) {
      return origin.offset(index % 16, 0, index / 16);
   }

   private static final class FirstWriteScenario {
      final WorldOperationTask task;
      final WorldTaskContext context;
      final ArrayDeque<Runnable> io = new ArrayDeque<>();
      boolean journalCompleted;
      boolean recovering;
      int resumes;

      FirstWriteScenario(ServerLevel level, BlockPos origin, int count, boolean workspace) {
         Map<BlockPos, ClientBlockSnapshot> desired = new LinkedHashMap<>();
         for (int index = 0; index < count; index++) {
            BlockPos source = position(origin, index);
            level.setBlock(source, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            level.setBlock(source.above(), Blocks.AIR.defaultBlockState(), 2);
            desired.put(source.above(), new ClientBlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState(), null));
         }
         if (workspace) {
            var part = new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.CLIPBOARD,
               desired, WorkspaceTransform.IDENTITY, false);
            task = new ClientWorkspacePlacementTask(UUID.randomUUID(), new OperationWorkspacePlan(List.of(part)),
               PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension());
         } else {
            BlockPos max = position(origin, count - 1);
            var selection = OperationSelectionVolume.cuboid(origin, max, origin, max);
            task = new SelectionOperationTask(selection, OperationMode.MOVE, OperationConflictMode.REPLACE,
               true, BlockPos.ZERO.above(), OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY,
               1024, level.dimension());
         }
         try {
            var field = task.getClass().getDeclaredField("journalPreparation");
            field.setAccessible(true);
            field.set(task, new WorldJournalPreparation(io::addLast));
         } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not control journal scheduling", exception);
         }
         WorldTaskContext owner = new WorldTaskContext(level.getServer(), UUID.randomUUID());
         context = owner.withResume(() -> {
            resumes++;
            task.tick(owner, level, oneCellBudget());
         });
      }
   }
}
