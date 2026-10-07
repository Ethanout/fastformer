package io.github.fastformer.server.input;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.placement.IdleInteractionCapturePayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import io.github.fastformer.server.session.FastPlaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleEntryGameTests {
   private IdleEntryGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void ordinaryClicksDoNotCreateAPointDraft(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      helper.assertFalse(ServerInputDispatcher.rightClickBlock(player, hit), "ordinary use started a building draft");
      helper.assertFalse(ServerInputDispatcher.rightClickItem(player), "ordinary item use started a building draft");
      helper.assertFalse(ServerInputDispatcher.leftClickBlock(player, hit.getBlockPos()), "ordinary attack started a selection");
      ServerInputDispatcher.middleClick(player, hit.getBlockPos());
      helper.assertTrue(IdleInteractionSnapshots.idle(player), "ordinary click changed the idle session");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void secondPressUsesSnapshotAfterTheWorldChanges(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(101, hit, player.getEyePosition(), player.getViewVector(1)));
      helper.getLevel().setBlock(hit.getBlockPos(), Blocks.AIR.defaultBlockState(), 2);
      ServerInputDispatcher.startPlacement(player, request(player, 101, hit));
      var draft = FastPlaceManager.session(player).orElseThrow();
      helper.assertTrue(draft.points().equals(java.util.List.of(hit.getBlockPos())), "entry did not anchor exactly one point to the snapshot");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void successfulPlacementReplacesTheFirstHitShape(GameTestHelper helper) {
      var player = player(helper);
      helper.succeedWhen(() -> {
      helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
         player.getServer(), player.level().dimension()), "waiting for world writer");
      helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
      var first = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(102, first, player.getEyePosition(), player.getViewVector(1)));
      var result = player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, first);
      helper.assertTrue(result.consumesAction(), "first placement was not consumed");
      var placed = first.getBlockPos().above();
      helper.assertTrue(helper.getLevel().getBlockState(placed).is(Blocks.OAK_LOG), "first placement did not produce a log");
      var second = hit(player);
      helper.assertTrue(second.getBlockPos().equals(placed), "second ray did not hit the placed shape");
      ServerInputDispatcher.startPlacement(player, request(player, 102, second));
      helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().equals(java.util.List.of(placed)),
         "double-click used the old hit instead of the placed shape");
      helper.assertTrue(helper.getLevel().getBlockState(placed.above()).isAir(), "entry placed a second log");
      FastPlaceManager.cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void changingTheHeldItemInvalidatesTheSnapshot(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(103, hit, player.getEyePosition(), player.getViewVector(1)));
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      ServerInputDispatcher.startPlacement(player, request(player, 103, hit));
      helper.assertFalse(FastPlaceManager.active(player), "old snapshot survived a held-item change");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
      player.setPos(pos.getCenter().add(0, 2, 0));
      player.setXRot(90);
      PlayerPreviewSync.syncSettings(player);
      return player;
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_embedded_submit", timeoutTicks = 6000)
   public static void embeddedMiddleEntrySubmitsOnlyTheFirstPoint(GameTestHelper helper) {
      var player = player(helper);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      var hit = hit(player);
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
            helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
               player.getServer(), player.level().dimension()), "waiting for world writer");
            var target = new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
               PlayerPreviewSync.callbackScope(player), hit, player.getEyePosition(), player.getViewVector(1), 0, true, true);
            ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(104, target));
            helper.assertFalse(FastPlaceManager.active(player), "middle entry left a line draft instead of submitting");
            submitted[0] = true;
         }
         FastPlaceManager.tickWorld(helper.getLevel().getServer());
         helper.assertFalse(FastPlaceManager.taskActive(player), "waiting for placement");
         helper.assertFalse(WorldHistoryManager.busy(player), "waiting for history publication");
         helper.assertTrue(helper.getLevel().getBlockState(hit.getBlockPos()).is(Blocks.OAK_LOG), "embedded entry did not replace the hit block");
         helper.assertTrue(helper.getLevel().getBlockState(hit.getBlockPos().above()).isAir(), "embedded entry placed an adjacent block");
      });
   }

   private static net.minecraft.world.phys.BlockHitResult hit(ServerPlayer player) {
      return LongRangeBlockRaycast.clipForPlacement(player.level(), player, player.getEyePosition(), player.getViewVector(1)).hit();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry")
   public static void distantEntryUsesCurrentRangeAndPlacementRay(GameTestHelper helper) {
      var player = player(helper);
      var stone = hit(player).getBlockPos();
      helper.getLevel().setBlock(stone.above(), Blocks.SHORT_GRASS.defaultBlockState(), 2);
      var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
      attribute.setBaseValue(20);
      var near = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(near.getBlockPos().equals(stone.above()), "near entry did not preserve the original grass hit");
      helper.assertFalse(LongRangeBlockRaycast.beyondInteractionRange(player, near), "current extended vanilla range was ignored");
      attribute.setBaseValue(player.getEyePosition().distanceTo(near.getLocation()));
      helper.assertFalse(LongRangeBlockRaycast.beyondInteractionRange(player, near), "the range boundary was treated as distant");
      attribute.setBaseValue(1);
      var distant = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(distant.getBlockPos().equals(stone), "distant placement entry did not use the placement ray");
      helper.assertTrue(LongRangeBlockRaycast.beyondInteractionRange(player, distant), "updated vanilla range did not enable direct entry");
      helper.succeed();
   }

   private static StartPlacementPayload request(ServerPlayer player, long token, net.minecraft.world.phys.BlockHitResult hit) {
      return new StartPlacementPayload(token, new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
         PlayerPreviewSync.callbackScope(player), hit, player.getEyePosition(), player.getViewVector(1), token));
   }
}
