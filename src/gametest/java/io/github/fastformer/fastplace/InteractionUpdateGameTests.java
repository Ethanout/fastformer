package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockActivityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InteractionUpdateGameTests {
   private static final int QUIET = 2 | 16;

   @GameTest(template = "fastformergametests.empty", batch = "interaction_updates")
   public static void leverPreservesUnsupportedAndCustomNeighbors(GameTestHelper helper) {
      var level = helper.getLevel();
      var player = creative(helper);
      BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
      BlockState fence = crossFence();
      level.setBlock(pos, Blocks.LEVER.defaultBlockState(), QUIET);
      level.setBlock(pos.east(), fence, QUIET);
      level.setBlock(pos.above(), Blocks.TORCH.defaultBlockState(), QUIET);
      use(player, pos);
      helper.assertTrue(level.getBlockState(pos).getValue(LeverBlock.POWERED), "lever did not switch");
      helper.assertTrue(level.getBlockState(pos.east()) == fence, "custom fence connections changed");
      helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH), "unsupported torch broke");
      helper.assertTrue(InteractionUpdateScope.currentLevel() == null, "interaction scope leaked");
      FastPlaceSettings.load(player).setPlacementUpdateMode(player, PlacementUpdateMode.NORMAL);
      use(player, pos);
      helper.assertTrue(level.getBlockState(pos.above()).isAir(), "normal mode no longer updates neighbors");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "interaction_updates", timeoutTicks = 80)
   public static void buttonReleaseKeepsProtection(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
      level.setBlock(pos, Blocks.STONE_BUTTON.defaultBlockState(), QUIET);
      level.setBlock(pos.above(), Blocks.TORCH.defaultBlockState(), QUIET);
      use(creative(helper), pos);
      helper.assertTrue(level.getBlockState(pos).getValue(ButtonBlock.POWERED), "button did not press");
      // Exercise chunk save/load while the button is still pressed.
      var chunkTicks = (net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>)
         level.getChunkAt(pos).getBlockTicks();
      var pending = chunkTicks.getAll().filter(tick -> tick.pos().equals(pos)).findFirst().orElseThrow();
      var encoded = net.minecraft.world.ticks.SavedTick.saveTick(pending, block -> "button", level.getGameTime());
      var loaded = net.minecraft.world.ticks.SavedTick.loadTick(encoded,
         id -> java.util.Optional.of(Blocks.STONE_BUTTON)).orElseThrow();
      var savedAgain = loaded.save(block -> "button");
      var restored = net.minecraft.world.ticks.SavedTick.loadTick(savedAgain,
         id -> java.util.Optional.of(Blocks.STONE_BUTTON)).orElseThrow().unpack(level.getGameTime(), 0);
      chunkTicks.removeIf(tick -> tick.pos().equals(pos));
      level.getBlockTicks().schedule(restored);
      helper.runAfterDelay(25, () -> {
         helper.assertFalse(level.getBlockState(pos).getValue(ButtonBlock.POWERED), "button did not release");
         helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.TORCH), "release broke the neighbor");
         helper.assertTrue(InteractionUpdateScope.currentLevel() == null, "tick scope leaked");
         helper.succeed();
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "interaction_updates")
   public static void failedInteractionRestoresOuterPolicy(GameTestHelper helper) {
      var level = helper.getLevel();
      try {
         InteractionUpdateScope.run(level, () -> InteractionUpdateScope.runScheduled(() -> {
            throw new IllegalStateException("test failure");
         }));
         helper.fail("scope swallowed the exception");
      } catch (IllegalStateException expected) {
         helper.assertFalse(InteractionUpdateScope.suppresses(level), "failed scope leaked");
         helper.assertFalse(InteractionUpdateScope.protectsScheduledTicks(), "failed tick scope leaked");
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "interaction_updates")
   public static void doorUpdatesBothHalvesWithoutChangingFence(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
      var door = Blocks.OAK_DOOR.defaultBlockState();
      level.setBlock(pos, door, QUIET);
      level.setBlock(pos.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), QUIET);
      level.setBlock(pos.east(), crossFence(), QUIET);
      var player = creative(helper);
      use(player, pos);
      helper.assertTrue(level.getBlockState(pos).getValue(DoorBlock.OPEN), "lower door stayed shut");
      helper.assertTrue(level.getBlockState(pos.above()).getValue(DoorBlock.OPEN), "upper door stayed shut");
      use(player, pos.above());
      helper.assertFalse(level.getBlockState(pos).getValue(DoorBlock.OPEN), "lower door stayed open");
      helper.assertTrue(level.getBlockState(pos.east()) == crossFence(), "door changed fence connections");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "block_falling_rule", timeoutTicks = 60)
   public static void fallingRuleStopsPowderAndAnvilWithoutFreezingTime(GameTestHelper helper) {
      var level = helper.getLevel();
      var rules = BlockActivityRules.get(level.getServer());
      boolean previous = rules.fallingDisabled();
      long time = level.getGameTime();
      BlockPos powder = helper.absolutePos(new BlockPos(1, 4, 1));
      BlockPos anvil = helper.absolutePos(new BlockPos(3, 4, 1));
      BlockPos suspicious = helper.absolutePos(new BlockPos(1, 4, 3));
      BlockPos scaffold = helper.absolutePos(new BlockPos(3, 4, 3));
      BlockPos dripstone = helper.absolutePos(new BlockPos(2, 4, 2));
      rules.setFallingDisabled(true);
      level.setBlock(powder, Blocks.WHITE_CONCRETE_POWDER.defaultBlockState(), QUIET);
      level.setBlock(anvil, Blocks.ANVIL.defaultBlockState(), QUIET);
      level.setBlock(suspicious, Blocks.SUSPICIOUS_SAND.defaultBlockState(), QUIET);
      level.setBlock(scaffold, Blocks.SCAFFOLDING.defaultBlockState(), QUIET);
      level.setBlock(dripstone, Blocks.POINTED_DRIPSTONE.defaultBlockState()
         .setValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN), QUIET);
      level.scheduleTick(powder, Blocks.WHITE_CONCRETE_POWDER, 1);
      level.scheduleTick(anvil, Blocks.ANVIL, 1);
      level.scheduleTick(suspicious, Blocks.SUSPICIOUS_SAND, 1);
      level.scheduleTick(scaffold, Blocks.SCAFFOLDING, 1);
      level.scheduleTick(dripstone, Blocks.POINTED_DRIPSTONE, 1);
      helper.runAfterDelay(8, () -> {
         try {
            helper.assertTrue(level.getBlockState(powder).is(Blocks.WHITE_CONCRETE_POWDER), "powder fell");
            helper.assertTrue(level.getBlockState(anvil).is(Blocks.ANVIL), "anvil fell");
            helper.assertTrue(level.getBlockState(suspicious).is(Blocks.SUSPICIOUS_SAND), "suspicious sand fell");
            helper.assertTrue(level.getBlockState(scaffold).is(Blocks.SCAFFOLDING), "scaffolding collapsed");
            helper.assertTrue(level.getBlockState(dripstone).is(Blocks.POINTED_DRIPSTONE), "dripstone fell");
            helper.assertTrue(level.getGameTime() > time, "world time stopped");
            rules.setFallingDisabled(false);
            level.getBlockState(powder).tick(level, powder, level.random);
            helper.assertTrue(level.getBlockState(powder).isAir(), "powder cannot fall after rule is disabled");
            helper.succeed();
         } finally { rules.setFallingDisabled(previous); }
      });
   }

   private static ServerPlayer creative(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      return player;
   }

   private static BlockState crossFence() {
      return Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.NORTH, true)
         .setValue(FenceBlock.SOUTH, true).setValue(FenceBlock.EAST, true).setValue(FenceBlock.WEST, true);
   }

   private static void use(ServerPlayer player, BlockPos pos) {
      var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
      player.level().getBlockState(pos).useWithoutItem(player.level(), player, hit);
   }
}
