package io.github.fastformer.fastplace;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.interaction.PointerGesture;
import io.github.fastformer.protocol.c2s.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryPointPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryUndoPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.GeometrySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeometryPointGameTests {
   private GeometryPointGameTests() { }

   private static final long HISTORY_INITIALIZATION_TIMEOUT_MILLIS = 10_000L;

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void capturedPointUsesFrozenViewAfterPlayerTurns(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      BlockPos target = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(90);
      GeometrySystem.of(player).selectMode(player, GeometryMode.WALL);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1.0F);
      var request = new GeometryPointPayload(
         1L, PreviewSync.geometryRevision(player), PreviewSync.callbackScope(player), eye, view
      );
      player.setXRot(0);
      player.setYRot(90);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "geometry point input is blocked: fastPlaceTask="
            + SessionSystem.of(player).placementTasks().active(player) + " operationTask=" + SessionSystem.of(player).operationTasks().active(player)
            + " worldHistoryBusy=" + HistorySystem.of(player).busy(player));
         ServerInputDispatcher.geometryPoint(player, request);
         helper.assertTrue(GeometrySystem.of(player).session(player).orElseThrow().points().equals(java.util.List.of(target.above())),
            "geometry point used the current view instead of the captured ray");
         GeometrySystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void capturedInteractionUsesFrozenViewAfterPlayerTurns(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      BlockPos first = helper.absolutePos(new BlockPos(1, 6, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(90);
      GeometrySystem.of(player).selectMode(player, GeometryMode.WALL);
      GeometrySystem.of(player).addPoint(player, first);
      GeometrySystem.of(player).addPoint(player, first.offset(3, 0, 0));
      GeometrySystem.of(player).addPoint(player, first.offset(0, 0, 3));
      var eye = player.getEyePosition();
      var view = player.getViewVector(1.0F);
      var request = new GeometryInteractionPayload(
         1L, PreviewSync.geometryRevision(player), PreviewSync.callbackScope(player),
         GeometryInteractionTarget.TargetType.CLOSE_PATH, 0, GeometryInteractionAction.CLOSE_PATH,
         PointerGesture.RIGHT_CLICK, eye, view
      );
      player.setXRot(0);
      player.setYRot(90);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(ServerInputDispatcher.geometryInteraction(player, request),
            "captured geometry interaction used the current view instead of the captured ray");
         helper.assertTrue(GeometrySystem.of(player).session(player).orElseThrow().closed(), "captured close did not close the path");
         GeometrySystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void capturedDragRejectsReplayAndReplacementDraft(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(0);
      GeometrySystem.of(player).selectMode(player, GeometryMode.POLYHEDRON);
      BlockPos center = helper.absolutePos(new BlockPos(1, 6, 1));
      GeometrySystem.of(player).addPoint(player, center);
      GeometrySystem.of(player).addPoint(player, center.offset(3, 0, 0));
      var draft = GeometrySystem.of(player).session(player).orElseThrow();
      var before = draft.pointLocations();
      var scope = PreviewSync.callbackScope(player);
      long revision = PreviewSync.geometryRevision(player);
      var move = new GeometryGizmoDragPayload(1L, revision, scope, draft.draftId(), 0, 0, 2, false);
      var next = new GeometryGizmoDragPayload(2L, revision, scope, draft.draftId(), 0, 0, 2, false);
      var finish = new GeometryGizmoDragPayload(3L, revision, scope, draft.draftId(), 0, 0, 0, true);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for player history");
         helper.assertTrue(ServerInputDispatcher.geometryGizmoDrag(player, move), "first drag delta was rejected");
         helper.assertFalse(ServerInputDispatcher.geometryGizmoDrag(player, move), "replayed drag delta was accepted");
         helper.assertTrue(ServerInputDispatcher.geometryGizmoDrag(player, next), "next delta lost the captured revision");
         helper.assertTrue(ServerInputDispatcher.geometryGizmoDrag(player, finish), "drag finish lost the captured revision");
         helper.assertTrue(draft.pointLocations().equals(before.stream().map(point -> point.add(2, 0, 0)).toList()),
            "drag deltas did not move the original draft exactly twice");
         GeometrySystem.of(player).selectMode(player, GeometryMode.POLYHEDRON);
         var replacement = GeometrySystem.of(player).session(player).orElseThrow();
         var replacementPoints = replacement.pointLocations();
         var stale = new GeometryGizmoDragPayload(4L, PreviewSync.geometryRevision(player), scope,
            move.draftId(), 0, 0, 2, false);
         helper.assertFalse(ServerInputDispatcher.geometryGizmoDrag(player, stale), "old drag mutated the replacement draft");
         helper.assertTrue(replacement.pointLocations().equals(replacementPoints), "replacement draft changed");
         GeometrySystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void dragDispatchDoesNotInvalidateAnAlreadyCapturedClick(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(0);
      GeometrySystem.of(player).selectMode(player, GeometryMode.CONVEX_POLYHEDRON);
      GeometrySystem.of(player).addPoint(player, helper.absolutePos(new BlockPos(1, 6, 1)));
      var draft = GeometrySystem.of(player).session(player).orElseThrow();
      draft.selectControlPoint(0);
      GeometrySystem.of(player).sync(player);
      long revision = PreviewSync.geometryRevision(player);
      var scope = PreviewSync.callbackScope(player);
      var click = GeometryInteractionPayload.clearSelection(1L, revision, scope, PointerGesture.LEFT_CLICK,
         player.getEyePosition(), player.getViewVector(1.0F));
      var drag = new GeometryGizmoDragPayload(2L, revision, scope, draft.draftId(), 0, 0, 1, false);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for player history");
         helper.assertTrue(ServerInputDispatcher.geometryGizmoDrag(player, drag), "drag delta was rejected");
         helper.assertTrue(ServerInputDispatcher.geometryInteraction(player, click), "drag dispatch invalidated the captured click");
         helper.assertTrue(draft.selectedControlPoint() == -1, "captured click did not clear the selected point");
         helper.assertFalse(ServerInputDispatcher.geometryInteraction(player, click), "replayed click was accepted");
         helper.assertFalse(ServerInputDispatcher.geometryGizmoDrag(player, drag), "replayed drag was accepted");
         GeometrySystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void capturedPointThenDoubleCloseSharesRevisionAndRejectsReplays(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      BlockPos target = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(90);
      GeometrySystem.of(player).selectMode(player, GeometryMode.WALL);
      GeometrySystem.of(player).addPoint(player, target.above().offset(3, 0, 0));
      GeometrySystem.of(player).addPoint(player, target.above().offset(3, 0, 3));
      var eye = player.getEyePosition();
      var view = player.getViewVector(1.0F);
      long revision = PreviewSync.geometryRevision(player);
      var point = new GeometryPointPayload(1L, revision, PreviewSync.callbackScope(player), eye, view);
      var close = new GeometryInteractionPayload(
         2L, revision, PreviewSync.callbackScope(player),
         GeometryInteractionTarget.TargetType.CLOSE_PATH, 0, GeometryInteractionAction.CLOSE_PATH,
         PointerGesture.RIGHT_DOUBLE_CLICK, eye, view
      );
      var stale = new GeometryInteractionPayload(
         3L, revision, PreviewSync.callbackScope(player),
         GeometryInteractionTarget.TargetType.CLOSE_PATH, 0, GeometryInteractionAction.CLOSE_PATH,
         PointerGesture.RIGHT_DOUBLE_CLICK, eye, view
      );

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(ServerInputDispatcher.geometryPoint(player, point), "captured point was not applied");
         helper.assertTrue(GeometrySystem.of(player).session(player).orElseThrow().points().size() == 3,
            "captured point did not create the third wall point");
         helper.assertTrue(ServerInputDispatcher.geometryInteraction(player, close),
            "double-click close did not accept the point revision");
         helper.assertTrue(GeometrySystem.of(player).session(player).orElseThrow().closed(), "double-click close did not close the wall path");
         helper.assertFalse(ServerInputDispatcher.geometryPoint(player, point), "replayed point changed the closed path");
         helper.assertFalse(ServerInputDispatcher.geometryInteraction(player, close), "replayed close changed the closed path");
         GeometrySystem.of(player).sync(player);
         helper.assertFalse(ServerInputDispatcher.geometryInteraction(player, stale),
            "an external preview revision accepted the old pointer sequence");
         GeometrySystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_point", timeoutTicks = 600)
   public static void capturedPointThenUndoUsesTheSameRevision(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      BlockPos target = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.STONE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(90);
      GeometrySystem.of(player).selectMode(player, GeometryMode.WALL);
      GeometrySystem.of(player).addPoint(player, target.above().offset(3, 0, 0));
      GeometrySystem.of(player).addPoint(player, target.above().offset(3, 0, 3));
      var eye = player.getEyePosition();
      var view = player.getViewVector(1.0F);
      long revision = PreviewSync.geometryRevision(player);
      var scope = PreviewSync.callbackScope(player);
      var point = new GeometryPointPayload(1L, revision, scope, eye, view);
      var draft = GeometrySystem.of(player).session(player).orElseThrow();
      var undo = new GeometryUndoPayload(2L, revision, scope, draft.draftId(), eye, view);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for write gate");
         helper.assertTrue(ServerInputDispatcher.geometryPoint(player, point), "captured point was not applied");
         helper.assertTrue(ServerInputDispatcher.geometryUndo(player, undo), "captured undo was not applied");
         helper.assertTrue(GeometrySystem.of(player).session(player).orElseThrow().points().size() == 2,
            "captured undo did not remove the captured point");
         helper.assertFalse(ServerInputDispatcher.geometryUndo(player, undo), "replayed undo was accepted");
         GeometrySystem.of(player).cancel(player);
      });
   }
}
