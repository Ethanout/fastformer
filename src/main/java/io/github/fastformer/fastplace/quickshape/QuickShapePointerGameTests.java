package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.ServerInputDispatcher;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.network.payload.placement.QuickShapePointerPayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuickShapePointerGameTests {
   private QuickShapePointerGameTests() { }

   private static final long HISTORY_INITIALIZATION_TIMEOUT_MILLIS = 10_000L;

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void capturedPointSurvivesViewChangeAndRejectsReplay(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, LineMode.FREE_SCROLL);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      FastPlaceManager.addPoint(player, first, first);
      var draft = FastPlaceManager.session(player).orElseThrow();
      draft.setFreeScrollOffset(new BlockPos(3, 0, 0));
      PlayerPreviewSync.syncPreview(player, draft);
      var request = new QuickShapePointerPayload(PlayerPreviewSync.buildingRevision(player), PlayerPreviewSync.callbackScope(player),
         QuickShapePointerPayload.Action.POINT, first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1), false, 1);
      player.setXRot(0);
      player.setYRot(90);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(FastPlaceManager.session(player).orElse(null) == draft, "test draft was replaced before dispatch");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "pointer input is blocked by a world task"));
         helper.assertFalse(io.github.fastformer.fastplace.GeometryManager.active(player), "geometry owns input");
         helper.assertFalse(io.github.fastformer.fastplace.OperationManager.active(player), "selection owns input");
         ServerInputDispatcher.quickShapePointer(player, request);
         helper.assertTrue(draft.points().equals(java.util.List.of(first, first.offset(3, 0, 0))),
            "press target changed with current view: points=" + draft.points() + " revision=" + PlayerPreviewSync.buildingRevision(player) + " captured=" + request.revision());
         ServerInputDispatcher.quickShapePointer(player, request);
         helper.assertTrue(draft.points().size() == 2, "replayed pointer changed newer draft");
         var undo = new QuickShapePointerPayload(request.revision(), request.scope(), QuickShapePointerPayload.Action.UNDO,
            request.candidate(), request.eye(), request.view(), false, 2);
         ServerInputDispatcher.quickShapePointer(player, undo);
         helper.assertTrue(draft.points().equals(java.util.List.of(first)), "ordered undo lost its captured revision");
         PlayerPreviewSync.syncPreview(player, draft);
         var stale = new QuickShapePointerPayload(request.revision(), request.scope(), QuickShapePointerPayload.Action.POINT,
            request.candidate(), request.eye(), request.view(), false, 3);
         ServerInputDispatcher.quickShapePointer(player, stale);
         helper.assertTrue(draft.points().equals(java.util.List.of(first)), "external revision change accepted old pointer chain");
         draft.setFreeScrollOffset(new BlockPos(2, 3, 4));
         helper.assertTrue(ServerInputDispatcher.commandBack(player), "command back rejected the active draft");
         helper.assertTrue(draft.points().isEmpty(), "command back kept the last point");
         helper.assertTrue(draft.freeScrollOffset().equals(BlockPos.ZERO), "command back kept candidate offsets");
         helper.assertTrue(FastPlaceManager.session(player).isEmpty(), "command back did not end the empty draft");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void pointThenCloseFromOneRevisionClosesPolygon(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, FaceMode.POLYGON);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      FastPlaceManager.addPoint(player, first, first);
      var draft = FastPlaceManager.session(player).orElseThrow();
      draft.addPoint(first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1));
      draft.addPoint(first.offset(0, 0, 3), player.getEyePosition(), player.getViewVector(1));
      PlayerPreviewSync.syncPreview(player, draft);
      long revision = PlayerPreviewSync.buildingRevision(player);
      var scope = PlayerPreviewSync.callbackScope(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      var candidate = FastPlaceManager.candidateContext(player).resolve(hit, eye, view);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(FastPlaceManager.session(player).orElse(null) == draft, "polygon test draft was replaced before dispatch");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "polygon input is blocked by a world task"));
         ServerInputDispatcher.quickShapePointer(player,
            new QuickShapePointerPayload(revision, scope, QuickShapePointerPayload.Action.POINT, candidate, eye, view, false, 1));
         ServerInputDispatcher.quickShapePointer(player,
            new QuickShapePointerPayload(revision, scope, QuickShapePointerPayload.Action.CLOSE, candidate, eye, view, false, 2));
         helper.assertTrue(draft.polygonClosed(), "point consumed the queued close revision");
         FastPlaceManager.cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer")
   public static void pointerCodecPreservesCapturedRay(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var request = new QuickShapePointerPayload(7, PlayerPreviewSync.callbackScope(player), QuickShapePointerPayload.Action.MIDDLE,
         new BlockPos(4, 5, 6), player.getEyePosition(), player.getViewVector(1), false, 9);
      var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
      try {
         QuickShapePointerPayload.STREAM_CODEC.encode(buffer, request);
         helper.assertTrue(request.equals(QuickShapePointerPayload.STREAM_CODEC.decode(buffer)), "pointer codec changed capture");
      } finally {
         buffer.release();
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void idleStartUsesFrozenRayAndRejectsReplay(GameTestHelper helper) {
      var player = idleStartPlayer(helper);
      PlayerPreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "idle start is blocked by a world task"));
         helper.assertTrue(hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, "idle start capture missed the test world");
         var request = startRequest(player, 41L, hit, eye, view);
         player.setXRot(0);
         player.setYRot(90);
         ServerInputDispatcher.startPlacement(player, request);
         helper.assertTrue(FastPlaceManager.active(player), "server reread the rotated view instead of the frozen start ray");
         helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().equals(java.util.List.of(
            hit.getBlockPos().relative(hit.getDirection())
         )), "idle start point did not use the captured surface hit");
         FastPlaceManager.cancel(player);
         var replayWithCurrentIdentity = startRequest(player, 41L, hit, eye, view);
         ServerInputDispatcher.startPlacement(player, replayWithCurrentIdentity);
         helper.assertFalse(FastPlaceManager.active(player), "replayed idle start recreated a draft");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void idleStartRejectsStalePreviewIdentity(GameTestHelper helper) {
      var player = idleStartPlayer(helper);
      PlayerPreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      var stale = startRequest(player, 42L, hit, eye, view);
      PlayerPreviewSync.syncSettings(player);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "idle start is blocked by a world task"));
         helper.assertTrue(hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, "idle start capture missed the test world");
         ServerInputDispatcher.startPlacement(player, stale);
         helper.assertFalse(FastPlaceManager.active(player), "stale inactive preview revision started a draft");
         var wrongScope = new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
            new io.github.fastformer.network.payload.operation.OperationCallbackScope(player.getUUID(), player.level().dimension().location(), java.util.UUID.randomUUID()),
            stale.target().placement(), hit, eye, view);
         ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(43L, wrongScope));
         helper.assertFalse(FastPlaceManager.active(player), "stale callback scope started a draft");
         ServerInputDispatcher.startPlacement(player, startRequest(player, 44L, hit, eye, view));
         helper.assertTrue(FastPlaceManager.active(player), "a current idle start was blocked after stale requests");
         FastPlaceManager.cancel(player);
      });
   }

   private static net.minecraft.server.level.ServerPlayer idleStartPlayer(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      return player;
   }

   private static StartPlacementPayload startRequest(net.minecraft.server.level.ServerPlayer player, long requestId,
      net.minecraft.world.phys.BlockHitResult hit, net.minecraft.world.phys.Vec3 eye, net.minecraft.world.phys.Vec3 view) {
      return new StartPlacementPayload(requestId, new StartPlacementPayload.Target(
         PlayerPreviewSync.buildingRevision(player), PlayerPreviewSync.callbackScope(player), RaycastPlacement.SURFACE, hit, eye, view
      ));
   }

   private static String blockedMessage(net.minecraft.server.level.ServerPlayer player, String message) {
      return message + ": placementTaskActive=" + FastPlaceManager.taskActive(player)
         + ", operationTaskActive=" + OperationManager.taskActive(player)
         + ", historyBusy=" + WorldHistoryManager.busy(player);
   }
}
