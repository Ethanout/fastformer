package io.github.fastformer.server.input;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.network.payload.placement.IdleSelectionEntryPayload;
import io.github.fastformer.network.payload.placement.TinkerInteractionPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleTinkerEntryGameTests {
   private IdleTinkerEntryGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "idle_tinker")
   public static void doubleClickUsesOriginalShapeAndRestoresOriginalHistory(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
      lookDown(player, position, 2);
      var previousWrite = io.github.fastformer.fastplace.world.BlockEditTransaction.apply(player, helper.getLevel(),
         java.util.Map.of(position.east(2), Blocks.STONE.defaultBlockState()),
         io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY.flags(),
         io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY.flags());
      helper.assertTrue(previousWrite == io.github.fastformer.fastplace.world.BlockEditTransaction.Outcome.APPLIED,
         "prior world edit was not recorded");
      var prior = WorldHistoryManager.latestOperationId(player);
      click(player, 501);
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.DIRT_PATH), "first wrench click did not apply immediately");
      var operation = WorldHistoryManager.latestOperationId(player);
      helper.assertTrue(operation != null && !operation.equals(prior), "single wrench click was not recorded");
      var sideEye = position.getCenter().add(0, 0.49, -3);
      var restored = ServerInputDispatcher.idleSelectionEntry(player,
         new IdleSelectionEntryPayload(502, 501, sideEye, new Vec3(0, 0, 1)));
      helper.assertTrue(position.equals(restored), "double click did not restore the source before selection entry");
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.GRASS_BLOCK), "original grass state was not restored");
      helper.assertTrue(java.util.Objects.equals(prior, WorldHistoryManager.latestOperationId(player)), "canceled wrench displaced prior world history");
      helper.assertTrue(IdleInteractionSnapshots.consume(player, 501, player.getEyePosition(), player.getViewVector(1)) == null,
         "double click reused the same snapshot");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_tinker")
   public static void conflictingWorldChangePreventsRestoration(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
      lookDown(player, position, 2);
      click(player, 511);
      helper.getLevel().setBlock(position, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var result = ServerInputDispatcher.idleSelectionEntry(player,
         new IdleSelectionEntryPayload(512, 511, player.getEyePosition(), player.getViewVector(1)));
      helper.assertTrue(result == null, "stale wrench state started a selection");
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.DIAMOND_BLOCK), "restoration overwrote a newer world change");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_tinker")
   public static void doubleClickRestoresBothDoorHalves(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      var lower = Blocks.IRON_DOOR.defaultBlockState();
      helper.getLevel().setBlock(position.below(), Blocks.STONE.defaultBlockState(), 2);
      helper.getLevel().setBlock(position, lower, 2);
      helper.getLevel().setBlock(position.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
      player.setPos(position.getCenter().add(0, -1.62, -2));
      player.setXRot(0);
      player.setYRot(0);
      var originals = IdleTinkerSnapshot.capture(helper.getLevel(), java.util.Set.of(position, position.above()));
      click(player, 521);
      helper.assertTrue(helper.getLevel().getBlockState(position).getValue(BlockStateProperties.OPEN), "first click did not open the door");
      var changedShape = io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot.capture(helper.getLevel(), position);
      var target = changedShape.boxes().getFirst().getCenter().add(Vec3.atLowerCornerOf(position));
      var view = target.subtract(player.getEyePosition()).normalize();
      helper.assertTrue(IdleInteractionSnapshots.consume(player, 521, player.getEyePosition(), view) != null, "door restore failed");
      for (var entry : originals.entrySet()) helper.assertTrue(entry.getValue().matches(helper.getLevel(), entry.getKey()), "door half was not restored");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_tinker")
   public static void doubleClickRetainsFurnaceInventory(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), 2);
      var furnace = (AbstractFurnaceBlockEntity) helper.getLevel().getBlockEntity(position);
      furnace.setItem(0, new ItemStack(Items.IRON_ORE, 3));
      furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 4));
      var original = ReversibleBlockSnapshot.capture(helper.getLevel(), position).orElseThrow();
      player.setPos(position.getCenter().add(0, -1.62, -2));
      player.setXRot(0);
      player.setYRot(0);
      click(player, 531);
      helper.assertTrue(helper.getLevel().getBlockState(position).getValue(BlockStateProperties.LIT), "first click did not light the furnace");
      helper.assertTrue(IdleInteractionSnapshots.consume(player, 531, player.getEyePosition(), player.getViewVector(1)) != null, "furnace restore failed");
      helper.assertTrue(original.matches(helper.getLevel(), position), "furnace state or block entity data changed");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_tinker")
   public static void wrenchUsesPlayerInteractionRangeAndRejectsRepeatedTokens(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
      lookDown(player, position, 40);
      click(player, 541);
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.GRASS_BLOCK), "distant wrench changed a block outside player reach");
      var hit = LongRangeBlockRaycast.clipForSelection(player.level(), player, player.getEyePosition(), player.getViewVector(1), false).hit();
      helper.assertTrue(!io.github.fastformer.fastplace.interaction.BlockTinker.consumes(player, hit), "distant wrench still consumed the session entry");
      player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(48);
      click(player, 542);
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.DIRT_PATH), "wrench ignored the player's extended interaction range");
      click(player, 542);
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.DIRT_PATH), "repeated token applied a second wrench action");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      return player;
   }

   private static void lookDown(ServerPlayer player, BlockPos position, int distance) {
      player.setPos(position.getCenter().add(0, distance, 0));
      player.setXRot(90);
   }

   private static void click(ServerPlayer player, long token) {
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, false).hit();
      ServerInputDispatcher.tinker(player, new TinkerInteractionPayload(token, hit, eye, view));
   }
}
