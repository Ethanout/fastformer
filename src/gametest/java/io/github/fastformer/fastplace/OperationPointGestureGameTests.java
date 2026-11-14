package io.github.fastformer.fastplace;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.c2s.operation.OperationPointClickPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputActions;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OperationPointGestureGameTests {
   private static final long HISTORY_INITIALIZATION_TIMEOUT_MILLIS = 10_000L;

   private OperationPointGestureGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_point_gesture", timeoutTicks = 600)
   public static void finishedDragReplayDoesNotAuthorizeAnotherRemoval(GameTestHelper helper) {
      ServerPlayer player = preparedPrism(helper, new BlockPos(1, 5, 1));
      long revision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);
      OperationPointDragPayload start = drag(1L, revision, scope, 1, point(player, 1), false);
      OperationPointDragPayload finish = finish(1L, revision, scope, 1, point(player, 1));
      OperationPointClickPayload remove = remove(1L, revision, scope, 1);

      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.operationPointDrag(player, start);
         ServerInputActions.operationPointDrag(player, finish);
         ServerInputActions.operationPointClick(player, remove);
         helper.assertTrue(points(player) == 2, "the completed drag did not remove its point");
         ServerInputActions.operationPointDrag(player, finish);
         ServerInputActions.operationPointClick(player, remove);
         helper.assertTrue(points(player) == 2, "a replayed finish authorized another removal");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_point_gesture", timeoutTicks = 600)
   public static void changedGestureFieldsDoNotConsumeTheCompletedDrag(GameTestHelper helper) {
      ServerPlayer player = preparedPrism(helper, new BlockPos(1, 5, 1));
      long revision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);
      OperationPointDragPayload start = drag(1L, revision, scope, 1, point(player, 1), false);
      OperationPointDragPayload changedPoint = drag(1L, revision, scope, 2, point(player, 2), false);
      OperationPointDragPayload changedRevision = drag(1L, revision + 1L, scope, 1, point(player, 1), false);
      OperationPointDragPayload finish = finish(1L, revision, scope, 1, point(player, 1));
      OperationPointClickPayload wrongPoint = remove(1L, revision, scope, 2);
      OperationPointClickPayload wrongRevision = remove(1L, revision + 1L, scope, 1);
      OperationPointClickPayload valid = remove(1L, revision, scope, 1);

      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.operationPointDrag(player, start);
         ServerInputActions.operationPointDrag(player, changedPoint);
         ServerInputActions.operationPointDrag(player, changedRevision);
         ServerInputActions.operationPointDrag(player, finish);
         ServerInputActions.operationPointClick(player, wrongPoint);
         ServerInputActions.operationPointClick(player, wrongRevision);
         helper.assertTrue(points(player) == 3, "a changed gesture removed a point");
         ServerInputActions.operationPointClick(player, valid);
         helper.assertTrue(points(player) == 2, "a rejected gesture consumed the valid completion");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_point_gesture", timeoutTicks = 600)
   public static void replacedOrChangedDraftRejectsAnOldCompletedClick(GameTestHelper helper) {
      ServerPlayer player = preparedPrism(helper, new BlockPos(1, 5, 1));
      long oldRevision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);
      OperationPointDragPayload oldFinish = finish(1L, oldRevision, scope, 1, point(player, 1));
      OperationPointClickPayload oldRemove = remove(1L, oldRevision, scope, 1);

      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.operationPointDrag(player, oldFinish);
         OperationSystem.of(player).cancel(player);
         preparePrism(player, helper.absolutePos(new BlockPos(8, 5, 1)));
         ServerInputActions.operationPointClick(player, oldRemove);
         helper.assertTrue(points(player) == 3, "a replaced prism accepted the old completion");

         long revision = PreviewSync.operationRevision(player);
         OperationPointDragPayload finish = finish(2L, revision, scope, 1, point(player, 1));
         OperationPointClickPayload remove = remove(2L, revision, scope, 1);
         ServerInputActions.operationPointDrag(player, finish);
         OperationSystem.of(player).addSelectionPoint(player, helper.absolutePos(new BlockPos(12, 5, 5)));
         ServerInputActions.operationPointClick(player, remove);
         helper.assertTrue(points(player) == 4, "a later operation accepted an old completion");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_point_gesture", timeoutTicks = 600)
   public static void nonzeroControlPointCanCloseAPrismBase(GameTestHelper helper) {
      ServerPlayer player = preparedPrism(helper, new BlockPos(1, 5, 1));
      long revision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);
      OperationPointDragPayload finish = finish(1L, revision, scope, 2, point(player, 2));
      OperationPointClickPayload close = new OperationPointClickPayload(
         1L, revision, scope, OperationPointClickPayload.Action.CLOSE, 2
      );

      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.operationPointDrag(player, finish);
         ServerInputActions.operationPointClick(player, close);
         helper.assertTrue(
            OperationSystem.of(player).session(player).orElseThrow().prismBaseClosed(),
            "a nonzero control point did not close the prism base"
         );
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_point_gesture", timeoutTicks = 600)
   public static void dragRejectsWorldHeightAndPacketJumpButAllowsEditorDistance(GameTestHelper helper) {
      var player = preparedPrism(helper, new BlockPos(1, 5, 1));
      var session = OperationSystem.of(player).session(player).orElseThrow();
      var original = point(player, 1);
      player.setPos(original.getX() + 50, original.getY(), original.getZ());
      helper.assertTrue(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(original)) > 25,
         "fixture must exceed vanilla reach");
      OperationSystem.of(player).dragPoint(player, 1, original.offset(1, 0, 0), OperationPointDragConstraint.FREE, true);
      helper.assertTrue(point(player, 1).equals(original.offset(1, 0, 0)), "valid far editor drag was rejected");
      var before = session.points();
      OperationSystem.of(player).dragPoint(player, 1, original.offset(130, 0, 0), OperationPointDragConstraint.FREE, true);
      helper.assertTrue(session.points().equals(before), "oversized packet jump was accepted");
      session.setSecond(new BlockPos(original.getX(), helper.getLevel().getMaxBuildHeight() - 1, original.getZ()));
      before = session.points();
      OperationSystem.of(player).dragPoint(player, 1, before.get(1).above(), OperationPointDragConstraint.FREE, true);
      helper.assertTrue(session.points().equals(before), "drag escaped dimension height");
      OperationSystem.of(player).cancel(player);
      helper.succeed();
   }

   private static ServerPlayer preparedPrism(GameTestHelper helper, BlockPos origin) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_INITIALIZATION_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      preparePrism(player, helper.absolutePos(origin));
      return player;
   }

   private static void preparePrism(ServerPlayer player, BlockPos origin) {
      OperationSystem.of(player).startFirst(player, origin);
      OperationSystem.of(player).session(player).orElseThrow().setSelectionMode(OperationSelectionMode.PRISM);
      OperationSystem.of(player).startFirst(player, origin);
      OperationSystem.of(player).startSecond(player, origin.offset(3, 0, 0));
      OperationSystem.of(player).addSelectionPoint(player, origin.offset(0, 0, 3));
   }

   private static OperationPointDragPayload finish(long gestureId, long revision, OperationCallbackScope scope, int pointIndex, BlockPos target) {
      return drag(gestureId, revision, scope, pointIndex, target, true);
   }

   private static OperationPointDragPayload drag(
      long gestureId, long revision, OperationCallbackScope scope, int pointIndex, BlockPos target, boolean finish
   ) {
      return new OperationPointDragPayload(gestureId, revision, scope, pointIndex, target, OperationPointDragConstraint.FREE, finish);
   }

   private static OperationPointClickPayload remove(long gestureId, long revision, OperationCallbackScope scope, int pointIndex) {
      return new OperationPointClickPayload(gestureId, revision, scope, OperationPointClickPayload.Action.REMOVE, pointIndex);
   }

   private static BlockPos point(ServerPlayer player, int pointIndex) {
      return OperationSystem.of(player).session(player).orElseThrow().points().get(pointIndex);
   }

   private static int points(ServerPlayer player) {
      return OperationSystem.of(player).session(player).orElseThrow().points().size();
   }

   private static void ready(GameTestHelper helper, ServerPlayer player) {
      helper.assertTrue(ServerInputActions.canOperate(player), "waiting for the write gate");
      helper.assertFalse(ServerInputActions.interactionBlocked(player), "waiting for the operation input gate");
   }
}
