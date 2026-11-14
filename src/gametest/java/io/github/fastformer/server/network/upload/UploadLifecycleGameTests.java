package io.github.fastformer.server.network.upload;

import io.github.fastformer.FastFormer;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceApplyPayload;
import io.github.fastformer.protocol.c2s.workspace.ShapePlacementPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.testkit.ServerPlayers;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.common.workspace.submission.WorkspaceFailure;
import io.github.fastformer.common.workspace.submission.WorkspaceCompletion;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UploadLifecycleGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void tickingDropsOrphanScopesAndKeepsIncompleteAndDecodingRequests(GameTestHelper helper) throws Exception {
      ServerPlayer player = readyPlayer(helper);
      var work = new ArrayDeque<Runnable>();
      var uploads = new WorkspaceUploads(player.getServer(), work::addLast, SessionSystem.of(player.getServer()).submissions());
      UUID orphan = UUID.randomUUID(), partial = UUID.randomUUID();
      try {
         uploads.receipts().remember(player, orphan, 0);
         uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(partial, 0, 2, new byte[] {1}));
         var live = uploads.receipts().requestScope(player.getUUID(), partial);
         helper.assertTrue(live != null, "incomplete request scope missing");
         uploads.tick();
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), orphan) == null, "orphan scope survived tick");
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), partial) == live, "tick removed incomplete scope");
         uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(partial, 1, 2, new byte[] {2}));
         helper.assertTrue(work.size() == 1, "decode was not admitted");
         uploads.tick();
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), partial) == live, "tick removed decoding scope");
      } finally {
         uploads.close();
         work.forEach(Runnable::run);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void cancellingAQueuedDecodeAllowsSameTransferAndRetiresTheOldWork(GameTestHelper helper) throws Exception {
      ServerPlayer player = readyPlayer(helper);
      var work = new ArrayDeque<Runnable>();
      var uploads = new WorkspaceUploads(player.getServer(), work::addLast, SessionSystem.of(player.getServer()).submissions());
      UUID transfer = UUID.randomUUID();
      try {
         var chunk = new OperationWorkspaceApplyPayload(transfer, 0, 1, new byte[] {1});
         uploads.receive(player, UploadKind.WORKSPACE, chunk);
         helper.assertTrue(work.size() == 1, "first decode was not admitted");
         uploads.forget(player);
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), transfer) == null, "forget retained scope");
         uploads.receive(player, UploadKind.WORKSPACE, chunk);
         helper.assertTrue(work.size() == 2, "cancelled owner still blocked decoding");
         var replacement = uploads.receipts().requestScope(player.getUUID(), transfer);
         work.removeFirst().run();
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), transfer) == replacement,
            "old work removed the replacement scope");
         uploads.close();
         work.removeFirst().run();
         assertNoWork(helper, player, transfer);
      } finally {
         uploads.close();
         SessionSystem.of(player.getServer()).submissions().clearOwner(player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void closingOneUploadInstancePreservesAnotherInstancesChunksAndScope(GameTestHelper helper) throws Exception {
      ServerPlayer player = readyPlayer(helper);
      var firstWork = new ArrayDeque<Runnable>();
      var secondWork = new ArrayDeque<Runnable>();
      var first = new WorkspaceUploads(player.getServer(), firstWork::addLast, SessionSystem.of(player.getServer()).submissions());
      var second = new WorkspaceUploads(player.getServer(), secondWork::addLast, SessionSystem.of(player.getServer()).submissions());
      UUID transfer = UUID.randomUUID();
      try {
         var chunk = new ShapePlacementPayload(transfer, 0, 2, new byte[] {1});
         first.receive(player, UploadKind.SHAPE, chunk);
         second.receive(player, UploadKind.SHAPE, chunk);
         var scope = second.receipts().requestScope(player.getUUID(), transfer);
         helper.assertTrue(scope != null, "second instance did not remember scope");
         first.close();
         first.close();
         first.receive(player, UploadKind.SHAPE, new ShapePlacementPayload(transfer, 1, 2, new byte[] {2}));
         helper.assertTrue(firstWork.isEmpty(), "closed instance accepted work");
         helper.assertTrue(second.receipts().requestScope(player.getUUID(), transfer) == scope,
            "closing another instance removed scope");
         second.receive(player, UploadKind.SHAPE, new ShapePlacementPayload(transfer, 1, 2, new byte[] {2}));
         helper.assertTrue(secondWork.size() == 1, "closing another instance removed chunks");
      } finally {
         first.close();
         second.close();
         secondWork.forEach(Runnable::run);
      }
      assertNoWork(helper, player, transfer);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void staleChunkFailurePreservesTheCurrentUpload(GameTestHelper helper) throws Exception {
      ServerPlayer player = readyPlayer(helper);
      var work = new ArrayDeque<Runnable>();
      var uploads = new WorkspaceUploads(player.getServer(), work::addLast, SessionSystem.of(player.getServer()).submissions());
      UUID current = UUID.randomUUID(), stale = UUID.randomUUID();
      try {
         uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(current, 0, 2, new byte[] {1}));
         var scope = uploads.receipts().requestScope(player.getUUID(), current);
         helper.assertTrue(scope != null, "current scope missing");
         uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(stale, 1, 2, new byte[] {2}));
         helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), current) == scope,
            "stale failure discarded the current scope");
         helper.assertTrue(OperationSystem.of(player).recordedOutcome(player, stale) == OperationSubmissionOutcome.FAILED_RETRYABLE,
            "stale failure was not recorded");
         uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(current, 1, 2, new byte[] {2}));
         helper.assertTrue(work.size() == 1, "stale failure discarded the current chunks");
      } finally {
         uploads.close();
         work.forEach(Runnable::run);
         SessionSystem.of(player.getServer()).submissions().clearOwner(player.getUUID());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void requestScopeChangeRejectsADecodeFrozenByThePreviousConnection(GameTestHelper helper) throws Exception {
      ServerPlayer player = readyPlayer(helper);
      var work = new ArrayDeque<Runnable>();
      var uploads = new WorkspaceUploads(player.getServer(), work::addLast, SessionSystem.of(player.getServer()).submissions());
      UUID transfer = UUID.randomUUID();
      uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(transfer, 0, 2, new byte[] {1}));
      PreviewSync.beginClientSession(player);
      // A duplicate chunk zero must not rebind retained chunks to the replacement connection.
      uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(transfer, 0, 2, new byte[] {1}));
      uploads.receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(transfer, 1, 2, new byte[] {2}));
      helper.assertTrue(work.size() == 1, "decode was not admitted");
      work.removeFirst().run();
      helper.runAfterDelay(1, () -> {
         try {
            helper.assertTrue(uploads.receipts().requestScope(player.getUUID(), transfer) == null, "retired scope survived completion");
            assertNoWork(helper, player, transfer);
            helper.succeed();
         } finally { uploads.close(); }
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "upload_lifecycle", timeoutTicks = 600)
   public static void offlineFinishRecordsBeforeDroppingScopeAndClosedReceiptsCannotRecreateLedger(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      var uploads = new WorkspaceUploads(player.getServer(), Runnable::run, SessionSystem.of(player.getServer()).submissions());
      var receipts = uploads.receipts();
      UUID transfer = UUID.randomUUID();
      try {
         receipts.remember(player, transfer, 0);
         receipts.finish(new WorkspaceCompletion(player.getUUID(), player.level().dimension().location(), transfer,
            true, false, false, List.of(), List.of(), WorkspaceFailure.UNKNOWN), null);
         helper.assertTrue(receipts.requestScope(player.getUUID(), transfer) == null, "offline result retained scope");
         helper.assertTrue(OperationSystem.of(player).recordedOutcome(player, transfer) == OperationSubmissionOutcome.APPLIED,
            "offline result was not recorded");
         receipts.finish(new WorkspaceCompletion(player.getUUID(), player.level().dimension().location(), transfer,
            false, true, false, List.of(), List.of(), WorkspaceFailure.UNKNOWN), null);
         helper.assertTrue(OperationSystem.of(player).recordedOutcome(player, transfer) == OperationSubmissionOutcome.APPLIED,
            "weaker result replaced applied work");
         uploads.close();
         SessionSystem.of(player.getServer()).submissions().clearOwner(player.getUUID());
         receipts.finish(new WorkspaceCompletion(player.getUUID(), player.level().dimension().location(), transfer,
            true, false, false, List.of(), List.of(), WorkspaceFailure.UNKNOWN), null);
         assertNoWork(helper, player, transfer);
      } finally {
         uploads.close();
         SessionSystem.of(player.getServer()).submissions().clearOwner(player.getUUID());
      }
      helper.succeed();
   }

   private static ServerPlayer readyPlayer(GameTestHelper helper) throws Exception {
      ServerPlayer player = ServerPlayers.connect(helper, "upload-test");
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      return player;
   }

   private static void assertNoWork(GameTestHelper helper, ServerPlayer player, UUID transfer) {
      helper.assertFalse(SessionSystem.of(player).operationTasks().active(player), "retired upload started work");
      helper.assertTrue(OperationSystem.of(player).recordedOutcome(player, transfer) == OperationSubmissionOutcome.UNKNOWN,
         "retired upload recorded an outcome");
   }
}
