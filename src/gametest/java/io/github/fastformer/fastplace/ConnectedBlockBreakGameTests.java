package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConnectedBlockBreakGameTests {
   private static final int QUIET = PlacementUpdateMode.CLIENT_ONLY.flags();

   private ConnectedBlockBreakGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "connected_break")
   public static void quietCreativeBreakKeepsOtherHalvesAndNearbyCustomStates(GameTestHelper helper) {
      var player = player(helper, GameType.CREATIVE);
      FastPlaceSettings.load(player).setPlacementUpdateMode(player, PlacementUpdateMode.CLIENT_ONLY);
      for (Pair pair : pairs()) for (boolean second : new boolean[] {false, true}) {
         assertBreak(helper, player, pair, second, true);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "connected_break")
   public static void normalCreativeBreakStillRemovesBothHalves(GameTestHelper helper) {
      var player = player(helper, GameType.CREATIVE);
      FastPlaceSettings.load(player).setPlacementUpdateMode(player, PlacementUpdateMode.NORMAL);
      for (Pair pair : pairs()) for (boolean second : new boolean[] {false, true}) {
         assertBreak(helper, player, pair, second, false);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "connected_break")
   public static void survivalAndDisabledModKeepVanillaBreakBehavior(GameTestHelper helper) {
      var player = player(helper, GameType.SURVIVAL);
      FastPlaceSettings.load(player).setPlacementUpdateMode(player, PlacementUpdateMode.CLIENT_ONLY);
      for (Pair pair : pairs()) assertBreak(helper, player, pair, true, false);
      player.setGameMode(GameType.CREATIVE);
      FastPlaceSettings.load(player).toggleEnabled(player);
      helper.assertFalse(FastPlaceSettings.load(player).enabled(), "test did not disable the mod");
      for (Pair pair : pairs()) assertBreak(helper, player, pair, true, false);
      helper.succeed();
   }

   private static void assertBreak(GameTestHelper helper, ServerPlayer player, Pair pair, boolean second, boolean quiet) {
      var level = helper.getLevel();
      var firstPos = helper.absolutePos(new BlockPos(2, 2, 2));
      var secondPos = firstPos.relative(pair.direction());
      // Clear the previous structure before installing the next complete pair.
      level.setBlock(firstPos, Blocks.AIR.defaultBlockState(), QUIET);
      level.setBlock(firstPos.above(), Blocks.AIR.defaultBlockState(), QUIET);
      level.setBlock(firstPos.east(), Blocks.AIR.defaultBlockState(), QUIET);
      level.setBlock(firstPos.below(), Blocks.STONE.defaultBlockState(), QUIET);
      level.setBlock(firstPos.east().below(), Blocks.STONE.defaultBlockState(), QUIET);
      level.setBlock(firstPos, pair.first(), QUIET);
      level.setBlock(secondPos, pair.second(), QUIET);
      var fencePos = firstPos.west();
      var fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.NORTH, true)
         .setValue(BlockStateProperties.SOUTH, true).setValue(BlockStateProperties.WEST, true)
         .setValue(BlockStateProperties.EAST, true);
      level.setBlock(fencePos, fence, QUIET);
      BlockPos clicked = second ? secondPos : firstPos;
      BlockPos retained = second ? firstPos : secondPos;
      BlockState original = second ? pair.first() : pair.second();
      helper.assertTrue(player.gameMode.destroyBlock(clicked), "player could not break " + pair.first().getBlock());
      helper.assertFalse(level.getBlockState(clicked).is(pair.first().getBlock())
         || level.getBlockState(clicked).is(pair.second().getBlock()), "clicked part was not removed");
      if (quiet) {
         helper.assertTrue(level.getBlockState(retained).equals(original), "quiet break removed or changed the other part of " + pair.first().getBlock());
         helper.assertTrue(level.getBlockState(fencePos).equals(fence), "quiet break changed nearby custom connections");
      } else {
         helper.assertFalse(level.getBlockState(retained).is(original.getBlock()), "vanilla break no longer removed the other part of " + pair.first().getBlock());
      }
      helper.assertTrue(InteractionUpdateScope.currentLevel() == null, "break leaked its update scope");
   }

   private static ServerPlayer player(GameTestHelper helper, GameType mode) {
      // GameTest's mock overrides isCreative() to always return true.
      var connectionOwner = helper.makeMockServerPlayerInLevel();
      var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
         new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "connected-break-player"),
         net.minecraft.server.level.ClientInformation.createDefault());
      player.connection = connectionOwner.connection;
      player.setGameMode(mode);
      return player;
   }

   private static List<Pair> pairs() {
      var pairs = new java.util.ArrayList<Pair>();
      for (var block : List.of(Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.COPPER_DOOR, Blocks.TALL_GRASS,
         Blocks.LARGE_FERN, Blocks.SUNFLOWER, Blocks.TALL_SEAGRASS, Blocks.SMALL_DRIPLEAF, Blocks.PITCHER_CROP)) {
         BlockState lower = block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
         if (lower.hasProperty(BlockStateProperties.AGE_4)) lower = lower.setValue(BlockStateProperties.AGE_4, 4);
         pairs.add(new Pair(lower, lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Direction.UP));
      }
      var bed = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
      pairs.add(new Pair(bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT),
         bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), Direction.EAST));
      for (var piston : List.of(Blocks.PISTON, Blocks.STICKY_PISTON)) {
         var base = piston.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST)
            .setValue(BlockStateProperties.EXTENDED, true);
         var head = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST)
            .setValue(BlockStateProperties.PISTON_TYPE, piston == Blocks.PISTON
               ? net.minecraft.world.level.block.state.properties.PistonType.DEFAULT
               : net.minecraft.world.level.block.state.properties.PistonType.STICKY);
         pairs.add(new Pair(base, head, Direction.EAST));
      }
      return List.copyOf(pairs);
   }

   private record Pair(BlockState first, BlockState second, Direction direction) {}
}
