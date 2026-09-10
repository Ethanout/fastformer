package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
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
public final class SelectionOperationRecoveryGameTests {
   private SelectionOperationRecoveryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = "selection_startup_recovery", timeoutTicks = 20000)
   public static void overlappingMoveRecoversAfterSourceClear(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 33, 1));
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
         helper.assertTrue(task.acquireLease(context), "waiting for recovery-test write lease");
         OperationTaskResult result = task.tick(context, level, WorldTaskBudget.testing(1, 1, Long.MAX_VALUE, () -> 0L));
         helper.assertTrue(result == OperationTaskResult.ACTIVE, "move finished before the simulated crash point");
         helper.assertTrue("验证快照".equals(task.phaseName()), "waiting for source clearing to finish");
         helper.assertTrue(task.journal() != null, "move did not prepare its unsealed recovery journal");
         helper.assertTrue(level.getBlockState(origin).isAir(), "move did not clear its source-only position");
         for (int index = 1; index < count; index++) {
            helper.assertTrue(level.getBlockState(origin.offset(index, 0, 0)).is(Blocks.GOLD_BLOCK),
               "move cleared overlapping source " + index);
         }

         helper.assertTrue(PersistentRecoveryJournal.recoverAll(level.getServer()), "startup recovery failed");
         for (int index = 0; index < count; index++) {
            helper.assertTrue(level.getBlockState(origin.offset(index, 0, 0)).is(Blocks.GOLD_BLOCK),
               "startup recovery lost source " + index);
         }
         helper.assertTrue(level.getBlockState(origin.offset(count, 0, 0)).isAir(),
            "startup recovery changed the untouched destination");
         task.releaseMemoryReservation();
         task.releaseLease(context);
      });
   }
}
