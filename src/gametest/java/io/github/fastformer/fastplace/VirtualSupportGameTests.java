package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.context.PlacementSupportQuery;
import io.github.fastformer.fastplace.placement.context.VirtualSupportPlacement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VirtualSupportGameTests {
   private VirtualSupportGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void itemPlacementKeepsWallVariantsWithoutSupport(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      var snapshot = snapshot(target, Direction.EAST);
      Item[] items = {Items.TORCH, Items.SOUL_TORCH, Items.REDSTONE_TORCH, Items.OAK_SIGN, Items.WHITE_BANNER};
      Block[] expected = {Blocks.WALL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.REDSTONE_WALL_TORCH,
         Blocks.OAK_WALL_SIGN, Blocks.WHITE_WALL_BANNER};
      for (int i = 0; i < items.length; i++) {
         var state = PlaceableItems.placementState(new ItemStack(items[i]), player, snapshot).orElseThrow();
         helper.assertTrue(state.is(expected[i]), "wall variant was lost for " + items[i] + ": " + state);
         helper.assertTrue(state.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
            "wall variant has the wrong direction for " + items[i]);
      }
      helper.assertTrue(helper.getLevel().getBlockState(target.west()).isAir(), "virtual support changed the world");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void virtualSupportKeepsSingleBellAttachmentAndVerticalShapes(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      var bell = PlaceableItems.placementState(new ItemStack(Blocks.BELL), player, snapshot(target, Direction.EAST)).orElseThrow();
      helper.assertTrue(bell.getValue(BlockStateProperties.BELL_ATTACHMENT) == BellAttachType.SINGLE_WALL,
         "virtual support created a double-wall bell");
      helper.assertTrue(bell.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.WEST, "bell direction changed");
      for (Block block : new Block[] {Blocks.LANTERN, Blocks.SOUL_LANTERN}) {
         var lantern = PlaceableItems.placementState(new ItemStack(block), player, snapshot(target, Direction.DOWN)).orElseThrow();
         helper.assertTrue(lantern.getValue(BlockStateProperties.HANGING), "ceiling lantern reverted to a standing lantern");
      }
      var dripstone = PlaceableItems.placementState(new ItemStack(Blocks.POINTED_DRIPSTONE), player, snapshot(target, Direction.DOWN)).orElseThrow();
      helper.assertTrue(dripstone.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN, "dripstone lost its downward direction");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void vinesAndMultifaceBlocksGainOnlyOneAttachment(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      for (Block block : new Block[] {Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN}) {
         var state = PlaceableItems.placementState(new ItemStack(block), player, snapshot(target, Direction.EAST)).orElseThrow();
         int faces = 0;
         for (Direction direction : Direction.values()) {
            var property = MultifaceBlock.getFaceProperty(direction);
            if (state.hasProperty(property) && state.getValue(property)) faces++;
         }
         helper.assertTrue(faces == 1 && state.getValue(BlockStateProperties.WEST), "wrong attached faces for " + block + ": " + state);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void queryRestoresWorldReadsAfterNestingAndFailure(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos first = helper.absolutePos(new BlockPos(2, 3, 2));
      BlockPos second = first.east();
      level.setBlock(first, Blocks.CHEST.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
      level.setBlock(second, Blocks.WATER.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
      var chest = level.getBlockEntity(first);
      try {
         PlacementSupportQuery.withSupport(level, first, () -> {
            helper.assertTrue(level.getBlockState(first).is(Blocks.STONE), "query did not expose support");
            helper.assertTrue(level.getBlockEntity(first) == null, "support retained the real block entity");
            helper.assertTrue(level.getChunkAt(first).getBlockState(first).is(Blocks.CHEST), "query wrote a real support block");
            helper.assertTrue(level.getFluidState(second).getType() == Fluids.WATER, "query changed another position's fluid");
            helper.assertTrue(CompletableFuture.supplyAsync(() -> PlacementSupportQuery.blockState(level, first) == null).join(),
               "support escaped to another thread");
            PlacementSupportQuery.withSupport(level, second, () -> {
               helper.assertTrue(level.getBlockState(first).is(Blocks.CHEST), "nested query retained a second support");
               helper.assertTrue(level.getFluidState(second).isEmpty(), "virtual stone has water");
               return null;
            });
            helper.assertTrue(level.getBlockState(first).is(Blocks.STONE), "nested query did not restore its parent");
            throw new QueryFailure();
         });
         throw new AssertionError("expected query failure");
      } catch (QueryFailure expected) {
         helper.assertTrue(level.getBlockState(first).is(Blocks.CHEST) && level.getBlockEntity(first) == chest,
            "query failure leaked the virtual support");
         helper.assertTrue(level.getFluidState(second).getType() == Fluids.WATER, "query failure changed water");
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void failedVirtualSupportFallsThroughDataRulesToDefault(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      var cocoaStack = new ItemStack(Blocks.COCOA);
      var cocoaContext = snapshot(target, Direction.EAST).context(player.level(), player, cocoaStack);
      helper.assertTrue(VirtualSupportPlacement.resolve((BlockItem)cocoaStack.getItem(), cocoaContext, cocoaContext) == null,
         "stone support unexpectedly replaced the cocoa log requirement");
      helper.assertTrue(PlaceableItems.placementState(cocoaStack, player, snapshot(target, Direction.EAST)).orElseThrow()
         .equals(Blocks.COCOA.defaultBlockState()), "failed support and data rules did not return the default state");

      BlockPos outside = new BlockPos(target.getX(), helper.getLevel().getMaxBuildHeight(), target.getZ());
      var ladderStack = new ItemStack(Blocks.LADDER);
      var ladderSnapshot = snapshot(outside, Direction.UP);
      var ladderContext = ladderSnapshot.context(player.level(), player, ladderStack);
      helper.assertTrue(VirtualSupportPlacement.resolve((BlockItem)ladderStack.getItem(), ladderContext, ladderContext) == null,
         "virtual support bypassed the world height limit");
      var fallback = PlaceableItems.placementState(ladderStack, player, ladderSnapshot).orElseThrow();
      helper.assertTrue(fallback.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
         "failed virtual support did not reach the ladder data rule");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void realFenceConnectionsRemainUnchanged(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      var stack = new ItemStack(Blocks.OAK_FENCE);
      var snapshot = snapshot(target, Direction.EAST);
      for (Block neighbor : new Block[] {Blocks.AIR, Blocks.STONE}) {
         helper.getLevel().setBlock(target.west(), neighbor.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
         var expected = Blocks.OAK_FENCE.getStateForPlacement(snapshot.context(player.level(), player, stack));
         helper.assertTrue(PlaceableItems.placementState(stack, player, snapshot).orElseThrow().equals(expected),
            "virtual support changed a valid fence connection");
      }
      helper.getLevel().setBlock(target.west(), Blocks.AIR.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
      var skullStack = new ItemStack(Items.SKELETON_SKULL);
      var vanillaSkull = ((BlockItem)skullStack.getItem()).getPlacementState(snapshot.context(player.level(), player, skullStack));
      helper.assertTrue(vanillaSkull != null && vanillaSkull.is(Blocks.SKELETON_SKULL), "vanilla no longer allows a standing skull without support");
      helper.assertTrue(PlaceableItems.placementState(skullStack, player, snapshot).orElseThrow().equals(vanillaSkull),
         "virtual support replaced a valid standing skull");
      helper.succeed();
   }

   static PlacementContextSnapshot snapshot(BlockPos target, Direction clickedFace) {
      BlockPos clicked = target.relative(clickedFace.getOpposite());
      return new PlacementContextSnapshot(clicked, clicked.getCenter(), clickedFace, false, false,
         90, Direction.WEST, Direction.UP,
         List.of(Direction.WEST, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.EAST), false);
   }

   private static final class QueryFailure extends RuntimeException {}
}
