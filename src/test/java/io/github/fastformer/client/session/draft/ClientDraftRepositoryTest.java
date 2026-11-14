package io.github.fastformer.client.session.draft;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.draft.ClientDraftRepository.ReceiptVerdict;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDraftRepositoryTest {
   @TempDir Path directory;

   @Test
   void lateSaveConfirmationCannotMakeAReplacementDraftEvictable() {
      var repository = new ClientDraftRepository(() -> directory.resolve("draft.nbt.gz"));
      var saved = draft(null);
      var replacement = draft(null);
      assertEquals(saved, replacement);
      assertNotSame(saved, replacement);
      repository.confirmDurable(saved);
      assertTrue(repository.canEvict(saved));
      assertFalse(repository.canEvict(replacement));
      repository.restoreRejected();
      assertFalse(repository.canEvict(saved));
   }

   @Test
   void liveWorkSkipsDiskAndReceiptChecks() throws Exception {
      Path file = directory.resolve("draft.nbt.gz");
      Files.writeString(file, "unreadable");
      var repository = new ClientDraftRepository(() -> file);
      var read = repository.read(null, true, id -> fail("Live work must not consult receipts"));
      assertEquals(ClientDraftLoadState.NOT_LOADED, read.state());
      assertNull(read.failure());
      assertNull(read.draft());
      assertTrue(Files.exists(file));
   }

   @Test
   void unknownReceiptWaitsWithoutRepeatingAndOnlyReceiptRecoveryRearmsIt() throws Exception {
      Path file = directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(file, ClientOperationDraftCodec.encode(draft(UUID.randomUUID())));
      var repository = new ClientDraftRepository(() -> file);
      var calls = new AtomicInteger();
      var first = repository.read(null, false, id -> { calls.incrementAndGet(); return ReceiptVerdict.UNKNOWN; });
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, first.state());
      assertEquals(ClientDraftRepository.ReadNotice.AWAITING_RECEIPT, first.notice());
      var waiting = repository.read(null, false, id -> fail("Cached wait must not read again"));
      assertEquals(ClientDraftRepository.ReadNotice.NONE, waiting.notice());
      assertEquals(1, calls.get());
      assertFalse(repository.canEvict(null));

      repository.rearmAfterReceipt();
      var applied = repository.read(null, false, id -> ReceiptVerdict.APPLIED);
      assertEquals(ClientDraftLoadState.READY, applied.state());
      assertNull(applied.draft());
      assertTrue(Files.exists(file));
      repository.rearmAfterReceipt();
      assertEquals(ClientDraftLoadState.READY, repository.readState());
   }

   @Test
   void oldFormatIsRejectedWithoutTouchingTheFile() throws Exception {
      Path file = directory.resolve("old-draft.nbt.gz");
      var root = ClientOperationDraftCodec.encode(draft(null));
      root.putInt("Version", ClientOperationDraft.CURRENT_VERSION - 1);
      OperationClipboardStore.save(file, root);
      byte[] original = Files.readAllBytes(file);
      var repository = new ClientDraftRepository(() -> file);
      var read = repository.read(null, false, id -> fail("Unsupported format must not consult receipts"));
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, read.state());
      assertInstanceOf(ClientOperationDraftCodec.VersionMismatchException.class, read.failure());
      assertFalse(repository.canEvict(null));
      assertArrayEquals(original, Files.readAllBytes(file));
   }

   private static ClientOperationDraft draft(UUID submission) {
      return new ClientOperationDraft(ClientOperationDraft.CURRENT_VERSION, null,
         new ClientOperationWorkspace.DraftState(List.of(), Set.of(), 0),
         new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
            List.of(new BlockPos(1, 2, 3)), 0, null, null),
         OperationSubmissionOrigin.CLIENT_SELECTION, submission);
   }
}
