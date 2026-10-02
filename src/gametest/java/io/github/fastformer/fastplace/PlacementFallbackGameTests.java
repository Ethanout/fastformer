package io.github.fastformer.fastplace;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.context.PlacementFallbackRule;
import io.github.fastformer.fastplace.placement.context.PlacementFallbacks;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlacementFallbackGameTests {
   private PlacementFallbackGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void unsupportedLaddersFacePlayerAndKeepFirstPointRotation(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      BlockPos floor = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
      var stack = new ItemStack(Blocks.LADDER);
      for (int yaw : new int[] {0, 90, 180, 270}) {
         player.setYRot(yaw);
         player.setYHeadRot(yaw);
         var hit = new BlockHitResult(floor.getCenter().add(0, 0.5, 0), Direction.UP, floor, false);
         var snapshot = PlacementContextSnapshot.capture(player.level(), player, stack, hit, false);
         var context = snapshot.context(player.level(), player, stack);
         helper.assertTrue(Blocks.LADDER.getStateForPlacement(context) == null, "test ladder unexpectedly has support");
         Direction expected = player.getDirection().getOpposite();
         player.setYRot(yaw + 90);
         player.setYHeadRot(yaw + 90);
         var state = PlaceableItems.placementState(stack, player, snapshot).orElseThrow();
         helper.assertTrue(state.getValue(BlockStateProperties.HORIZONTAL_FACING) == expected,
            "unsupported ladder lost the first point direction for yaw " + yaw);
         helper.assertTrue(!state.canSurvive(helper.getLevel(), snapshot.placementPosition()), "test ladder has a wall");
         helper.getLevel().setBlock(snapshot.placementPosition(), state, PlacementUpdateMode.CLIENT_ONLY.flags());
         helper.assertTrue(helper.getLevel().getBlockState(snapshot.placementPosition()).equals(state),
            "unsupported ladder changed on placement");
         helper.getLevel().setBlock(snapshot.placementPosition(), Blocks.AIR.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void supportedLaddersKeepVanillaWallDirection(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setYRot(0);
      BlockPos wall = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
      var stack = new ItemStack(Blocks.LADDER);
      var hit = new BlockHitResult(wall.getCenter().add(0.5, 0, 0), Direction.EAST, wall, false);
      var snapshot = PlacementContextSnapshot.capture(player.level(), player, stack, hit, false);
      var expected = Blocks.LADDER.getStateForPlacement(snapshot.context(player.level(), player, stack));
      helper.assertTrue(expected != null && expected.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
         "test wall does not select east");
      helper.assertTrue(PlaceableItems.placementState(stack, player, snapshot).orElseThrow().equals(expected),
         "fallback replaced a valid vanilla state");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void unsupportedLaddersKeepWaterAndExplicitItemProperties(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setYRot(90);
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      helper.getLevel().setBlock(target, Blocks.WATER.defaultBlockState(), PlacementUpdateMode.CLIENT_ONLY.flags());
      var stack = new ItemStack(Blocks.LADDER);
      var hit = new BlockHitResult(target.getCenter(), Direction.UP, target, false);
      var snapshot = PlacementContextSnapshot.capture(player.level(), player, stack, hit, false);
      var state = PlaceableItems.placementState(stack, player, snapshot).orElseThrow();
      helper.assertTrue(state.getValue(BlockStateProperties.WATERLOGGED), "fallback removed source water");
      stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of("facing", "south", "waterlogged", "false")));
      state = PlaceableItems.placementState(stack, player, snapshot).orElseThrow();
      helper.assertTrue(state.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.SOUTH
         && !state.getValue(BlockStateProperties.WATERLOGGED), "fallback overrode explicit item properties");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void unsupportedButtonsAndLeversKeepClickedAttachment(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setYRot(90);
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      for (Block block : new Block[] {Blocks.OAK_BUTTON, Blocks.STONE_BUTTON, Blocks.LEVER}) {
         var stack = new ItemStack(block);
         for (Direction face : Direction.values()) {
            var snapshot = VirtualSupportGameTests.snapshot(target, face);
            helper.assertTrue(block.getStateForPlacement(snapshot.context(player.level(), player, stack)) == null,
               "test attachment unexpectedly has support");
            var state = PlaceableItems.placementState(stack, player, snapshot).orElseThrow();
            var expectedFace = face == Direction.UP ? AttachFace.FLOOR : face == Direction.DOWN ? AttachFace.CEILING : AttachFace.WALL;
            var expectedFacing = face.getAxis().isHorizontal() ? face : player.getDirection();
            helper.assertTrue(state.getValue(BlockStateProperties.ATTACH_FACE) == expectedFace
               && state.getValue(BlockStateProperties.HORIZONTAL_FACING) == expectedFacing,
               "wrong unsupported attachment for " + block + " on " + face);
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void dataRulesRespectPriorityDisableAndAtomicAssignments(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var stack = new ItemStack(Blocks.LADDER);
      BlockPos target = helper.absolutePos(new BlockPos(2, 3, 2));
      var hit = new BlockHitResult(target.getCenter(), Direction.UP, target, false);
      var context = PlacementContextSnapshot.capture(player.level(), player, stack, hit, false).context(player.level(), player, stack);
      var rules = new MappedRegistry<>(PlacementFallbacks.REGISTRY, Lifecycle.stable());
      addRule(rules, "z_low", 0, true, "\"facing\":\"west\"");
      addRule(rules, "z_high", 10, true, "\"facing\":\"south\"");
      addRule(rules, "a_high", 10, true, "\"facing\":\"east\"");
      addRule(rules, "disabled", 100, false, "\"facing\":\"north\"");
      addRule(rules, "incompatible", 200, true, "\"facing\":\"north\",\"missing_property\":\"true\"");
      addRule(rules, "invalid_value", 300, true, "\"facing\":\"up\",\"waterlogged\":\"true\"");
      var state = PlacementFallbacks.resolve(rules, Blocks.LADDER.defaultBlockState(), context);
      helper.assertTrue(state.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
         "data rule priority or stable tie order changed");
      helper.assertTrue(!state.getValue(BlockStateProperties.WATERLOGGED), "incompatible rule partially changed the state");
      helper.assertTrue(PlacementFallbacks.resolve(rules, Blocks.STONE.defaultBlockState(), context).is(Blocks.STONE),
         "rule affected a block outside its tag");
      var disabled = new MappedRegistry<>(PlacementFallbacks.REGISTRY, Lifecycle.stable());
      addRule(disabled, "ladder", 0, false, "");
      helper.assertTrue(PlacementFallbacks.resolve(disabled, Blocks.LADDER.defaultBlockState(), context)
         .equals(Blocks.LADDER.defaultBlockState()), "disabled rule did not retain the default state");
      helper.succeed();
   }

   private static void addRule(Registry<PlacementFallbackRule> rules, String id, int priority, boolean enabled, String properties) {
      String json = "{\"block_tag\":\"fastformer:placement_fallback/ladder\",\"priority\":" + priority
         + ",\"enabled\":" + enabled + ",\"properties\":{" + properties + "}}";
      var rule = PlacementFallbackRule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
      var encoded = PlacementFallbackRule.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow();
      var decoded = PlacementFallbackRule.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
      Registry.register(rules, ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, id), decoded);
   }
}
