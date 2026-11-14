package io.github.fastformer.fastplace;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.c2s.operation.OperationExtendPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputActions;
import io.github.fastformer.server.session.QuickShapeSystem;
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
public final class OperationExtendIdentityGameTests {
   private static final long HISTORY_TIMEOUT_MILLIS = 10_000L;

   @GameTest(template = "fastformergametests.empty", batch = "operation_extend_identity", timeoutTicks = 600)
   public static void transformReplacementRejectsOldFinishAndKeepsNewBaseline(GameTestHelper helper) {
      ServerPlayer player = preparedOperation(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         var session = OperationSystem.of(player).session(player).orElseThrow();
         session.confirmSelection();
         long revision = PreviewSync.operationRevision(player);
         var scope = PreviewSync.callbackScope(player);
         int move = io.github.fastformer.common.geometry.gizmo.AxisGizmo.Operation.MOVE.ordinal();
         ServerInputActions.operationTransform(player, new io.github.fastformer.protocol.c2s.operation.OperationTransformPayload(100, 100, revision, scope, move, 0, 1, 2, false));
         helper.assertTrue(session.translation().getX() == 2 && session.transformActive(), "first transform did not apply");
         long next = PreviewSync.operationRevision(player);
         ServerInputActions.operationTransform(player, new io.github.fastformer.protocol.c2s.operation.OperationTransformPayload(101, 101, next, scope, move, 0, 1, 3, false));
         helper.assertTrue(session.translation().getX() == 5, "replacement reused the old transform baseline");
         ServerInputActions.operationTransform(player, new io.github.fastformer.protocol.c2s.operation.OperationTransformPayload(102, 100, revision, scope, move, 0, 1, 20, true));
         helper.assertTrue(session.translation().getX() == 5 && session.transformActive(), "old finish changed the replacement");
         ServerInputActions.operationTransform(player, new io.github.fastformer.protocol.c2s.operation.OperationTransformPayload(103, 101, next, scope, move, 0, 1, 4, true));
         helper.assertTrue(session.translation().getX() == 6 && !session.transformActive(), "owned finish did not apply");
         OperationSystem.of(player).cancel(player);
         BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
         OperationSystem.of(player).startFirst(player, origin);
         OperationSystem.of(player).startSecond(player, origin.offset(3, 0, 0));
         var replacement = OperationSystem.of(player).session(player).orElseThrow();
         ServerInputActions.operationTransform(player, new io.github.fastformer.protocol.c2s.operation.OperationTransformPayload(104, 101, next, scope, move, 0, 1, 10, true));
         helper.assertTrue(replacement.translation().equals(BlockPos.ZERO), "old session finish changed the new draft");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_extend_identity", timeoutTicks = 600)
   public static void closePathRejectsOldDraftRevision(GameTestHelper helper) {
      ServerPlayer player = preparedOperation(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         long old = PreviewSync.operationRevision(player);
         var scope = PreviewSync.callbackScope(player);
         OperationSystem.of(player).cancel(player);
         BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
         OperationSystem.of(player).startFirst(player, origin);
         OperationSystem.of(player).startSecond(player, origin.offset(3, 0, 0));
         var session = OperationSystem.of(player).session(player).orElseThrow();
         session.setSelectionMode(OperationSelectionMode.PRISM);
         OperationSystem.of(player).addSelectionPoint(player, origin);
         OperationSystem.of(player).addSelectionPoint(player, origin.offset(3, 0, 0));
         OperationSystem.of(player).addSelectionPoint(player, origin.offset(0, 0, 3));
         ServerInputActions.closeActivePath(player, new io.github.fastformer.protocol.c2s.geometry.ClosePathPayload(200, old, scope,
            io.github.fastformer.protocol.c2s.geometry.ClosePathPayload.Kind.OPERATION));
         helper.assertFalse(session.prismBaseClosed(), "old close changed the replacement draft");
         ServerInputActions.closeActivePath(player, new io.github.fastformer.protocol.c2s.geometry.ClosePathPayload(201, PreviewSync.operationRevision(player), scope,
            io.github.fastformer.protocol.c2s.geometry.ClosePathPayload.Kind.OPERATION));
         helper.assertTrue(session.prismBaseClosed(), "valid close did not close the prism");
         OperationSystem.of(player).cancel(player);
      });
   }

   private OperationExtendIdentityGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "operation_extend_identity", timeoutTicks = 600)
   public static void continuousDragKeepsCapturedRevisionAfterPreviewUpdate(GameTestHelper helper) {
      ServerPlayer player = preparedOperation(helper);
      long capturedRevision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);

      helper.succeedWhen(() -> {
         ready(helper, player);
         var session = OperationSystem.of(player).session(player).orElseThrow();
         int initialMax = session.cuboidMaxPoint().getX();
         ServerInputActions.extend(player, new OperationExtendPayload(1L, capturedRevision, scope, 0, true, 1, false));
         long updatedRevision = PreviewSync.operationRevision(player);
         helper.assertTrue(updatedRevision != capturedRevision, "the first drag packet did not publish a new preview");
         ServerInputActions.extend(player, new OperationExtendPayload(1L, capturedRevision, scope, 0, true, 1, true));
         helper.assertTrue(session.cuboidMaxPoint().getX() == initialMax + 2, "the finish delta was not applied");
         helper.assertFalse(session.extend(), "the finish packet did not close the edit");
         long nextRevision = PreviewSync.operationRevision(player);
         ServerInputActions.extend(player, new OperationExtendPayload(2L, nextRevision, scope, 0, true, 1, false));
         ServerInputActions.extend(player, new OperationExtendPayload(1L, capturedRevision, scope, 0, true, 10, true));
         helper.assertTrue(session.cuboidMaxPoint().getX() == initialMax + 3 && session.extend(), "a stale finish changed the new gesture");
         ServerInputActions.extend(player, new OperationExtendPayload(2L, nextRevision, scope, 0, true, 1, true));
         helper.assertTrue(session.cuboidMaxPoint().getX() == initialMax + 4, "the next drag did not finish");
         helper.assertFalse(session.commitEdit(), "the finished edit was still pending");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_extend_identity", timeoutTicks = 600)
   public static void changedIdentityCannotContinueDrag(GameTestHelper helper) {
      ServerPlayer player = preparedOperation(helper);
      long revision = PreviewSync.operationRevision(player);
      OperationCallbackScope scope = PreviewSync.callbackScope(player);

      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.extend(player, new OperationExtendPayload(2L, revision, scope, 0, true, 1, false));
         int afterFirst = OperationSystem.of(player).session(player).orElseThrow().cuboidMaxPoint().getX();
         ServerInputActions.extend(player, new OperationExtendPayload(3L, revision, scope, 0, true, 1, true));
         int afterRejected = OperationSystem.of(player).session(player).orElseThrow().cuboidMaxPoint().getX();
         helper.assertTrue(afterRejected == afterFirst, "a different request id continued the active drag");
         ServerInputActions.extend(player, new OperationExtendPayload(2L, revision, scope, 0, true, 1, true));
         helper.assertTrue(OperationSystem.of(player).session(player).orElseThrow().cuboidMaxPoint().getX() == afterFirst + 1, "the owned finish was rejected");
         OperationSystem.of(player).cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_extend_identity", timeoutTicks = 600)
   public static void cancelledSessionDoesNotBlockReplacementDrag(GameTestHelper helper) {
      ServerPlayer player = preparedOperation(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         long revision = PreviewSync.operationRevision(player);
         var scope = PreviewSync.callbackScope(player);
         ServerInputActions.extend(player, new OperationExtendPayload(10, revision, scope, 0, true, 1, false));
         SessionSystem.of(player).quit(player);
         BlockPos origin = helper.absolutePos(new BlockPos(1,5,1));
         OperationSystem.of(player).startFirst(player, origin);
         OperationSystem.of(player).startSecond(player, origin.offset(3,0,0));
         var session = OperationSystem.of(player).session(player).orElseThrow();
         int before = session.cuboidMaxPoint().getX();
         long next = PreviewSync.operationRevision(player);
         ServerInputActions.extend(player, new OperationExtendPayload(11, next, scope, 0, true, 2, false));
         ServerInputActions.extend(player, new OperationExtendPayload(10, revision, scope, 0, true, 20, true));
         helper.assertTrue(session.cuboidMaxPoint().getX() == before + 2 && session.extend(), "replacement drag was blocked or ended by stale input");
         ServerInputActions.extend(player, new OperationExtendPayload(11, next, scope, 0, true, 1, true));
         helper.assertTrue(session.cuboidMaxPoint().getX() == before + 3 && !session.extend(), "replacement finish was not applied");
         OperationSystem.of(player).cancel(player);
      });
   }

   private static ServerPlayer preparedOperation(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
      OperationSystem.of(player).startFirst(player, origin);
      OperationSystem.of(player).startSecond(player, origin.offset(3, 0, 0));
      OperationSystem.of(player).session(player).orElseThrow().setSelectionMode(OperationSelectionMode.CUBOID);
      return player;
   }

   private static void ready(GameTestHelper helper, ServerPlayer player) {
      helper.assertTrue(ServerInputActions.canOperate(player), "waiting for the write gate");
      helper.assertFalse(ServerInputActions.interactionBlocked(player), "waiting for the operation input gate");
   }
}
