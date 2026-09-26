package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlacementContextGameTests {
   private PlacementContextGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void firstPointAndStateMatchVanillaPlacement(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setPos(helper.absolutePos(new BlockPos(1, 10, 1)).getCenter());
      player.setYRot(35);
      player.setXRot(30);
      BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
      for (var block : new net.minecraft.world.level.block.Block[] {Blocks.OAK_SLAB, Blocks.OAK_STAIRS, Blocks.OAK_LOG}) {
         for (Direction face : Direction.values()) {
            var stack = new ItemStack(block);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            Vec3 location = target.getCenter().add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
            var hit = new BlockHitResult(location, face, target, false);
            var vanilla = new BlockPlaceContext(player.level(), player, InteractionHand.MAIN_HAND, stack, hit);
            var snapshot = PlacementContextSnapshot.capture(player.level(), player, stack, hit, false);
            helper.assertTrue(snapshot.placementPosition().equals(vanilla.getClickedPos()), "first point differs from vanilla position");
            var expected = ((BlockItem)stack.getItem()).getBlock().getStateForPlacement(vanilla);
            helper.assertTrue(PlaceableItems.placementState(stack, player, snapshot).orElseThrow().equals(expected),
               "placement state differs from vanilla for " + block + " face " + face);
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void replaceableAndEmbeddedTargetsStayInTheHitBlock(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setPos(helper.absolutePos(new BlockPos(1, 10, 1)).getCenter());
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.OAK_SLAB));
      BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
      var hit = new BlockHitResult(target.getCenter().add(0, 0.5, 0), Direction.UP, target, false);
      helper.getLevel().setBlockAndUpdate(target, Blocks.OAK_SLAB.defaultBlockState());
      var snapshot = PlacementContextSnapshot.capture(player.level(), player, player.getMainHandItem(), hit, false);
      helper.assertTrue(snapshot.placementPosition().equals(target), "slab merge preview moved outside the target");
      FastPlaceManager.addInitialPoint(player, hit, false);
      helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().getFirst().equals(target), "slab merge selected another cell");
      FastPlaceManager.cancel(player);
      helper.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
      snapshot = PlacementContextSnapshot.capture(player.level(), player, player.getMainHandItem(), hit, true);
      helper.assertTrue(snapshot.equals(PlacementContextSnapshot.capture(player.level(), player, player.getMainHandItem(), hit, false)),
         "embedded placement changed the entry orientation");
      FastPlaceManager.addInitialPoint(player, hit, true);
      helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().getFirst().equals(target), "embedded point moved outside the hit block");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }
}
