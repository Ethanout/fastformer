package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import io.github.fastformer.workspace.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

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
