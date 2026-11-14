package io.github.fastformer.client.session.receipt;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientReceiptRepositoryTest {
   @TempDir Path directory;

   @Test
   void aFailedReadWaitsForAnExplicitRetryAndReportsOnlyTheAttempt() throws Exception {
      Path file = directory.resolve("receipts.nbt.gz");
      Files.writeString(file, "damaged");
      var repository = new ClientReceiptRepository(() -> file);
      var failed = repository.read();
      assertNotNull(failed.failure());
      assertTrue(failed.newlyRead());
      assertTrue(failed.store().readOnly());
      assertFalse(repository.canEvict());

      Files.delete(file);
      var cached = repository.read();
      assertSame(failed.store(), cached.store());
      assertFalse(cached.newlyRead());
      assertNull(cached.failure());
      assertTrue(repository.retryTransientFailure());
      var recovered = repository.read();
      assertFalse(recovered.store().readOnly());
      assertNull(recovered.failure());
      assertEquals(ReceiptReadState.READY, repository.readState());
      assertTrue(repository.canEvict());
   }

   @Test
   void anOldFormatIsNeverMigratedOrOverwritten() throws Exception {
      Path file = directory.resolve("old.nbt.gz");
      var root = new CompoundTag();
      root.putInt("Version", OperationSubmissionReceiptCodec.CURRENT_VERSION - 1);
      OperationClipboardStore.save(file, root);
      byte[] original = Files.readAllBytes(file);
      var repository = new ClientReceiptRepository(() -> file);
      var read = repository.read();
      assertInstanceOf(OperationSubmissionReceiptCodec.VersionMismatchException.class, read.failure());
      assertEquals(ReceiptReadState.VERSION_INCOMPATIBLE, repository.readState());
      assertFalse(repository.retryTransientFailure());
      assertFalse(repository.canEvict());
      read.store().record(receipt());
      assertThrows(IOException.class, repository::save);
      assertTrue(read.store().dirty());
      assertArrayEquals(original, Files.readAllBytes(file));
      assertTrue(repository.retry());
      assertNotNull(repository.read().failure());
   }

   @Test
   void aFailedSaveRetainsDirtyStateUntilTheSameRepositoryPersistsIt() throws Exception {
      Path blocker = directory.resolve("parent");
      Path file = blocker.resolve("receipts.nbt.gz");
      var repository = new ClientReceiptRepository(() -> file);
      var store = repository.read().store();
      var receipt = receipt();
      store.record(receipt);
      Files.writeString(blocker, "blocks directory creation");
      assertThrows(IOException.class, repository::save);
      assertTrue(store.dirty());
      assertFalse(repository.canEvict());
      Files.delete(blocker);

      repository.save();
      assertSame(store, repository.read().store());
      assertFalse(store.dirty());
      assertTrue(repository.canEvict());
      var loaded = new ClientReceiptRepository(() -> file).read();
      assertEquals(receipt, loaded.store().find(receipt.transferId()).orElseThrow());
   }

   private static OperationSubmissionReceipt receipt() {
      return new OperationSubmissionReceipt(UUID.randomUUID(), OperationSubmissionOrigin.LOCAL_ONLY,
         null, ResourceLocation.withDefaultNamespace("overworld"), OperationSubmissionOutcome.IN_FLIGHT, 100L);
   }
}
