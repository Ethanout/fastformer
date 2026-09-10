package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskFailureRecoveryGameTests {
   private static final String BATCH = "task_failure_recovery";
   private static final int COUNT = 16;

   private TaskFailureRecoveryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 20000)
   public static void cancelledOverlappingMoveRestoresOwnedWritesAndPreservesExternalChange(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 113, 1));
      initializeAlternating(level, origin);
      BlockPos max = origin.offset(COUNT - 1, 0, 0);
      var selection = OperationSelectionVolume.cuboid(origin, max, origin, max);
      var task = new SelectionOperationTask(
         selection, OperationMode.MOVE, OperationConflictMode.KEEP_EXISTING, false,
         new BlockPos(2, 0, 0), OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY,
         1024, level.dimension()
      );
      var context = new WorldTaskContext(level.getServer(), UUID.randomUUID());
      boolean[] firstClearObserved = {false};
      boolean[] recovering = {false};

      helper.succeedWhen(() -> {
         if (!recovering[0]) {
            helper.assertTrue(task.acquireLease(context), "waiting for overlapping move lease");
            OperationTaskResult result = task.tick(context, level, oneCellBudget());
            helper.assertTrue(result == OperationTaskResult.ACTIVE, "overlapping move ended before cancellation: " + result);
            if (task.transaction().beforeCount() < 1) {
               helper.assertTrue(false, "waiting for first source clear");
            }
            if (!firstClearObserved[0]) {
               firstClearObserved[0] = true;
               helper.assertTrue(false, "waiting for second source clear");
            }
            helper.assertTrue(task.transaction().beforeCount() == 2, "move wrote past the cancellation boundary");
            level.setBlock(origin, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
            helper.assertTrue(WorldHistoryManager.acceptStoppedTask(context, task).recoveryCreated(),
               "partial overlapping move did not create recovery");
            recovering[0] = true;
         }

         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(context.owner()), "waiting for overlapping move recovery");
         helper.assertTrue(!WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
            "overlapping move recovery retained the world lease");
         helper.assertTrue(level.getBlockState(origin).is(Blocks.EMERALD_BLOCK),
            "recovery overwrote an external source change");
         assertAlternating(helper, level, origin, 1, COUNT);
         helper.assertTrue(level.getBlockState(origin.offset(COUNT, 0, 0)).isAir(),
            "cancelled move changed its first destination-only position");
         helper.assertTrue(level.getBlockState(origin.offset(COUNT + 1, 0, 0)).isAir(),
            "cancelled move changed its second destination-only position");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 20000)
   public static void workspaceWriteConflictRecoversOwnedWritesAndPreservesExternalChanges(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 129, 1));
      initializeAlternating(level, origin);
      ClientWorkspacePlacementTask task = workspaceTask(level, origin);
      var context = new WorldTaskContext(level.getServer(), UUID.randomUUID());
      int[] observedWrites = {0};
      boolean[] recovering = {false};
      BlockPos[] externalWritten = {null};
      BlockPos[] externalPending = {null};

      helper.succeedWhen(() -> {
         if (!recovering[0]) {
            helper.assertTrue(task.acquireLease(context), "waiting for workspace failure-test lease");
            OperationTaskResult result = task.tick(context, level, oneCellBudget());
            helper.assertTrue(result == OperationTaskResult.ACTIVE || result == OperationTaskResult.FAILED,
               "workspace ended unexpectedly before recovery: " + result);
            int writes = task.transaction().beforeCount();
            if (writes < 2) {
               observedWrites[0] = writes;
               helper.assertTrue(false, "waiting for two workspace writes");
            }
            if (externalPending[0] == null) {
               observedWrites[0] = writes;
               List<BlockPos> written = positions(task.transaction().afterPositions());
               externalWritten[0] = written.get(0);
               externalPending[0] = firstUnwritten(task, written);
               level.setBlock(externalWritten[0], Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
               level.setBlock(externalPending[0], Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
               helper.assertTrue(false, "triggering the validated workspace conflict");
            }
            helper.assertTrue(result == OperationTaskResult.FAILED,
               "workspace accepted an externally changed validated target");
            helper.assertTrue(task.transaction().beforeCount() == observedWrites[0],
               "failed workspace write changed recovery ownership");
            helper.assertTrue(WorldHistoryManager.acceptStoppedTask(context, task).recoveryCreated(),
               "failed workspace task did not create recovery");
            recovering[0] = true;
         }

         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(context.owner()), "waiting for workspace failure recovery");
         helper.assertTrue(!WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
            "workspace recovery retained the world lease");
         for (int index = 0; index < COUNT; index++) {
            BlockPos pos = origin.offset(index, 0, 0);
            if (pos.equals(externalWritten[0]) || pos.equals(externalPending[0])) {
               helper.assertTrue(level.getBlockState(pos).is(Blocks.EMERALD_BLOCK),
                  "workspace recovery overwrote external change at " + pos.toShortString());
            } else {
               helper.assertTrue(level.getBlockState(pos).is(originalBlock(index)),
                  "workspace recovery did not restore original at " + pos.toShortString());
            }
         }
      });
   }

   private static ClientWorkspacePlacementTask workspaceTask(ServerLevel level, BlockPos origin) {
      Map<BlockPos, ClientBlockSnapshot> desired = new LinkedHashMap<>();
      for (int index = 0; index < COUNT; index++) {
         desired.put(origin.offset(index, 0, 0),
            new ClientBlockSnapshot(Blocks.DIAMOND_BLOCK.defaultBlockState(), null));
      }
      var part = new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.CLIPBOARD, desired, WorkspaceTransform.IDENTITY, false
      );
      return new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of(part)),
         PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension()
      );
   }

   private static BlockPos firstUnwritten(ClientWorkspacePlacementTask task, List<BlockPos> written) {
      var positions = task.transaction().expectedPositions();
      while (positions.hasNext()) {
         BlockPos position = positions.next();
         if (!written.contains(position)) {
            return position;
         }
      }
      throw new IllegalStateException("Workspace did not retain an unwritten validated target");
   }

   private static List<BlockPos> positions(java.util.Iterator<BlockPos> positions) {
      List<BlockPos> result = new ArrayList<>();
      positions.forEachRemaining(result::add);
      return result;
   }

   private static WorldTaskBudget oneCellBudget() {
      return WorldTaskBudget.testing(1, 1, 0L, () -> 0L);
   }

   private static void initializeAlternating(ServerLevel level, BlockPos origin) {
      for (int index = 0; index < COUNT; index++) {
         level.setBlock(origin.offset(index, 0, 0), originalBlock(index).defaultBlockState(), 2);
      }
      level.setBlock(origin.offset(COUNT, 0, 0), Blocks.AIR.defaultBlockState(), 2);
      level.setBlock(origin.offset(COUNT + 1, 0, 0), Blocks.AIR.defaultBlockState(), 2);
   }

   private static void assertAlternating(
      GameTestHelper helper, ServerLevel level, BlockPos origin, int fromInclusive, int toExclusive
   ) {
      for (int index = fromInclusive; index < toExclusive; index++) {
         helper.assertTrue(level.getBlockState(origin.offset(index, 0, 0)).is(originalBlock(index)),
            "recovery did not restore source " + index);
      }
   }

   private static Block originalBlock(int index) {
      return index % 2 == 0 ? Blocks.GOLD_BLOCK : Blocks.STONE;
   }
}
