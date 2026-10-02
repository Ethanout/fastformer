package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.interaction.ProtectedInteractionTick;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.session.OperationManager;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.workspace.submission.OperationWorkspaceValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkspaceSceneGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "workspace_scene", timeoutTicks = 40)
   public static void newerLayerWithReusedIdWinsAndAirIsTransparent(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 2));
      var first = part(8, pos, Blocks.STONE.defaultBlockState(), BlockPos.ZERO);
      var last = part(1, pos, Blocks.GOLD_BLOCK.defaultBlockState(), BlockPos.ZERO);
      var air = part(2, pos, Blocks.AIR.defaultBlockState(), BlockPos.ZERO);
      var result = OperationWorkspaceValidator.validate(new OperationWorkspacePlan(List.of(first, last, air)), 16);
      helper.assertTrue(result.success() && result.writes().get(pos).state().is(Blocks.GOLD_BLOCK),
         "server composition disagrees with creation-order preview");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scene", timeoutTicks = 40)
   public static void cascadingPlantRemovalIsOwnedButExternalChangesAreNot(GameTestHelper helper) {
      var level = helper.getLevel();
      var base = helper.absolutePos(new BlockPos(2, 2, 2));
      level.setBlock(base, Blocks.DIRT.defaultBlockState(), 18);
      level.setBlock(base.above(), Blocks.TALL_GRASS.defaultBlockState(), 18);
      level.setBlock(base.above(2), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 18);
      var transaction = new WorldChangeTransaction();
      for (int y = 0; y < 3; y++) {
         var pos = base.above(y);
         transaction.recordExpected(pos, ReversibleBlockSnapshot.capture(level, pos).orElseThrow());
      }
      try (var scope = new WorkspaceWriteScope(level, transaction)) {
         level.setBlock(base, Blocks.AIR.defaultBlockState(), 3);
         helper.assertTrue(scope.conflict() == null, "own plant updates caused a conflict");
      }
      helper.assertTrue(level.getBlockState(base.above(2)).isAir(), "plant did not cascade beyond the immediate neighbor");
      helper.assertTrue(transaction.expectedAt(base.above(2)).matches(level, base.above(2)), "distant callback was not captured");
      helper.assertTrue(transaction.beforeCount() == 3, "undo does not contain each original cell exactly once");
      level.setBlock(base, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
      try (var scope = new WorkspaceWriteScope(level, transaction)) {
         level.setBlock(base, Blocks.STONE.defaultBlockState(), 18);
         helper.assertTrue(base.equals(scope.conflict()), "external edit was incorrectly treated as our callback");
      }
      helper.assertTrue(level.getBlockState(base).is(Blocks.DIAMOND_BLOCK), "external edit was overwritten");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scene", timeoutTicks = 40)
   public static void tickBarrierRetainsFluidTicksAndDoesNotBlockDirectEdits(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(3, 3, 3));
      var tick = new ScheduledTick<net.minecraft.world.level.material.Fluid>(Fluids.WATER, pos, level.getGameTime(), TickPriority.HIGH, 0L);
      ((ProtectedInteractionTick)(Object)tick).fastformer$suppressNeighbors(true);
      try (var barrier = new WorkspaceTickBarrier(level, List.of(pos))) {
         helper.assertTrue(WorkspaceTickBarrier.defer(level.getFluidTicks(), tick), "fluid tick was not deferred");
         helper.assertTrue(level.getFluidTicks().hasScheduledTick(pos, Fluids.WATER), "fluid tick was discarded");
         helper.assertTrue(WorkspaceTickBarrier.pausesRandomTick(level, pos), "random tick is not protected");
         helper.assertFalse(WorkspaceTickBarrier.pausesRandomTick(level, pos.offset(128, 0, 0)), "barrier extends to distant chunks");
         level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "direct edit was blocked");
      }
      helper.assertFalse(WorkspaceTickBarrier.pausesRandomTick(level, pos), "barrier remained after close");
      helper.assertFalse(WorkspaceTickBarrier.defer(level.getFluidTicks(), tick), "ticks did not resume");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "workspace_scene_submit", timeoutTicks = 1200)
   public static void overlappingSwapCommitsOnceAndUndoRestoresBothSources(GameTestHelper helper) {
      var level = helper.getLevel();
      var player = helper.makeMockServerPlayerInLevel();
      var a = helper.absolutePos(new BlockPos(2, 3, 2));
      var b = a.east();
      level.setBlock(a, Blocks.GOLD_BLOCK.defaultBlockState(), 18);
      level.setBlock(b, Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
      var plan = new OperationWorkspacePlan(List.of(
         part(8, a, Blocks.GOLD_BLOCK.defaultBlockState(), BlockPos.ZERO.east()),
         part(1, b, Blocks.DIAMOND_BLOCK.defaultBlockState(), BlockPos.ZERO.west())));
      int[] phase = {0};
      helper.succeedWhen(() -> {
         try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
         OperationManager.tickWorld(level.getServer());
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertFalse(WorldWriteCoordinator.busy(level.getServer(), level.dimension()) || WorldHistoryManager.busy(player.getUUID()), "waiting for writes");
         if (phase[0] == 0) {
            helper.assertTrue(OperationManager.applyWorkspace(player, UUID.randomUUID(), plan).isQueued(), "swap refused");
            phase[0] = 1;
            helper.assertTrue(false, "waiting for swap");
         }
         if (phase[0] == 1) {
            helper.assertTrue(level.getBlockState(a).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(b).is(Blocks.GOLD_BLOCK), "overlapping sources erased a target");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "undo refused");
            phase[0] = 2;
            helper.assertTrue(false, "waiting for undo");
         }
         helper.assertTrue(level.getBlockState(a).is(Blocks.GOLD_BLOCK) && level.getBlockState(b).is(Blocks.DIAMOND_BLOCK), "undo did not restore both sources");
      });
   }

   private static OperationWorkspacePlan.Part part(int id, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, BlockPos offset) {
      return new OperationWorkspacePlan.Part(id, ClientSelectionPart.Source.WORLD, Map.of(pos, new ClientBlockSnapshot(state, null)),
         WorkspaceTransform.IDENTITY.withTranslation(net.minecraft.world.phys.Vec3.atLowerCornerOf(offset)), false);
   }
}
