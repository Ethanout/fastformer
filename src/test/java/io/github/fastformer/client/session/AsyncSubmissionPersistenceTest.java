package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.workspace.model.*;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AsyncSubmissionPersistenceTest {
   @TempDir Path directory;

   @BeforeAll static void bootstrap() {
      net.minecraft.SharedConstants.tryDetectVersion();
   }

   @Test void sendContinuationRunsOnlyAfterBothDurableFilesExist() throws Exception {
      exercise(false);
   }

   @Test void cancelledOwnershipSuppressesLateSendAndKeepsDraftRetryable() throws Exception {
      exercise(true);
   }

   @Test void fullQueueRejectsSubmissionWithoutInvalidatingTheAcceptedDraft() throws Exception {
      var manager = ClientSessionManager.instance();
      var published = new AtomicBoolean();
      OperationWorkspacePlan plan = null;
      ClientSessionManager.SessionKey key = null;
      var keys = new ArrayList<ClientSessionManager.SessionKey>();
      UUID accepted = null;
      for (int index = 0; index < 3; index++) {
         UUID player = UUID.randomUUID();
         accepted = UUID.randomUUID();
         String connection = "saturation-" + player;
         key = new ClientSessionManager.SessionKey(connection, "minecraft:overworld", player);
         keys.add(key);
         var session = manager.activateScope(player, connection, "minecraft:overworld");
         manager.installDraftFile(key, directory.resolve("draft-" + index));
         manager.installReceiptFile(key, directory.resolve("receipts-" + index));
         Map<BlockPos, ClientBlockSnapshot> blocks = Map.of();
         var part = new ClientSelectionPart(1, ClientSelectionPart.Source.CLIPBOARD, null, blocks, WorkspaceTransform.IDENTITY, false);
         assertTrue(session.operationWorkspace().addParts(List.of(part)));
         plan = new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(1, part.source(), blocks, part.transform(), false)));
         assertTrue(manager.persistSubmittedDraft(null, OperationSubmissionOrigin.LOCAL_ONLY, accepted, plan, () -> true, submission -> {
            assertNotNull(submission);
            published.set(true);
         }));
      }
      UUID rejected = UUID.randomUUID();
      assertFalse(manager.persistSubmittedDraft(null, OperationSubmissionOrigin.LOCAL_ONLY, rejected, plan, () -> true,
         submission -> fail("Rejected submission completed")));
      assertTrue(manager.receiptStore(key).find(rejected).isEmpty());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!published.get() && System.nanoTime() < deadline) { manager.drainStorageResults(); Thread.yield(); }
      assertTrue(published.get());
      assertEquals(OperationSubmissionOutcome.IN_FLIGHT, manager.receiptStore(key).find(accepted).orElseThrow().outcome());
      while (System.nanoTime() < deadline) {
         manager.drainStorageResults();
         if (keys.stream().noneMatch(savedKey -> manager.receiptStore(savedKey).dirty())) break;
         Thread.yield();
      }
      assertTrue(keys.stream().noneMatch(savedKey -> manager.receiptStore(savedKey).dirty()));
   }

   private void exercise(boolean cancelled) throws Exception {
      var manager = ClientSessionManager.instance();
      UUID player = UUID.randomUUID(), transfer = UUID.randomUUID();
      String connection = "async-test-" + player;
      var key = new ClientSessionManager.SessionKey(connection, "minecraft:overworld", player);
      var session = manager.activateScope(player, connection, "minecraft:overworld");
      Path draft = directory.resolve("draft.nbt.gz"), receipt = directory.resolve("receipt.nbt.gz");
      manager.installDraftFile(key, draft);
      manager.installReceiptFile(key, receipt);
      Map<BlockPos, ClientBlockSnapshot> blocks = Map.of();
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.CLIPBOARD, null, blocks, WorkspaceTransform.IDENTITY, false);
      assertTrue(session.operationWorkspace().addParts(List.of(part)));
      var plan = new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(1, part.source(), blocks, part.transform(), false)));
      var published = new AtomicBoolean();
      assertTrue(manager.persistSubmittedDraft(null, OperationSubmissionOrigin.LOCAL_ONLY, transfer, plan, () -> !cancelled, submission -> {
         assertNotNull(submission);
         assertTrue(Files.exists(draft));
         assertTrue(Files.exists(receipt));
         published.set(true);
      }));
      assertFalse(published.get());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
         manager.drainStorageResults();
         var state = manager.receiptStore(key).find(transfer).orElseThrow().outcome();
         if ((!cancelled && published.get()) || (cancelled && state == OperationSubmissionOutcome.FAILED_RETRYABLE && !manager.receiptStore(key).dirty())) break;
         Thread.yield();
      }
      assertEquals(!cancelled, published.get());
      assertTrue(OperationClipboardStore.loadStrict(draft).isPresent());
      if (cancelled) {
         assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE, manager.receiptStore(key).find(transfer).orElseThrow().outcome());
         assertFalse(manager.receiptStore(key).dirty());
      }
   }
}
