package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.OperationSystem;
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

   @GameTest(template = "fastformergametests.empty", batch = "explicit_entry", timeoutTicks = 600)
   public static void explicitEntryCanStartQuickShapeWithinVanillaReach(GameTestHelper helper) {
      var player = idleStartPlayer(helper);
      player.setPos(helper.absolutePos(new BlockPos(1, 4, 1)).getCenter());
      var settings = FastPlaceSettings.load(player);
      settings.setMode(player, PointMode.RAYCAST);
      PreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      helper.succeedWhen(() -> {
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
         helper.assertTrue(eye.distanceTo(hit.getLocation()) < 3, "test target was outside close range");
         ServerInputDispatcher.startPlacement(player, startRequest(player, 90L, hit, eye, view));
         helper.assertTrue(QuickShapeSystem.of(player).active(player), "explicit entry did not own the near click");
         SessionSystem.of(player).cancelEditing(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "overlap_submission", timeoutTicks = 6000)
   public static void overlappingRightClickSubmitsPointNearBlocksWithMiddleDisabled(GameTestHelper helper) {
      checkConfirmedSubmission(helper, true);
   }

   @GameTest(template = "fastformergametests.empty", batch = "overlap_submission", timeoutTicks = 6000)
   public static void confirmedSubmissionDoesNotIncludeScrollCandidate(GameTestHelper helper) {
      checkConfirmedSubmission(helper, false);
   }

   private static void checkConfirmedSubmission(GameTestHelper helper, boolean pointer) {
      var player = idleStartPlayer(helper);
      var settings = FastPlaceSettings.load(player);
      settings.setMode(player, LineMode.FREE_SCROLL);
      settings.setMode(player, FaceMode.COORDINATE_PLANE);
      settings.setMiddleConfirmEnabled(player, false);
      var first = helper.absolutePos(new BlockPos(1, 4, 1));
      QuickShapeSystem.of(player).addPoint(player, first, first);
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      draft.setFreeScrollOffset(pointer ? BlockPos.ZERO : new BlockPos(3, 0, 0));
      SessionSystem.of(player).setModifierHeld(player, false);
      player.setPos(first.getCenter());
      PreviewSync.syncPreview(player, draft);
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
            helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension()), "waiting for world write");
            if (pointer) {
               ServerInputDispatcher.quickShapePointer(player, new QuickShapePointerPayload(
                  PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player),
                  QuickShapePointerPayload.Action.SUBMIT_CURRENT, first, player.getEyePosition(), player.getViewVector(1), false, 71));
            } else QuickShapeSystem.of(player).fillConfirmed(player);
            helper.assertFalse(QuickShapeSystem.of(player).active(player), "confirmed submission did not end the draft");
            submitted[0] = true;
         }
         QuickShapeSystem.of(helper.getLevel().getServer()).tickWorld(helper.getLevel().getServer(), io.github.fastformer.server.history.HistorySystem.of(helper.getLevel().getServer()));
         helper.assertFalse(QuickShapeSystem.of(player).taskActive(player), "waiting for placement");
         helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.GOLD_BLOCK), "confirmed point was not placed");
         helper.assertTrue(helper.getLevel().getBlockState(first.offset(3, 0, 0)).isAir(), "ghost candidate was placed");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "embedded_middle_finish", timeoutTicks = 6000)
   public static void surfaceStartedDraftFinishesWithMiddleNearBlocks(GameTestHelper helper) {
      var player = idleStartPlayer(helper);
      var settings = FastPlaceSettings.load(player);
      settings.setMode(player, PointMode.RAYCAST);
      settings.setMode(player, LineMode.FREE_SCROLL);
      settings.setMode(player, FaceMode.COORDINATE_PLANE);
      settings.setMiddleConfirmEnabled(player, true);
      SessionSystem.of(player).setModifierHeld(player, false);
      PreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(
         player.level(), player, eye, view).hit();
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
            helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history initialization");
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension()), "waiting for world write");
            ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(51L,
               new StartPlacementPayload.Target(PreviewSync.buildingRevision(player),
                  PreviewSync.callbackScope(player), hit, eye, view)));
            var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
            helper.assertTrue(draft.points().getFirst().equals(hit.getBlockPos().relative(hit.getDirection())), "surface start did not use the adjacent position");
            helper.assertFalse(draft.modifierHeld(), "embedded start latched Alt on the draft");
            helper.assertFalse(SessionSystem.of(player).modifierHeld(player), "embedded start latched player Alt");
            draft.setFreeScrollOffset(new BlockPos(2, 0, 0));
            player.setPos(hit.getBlockPos().getCenter().add(0, 1, 0));
            PreviewSync.syncPreview(player, draft);
            var nearEye = player.getEyePosition();
            var nearView = player.getViewVector(1);
            var nearHit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(
               player.level(), player, nearEye, nearView).hit();
            var candidate = QuickShapeSystem.of(player).candidateContext(player).resolve(nearHit, nearEye, nearView);
            ServerInputDispatcher.quickShapePointer(player, new QuickShapePointerPayload(
               PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player),
               QuickShapePointerPayload.Action.MIDDLE, candidate, nearEye, nearView, false, 52));
            helper.assertFalse(QuickShapeSystem.of(player).active(player), "middle did not finish a surface-started draft near a block");
            submitted[0] = true;
         }
         QuickShapeSystem.of(helper.getLevel().getServer()).tickWorld(helper.getLevel().getServer(), io.github.fastformer.server.history.HistorySystem.of(helper.getLevel().getServer()));
         helper.assertFalse(QuickShapeSystem.of(player).taskActive(player), "waiting for placement");
         helper.assertTrue(helper.getLevel().getBlockState(hit.getBlockPos().relative(hit.getDirection())).is(Blocks.GOLD_BLOCK), "middle finish did not place the shape");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "alt_quick_shape_pointer", timeoutTicks = 6000)
   public static void altMiddleFinishesLine(GameTestHelper helper) {
      assertAltMiddleFinishes(helper, false);
   }

   @GameTest(template = "fastformergametests.empty", batch = "alt_quick_shape_pointer", timeoutTicks = 6000)
   public static void altMiddleFinishesFace(GameTestHelper helper) {
      assertAltMiddleFinishes(helper, true);
   }

   private static void assertAltMiddleFinishes(GameTestHelper helper, boolean face) {
      var player = idleStartPlayer(helper);
      player.setXRot(-90);
      var settings = FastPlaceSettings.load(player);
      settings.setMode(player, face ? LineMode.AXIS : LineMode.FREE_SCROLL);
      settings.setMode(player, FaceMode.COORDINATE_PLANE);
      settings.setMiddleConfirmEnabled(player, true);
      BlockPos first = helper.absolutePos(new BlockPos(1, 5, 1));
      QuickShapeSystem.of(player).addPoint(player, first, first);
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      if (face) draft.addPoint(first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1));
      draft.setFreeScrollOffset(new BlockPos(0, 0, 3));
      SessionSystem.of(player).setModifierHeld(player, true);
      PreviewSync.syncPreview(player, draft);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      var candidate = QuickShapeSystem.of(player).candidateContext(player).resolve(hit, eye, view);
      var request = new QuickShapePointerPayload(PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player),
         QuickShapePointerPayload.Action.MIDDLE, candidate, eye, view, true, 1);
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         // GameTest ticks run without the normal delay; allow asynchronous history writes to finish.
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
            helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history initialization");
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension()), "waiting for the other test's world write");
            ServerInputDispatcher.quickShapePointer(player, request);
            helper.assertFalse(QuickShapeSystem.of(player).active(player), "Alt-middle did not finish the draft: points=" + draft.points()
               + ", candidate=" + candidate + ", modifier=" + draft.modifierHeld()
               + ", faceMode=" + FastPlaceSettings.load(player).faceMode()
               + ", revision=" + PreviewSync.buildingRevision(player) + "/" + request.revision());
            submitted[0] = true;
         }
         QuickShapeSystem.of(helper.getLevel().getServer()).tickWorld(helper.getLevel().getServer(), io.github.fastformer.server.history.HistorySystem.of(helper.getLevel().getServer()));
         helper.assertFalse(QuickShapeSystem.of(player).taskActive(player), "waiting for placement");
         helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.GOLD_BLOCK), "Alt-middle did not place the shape");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void capturedPointSurvivesViewChangeAndRejectsReplay(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, LineMode.FREE_SCROLL);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      QuickShapeSystem.of(player).addPoint(player, first, first);
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      draft.setFreeScrollOffset(new BlockPos(3, 0, 0));
      PreviewSync.syncPreview(player, draft);
      var request = new QuickShapePointerPayload(PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player),
         QuickShapePointerPayload.Action.POINT, first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1), false, 1);
      player.setXRot(0);
      player.setYRot(90);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(QuickShapeSystem.of(player).session(player).orElse(null) == draft, "test draft was replaced before dispatch");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "pointer input is blocked by a world task"));
         helper.assertFalse(io.github.fastformer.server.session.GeometrySystem.of(player).active(player), "geometry owns input");
         helper.assertFalse(io.github.fastformer.server.session.OperationSystem.of(player).active(player), "selection owns input");
         ServerInputDispatcher.quickShapePointer(player, request);
         helper.assertTrue(draft.points().equals(java.util.List.of(first, first.offset(3, 0, 0))),
            "press target changed with current view: points=" + draft.points() + " revision=" + PreviewSync.buildingRevision(player) + " captured=" + request.revision());
         ServerInputDispatcher.quickShapePointer(player, request);
         helper.assertTrue(draft.points().size() == 2, "replayed pointer changed newer draft");
         var undo = new QuickShapePointerPayload(request.revision(), request.scope(), QuickShapePointerPayload.Action.UNDO,
            request.candidate(), request.eye(), request.view(), false, 2);
         ServerInputDispatcher.quickShapePointer(player, undo);
         helper.assertTrue(draft.points().equals(java.util.List.of(first)), "ordered undo lost its captured revision");
         PreviewSync.syncPreview(player, draft);
         var stale = new QuickShapePointerPayload(request.revision(), request.scope(), QuickShapePointerPayload.Action.POINT,
            request.candidate(), request.eye(), request.view(), false, 3);
         ServerInputDispatcher.quickShapePointer(player, stale);
         helper.assertTrue(draft.points().equals(java.util.List.of(first)), "external revision change accepted old pointer chain");
         draft.setFreeScrollOffset(new BlockPos(2, 3, 4));
         helper.assertTrue(io.github.fastformer.server.intent.PlayerIntentSystem.dispatch(player, io.github.fastformer.core.command.IntentSource.COMMAND, new io.github.fastformer.server.intent.HistoryIntents.Back()).handled(), "command back rejected the active draft");
         helper.assertTrue(draft.points().isEmpty(), "command back kept the last point");
         helper.assertTrue(draft.freeScrollOffset().equals(BlockPos.ZERO), "command back kept candidate offsets");
         helper.assertTrue(QuickShapeSystem.of(player).session(player).isEmpty(), "command back did not end the empty draft");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void pointThenCloseFromOneRevisionClosesPolygon(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, FaceMode.POLYGON);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      QuickShapeSystem.of(player).addPoint(player, first, first);
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      draft.addPoint(first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1));
      draft.addPoint(first.offset(0, 0, 3), player.getEyePosition(), player.getViewVector(1));
      PreviewSync.syncPreview(player, draft);
      long revision = PreviewSync.buildingRevision(player);
      var scope = PreviewSync.callbackScope(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      var candidate = QuickShapeSystem.of(player).candidateContext(player).resolve(hit, eye, view);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(QuickShapeSystem.of(player).session(player).orElse(null) == draft, "polygon test draft was replaced before dispatch");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "polygon input is blocked by a world task"));
         ServerInputDispatcher.quickShapePointer(player,
            new QuickShapePointerPayload(revision, scope, QuickShapePointerPayload.Action.POINT, candidate, eye, view, false, 1));
         ServerInputDispatcher.quickShapePointer(player,
            new QuickShapePointerPayload(revision, scope, QuickShapePointerPayload.Action.CLOSE, candidate, eye, view, false, 2));
         helper.assertTrue(draft.polygonClosed(), "point consumed the queued close revision");
         SessionSystem.of(player).cancelEditing(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer")
   public static void pointerCodecPreservesCapturedRay(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var request = new QuickShapePointerPayload(7, PreviewSync.callbackScope(player), QuickShapePointerPayload.Action.MIDDLE,
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
      PreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "idle start is blocked by a world task"));
         helper.assertTrue(hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, "idle start capture missed the test world");
         var request = startRequest(player, 41L, hit, eye, view);
         player.setXRot(0);
         player.setYRot(90);
         ServerInputDispatcher.startPlacement(player, request);
         helper.assertTrue(QuickShapeSystem.of(player).active(player), "server reread the rotated view instead of the frozen start ray");
         helper.assertTrue(QuickShapeSystem.of(player).session(player).orElseThrow().points().equals(java.util.List.of(
            hit.getBlockPos().relative(hit.getDirection())
         )), "idle surface start did not use the captured placement position");
         SessionSystem.of(player).cancelEditing(player);
         var replayWithCurrentIdentity = startRequest(player, 41L, hit, eye, view);
         ServerInputDispatcher.startPlacement(player, replayWithCurrentIdentity);
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "replayed idle start recreated a draft");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_pointer", timeoutTicks = 600)
   public static void idleStartRejectsStalePreviewIdentity(GameTestHelper helper) {
      var player = idleStartPlayer(helper);
      PreviewSync.syncSettings(player);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var hit = io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit();
      var stale = startRequest(player, 42L, hit, eye, view);
      PreviewSync.syncSettings(player);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), blockedMessage(player, "idle start is blocked by a world task"));
         helper.assertTrue(hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK, "idle start capture missed the test world");
         ServerInputDispatcher.startPlacement(player, stale);
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "stale inactive preview revision started a draft");
         var wrongScope = new StartPlacementPayload.Target(PreviewSync.buildingRevision(player),
            new io.github.fastformer.protocol.callback.OperationCallbackScope(player.getUUID(), player.level().dimension().location(), java.util.UUID.randomUUID()),
            hit, eye, view);
         ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(43L, wrongScope));
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "stale callback scope started a draft");
         ServerInputDispatcher.startPlacement(player, startRequest(player, 44L, hit, eye, view));
         helper.assertTrue(QuickShapeSystem.of(player).active(player), "a current idle start was blocked after stale requests");
         SessionSystem.of(player).cancelEditing(player);
      });
   }

   private static net.minecraft.server.level.ServerPlayer idleStartPlayer(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
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
         PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player), hit, eye, view
      ));
   }

   private static String blockedMessage(net.minecraft.server.level.ServerPlayer player, String message) {
      return message + ": placementTaskActive=" + QuickShapeSystem.of(player).taskActive(player)
         + ", operationTaskActive=" + OperationSystem.of(player).taskActive(player)
         + ", historyBusy=" + HistorySystem.of(player).busy(player);
   }
}
