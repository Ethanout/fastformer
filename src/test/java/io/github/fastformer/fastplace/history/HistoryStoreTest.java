package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreTest {
   @TempDir Path directory;

   @Test
   void asynchronouslySavesAndLoadsOwnedPayload() {
      HistoryStore store = store(32, 128);
      UUID owner = UUID.randomUUID();
      byte[] payload = {1, 2, 3};

      store.save(owner, payload).join();
      payload[0] = 9;
      byte[] loaded = store.load(owner).join().orElseThrow();
      assertArrayEquals(new byte[]{1, 2, 3}, loaded);

      loaded[1] = 9;
      assertArrayEquals(new byte[]{1, 2, 3}, store.load(owner).join().orElseThrow());
      assertTrue(store.load(UUID.randomUUID()).join().isEmpty());
   }

   @Test
   void rejectsOwnerPayloadBeforeSchedulingAWrite() {
      HistoryStore store = store(2, 128);
      CompletionException failure = assertThrows(
            CompletionException.class,
            () -> store.save(UUID.randomUUID(), new byte[]{1, 2, 3}).join());
      assertInstanceOf(IOException.class, failure.getCause());
      try (var entries = Files.list(directory)) {
         assertEquals(0, entries.count());
      } catch (IOException ex) {
         fail(ex);
      }
   }

   @Test
   void globalQuotaIncludesEnvelopesAndAllowsSizeNeutralReplacement() {
      HistoryStore store = store(8, 42);
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      store.save(first, new byte[]{1}).join(); // 21 bytes including the envelope.
      store.save(second, new byte[]{2}).join();

      store.save(first, new byte[]{3}).join();
      CompletionException failure = assertThrows(
            CompletionException.class,
            () -> store.save(UUID.randomUUID(), new byte[]{4}).join());
      assertInstanceOf(UncheckedIOException.class, failure.getCause());
      assertArrayEquals(new byte[]{3}, store.load(first).join().orElseThrow());
   }

   @Test
   void rejectsAnOversizedFileBeforeReadingItIntoMemory() throws Exception {
      HistoryStore store = store(2, 128);
      UUID owner = UUID.randomUUID();
      Path target = directory.resolve(owner.toString()).resolve("history.dat");
      Files.createDirectories(target.getParent());
      Files.write(target, new byte[23]);

      CompletionException failure = assertThrows(CompletionException.class, () -> store.load(owner).join());
      assertInstanceOf(UncheckedIOException.class, failure.getCause());
   }

   private HistoryStore store(long ownerBytes, long totalBytes) {
      return new HistoryStore(directory, Runnable::run, 1, ownerBytes, totalBytes);
   }
}
