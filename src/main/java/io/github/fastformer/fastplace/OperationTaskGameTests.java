package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OperationTaskGameTests {
   private OperationTaskGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void selectionCopyCommitsAcrossSegmentBoundaries(GameTestHelper helper) {
      var level = helper.getLevel();
      int[] sizes = {1, 16, 256, 512};
      int[] scenario = {0};
      SelectionOperationTask[] active = {null};
      WorldTaskContext[] context = {null};
      BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
      helper.succeedWhen(() -> {
         int count = sizes[scenario[0]];
         int width = count == 1 ? 1 : 16;
         int depth = count / width;
         if (active[0] == null) {
            for (int index = 0; index < count; index++) {
               BlockPos pos = origin.offset(index % width, 0, index / width);
               level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
               level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
            }
            AABB bounds = new AABB(origin.getX(), origin.getY(), origin.getZ(),
               origin.getX() + width, origin.getY() + 1, origin.getZ() + depth);
            var selection = new OperationSelectionVolume(OperationSelectionMode.CUBOID, bounds,
               null, List.of(), 0, origin, origin.offset(width - 1, 0, depth - 1));
            active[0] = new SelectionOperationTask(selection, OperationMode.MOVE,
               OperationConflictMode.REPLACE, true, new BlockPos(0, 1, 0), OperationStackRegion.origin(),
               PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension());
            context[0] = new WorldTaskContext(level.getServer(), UUID.randomUUID());
         }
         SelectionOperationTask task = active[0];
         helper.assertTrue(task.acquireLease(context[0]), "waiting for selection write lease");
         OperationTaskResult result = task.tick(context[0], level, WorldTaskBudget.forServerTick());
         helper.assertTrue(result == OperationTaskResult.ACTIVE || result == OperationTaskResult.COMPLETE,
            "selection failed at " + task.phaseName() + " with " + result + " for " + count + " blocks");
         helper.assertTrue(result == OperationTaskResult.COMPLETE,
            "waiting for selection commit: " + count + " cells, " + task.phaseName() + ", " + task.metricsSummary());
         for (int index = 0; index < count; index++) {
            BlockPos pos = origin.offset(index % width, 0, index / width);
            helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "copy changed its source");
            helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.GOLD_BLOCK), "copy omitted a target");
         }
         helper.assertTrue(WorldHistoryManager.commitPreparedOperation(context[0], task.preparedBatch(), task.journal()),
            "completed selection could not publish history");
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
         task.releaseLease(context[0]);
         active[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == sizes.length, "running next selection size");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void selectionMovePreservesOverlappingTargets(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 17, 1));
      int count = 16;
      for (int index = 0; index < count; index++) {
         level.setBlock(origin.offset(index, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      }
      level.setBlock(origin.offset(count, 0, 0), Blocks.AIR.defaultBlockState(), 2);
      AABB bounds = new AABB(
         origin.getX(), origin.getY(), origin.getZ(),
         origin.getX() + count, origin.getY() + 1, origin.getZ() + 1
      );
      var selection = new OperationSelectionVolume(
         OperationSelectionMode.CUBOID, bounds, null, List.of(), 0,
         origin, origin.offset(count - 1, 0, 0)
      );
      var task = new SelectionOperationTask(
         selection, OperationMode.MOVE, OperationConflictMode.REPLACE, false,
         new BlockPos(1, 0, 0), OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY,
         1024, level.dimension()
      );
      var context = new WorldTaskContext(level.getServer(), UUID.randomUUID());

      helper.succeedWhen(() -> {
         helper.assertTrue(task.acquireLease(context), "waiting for overlapping move write lease");
         OperationTaskResult result = task.tick(context, level, WorldTaskBudget.forServerTick());
         helper.assertTrue(result == OperationTaskResult.ACTIVE || result == OperationTaskResult.COMPLETE,
            "overlapping move failed at " + task.phaseName() + " with " + result);
         helper.assertTrue(result == OperationTaskResult.COMPLETE,
            "waiting for overlapping move commit: " + task.phaseName() + ", " + task.metricsSummary());
         helper.assertTrue(level.getBlockState(origin).isAir(), "overlapping move did not clear its first source");
         for (int index = 1; index <= count; index++) {
            helper.assertTrue(level.getBlockState(origin.offset(index, 0, 0)).is(Blocks.GOLD_BLOCK),
               "overlapping move omitted target " + index);
         }
         helper.assertTrue(WorldHistoryManager.commitPreparedOperation(context, task.preparedBatch(), task.journal()),
            "completed overlapping move could not publish history");
         task.releaseMemoryReservation();
         task.releaseCommittedTransactionState();
         task.releaseLease(context);
      });
   }
}
