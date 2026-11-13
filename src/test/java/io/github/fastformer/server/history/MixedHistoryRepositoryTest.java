package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryEntryFormat;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.store.HistoryBatchStore;
import io.github.fastformer.testkit.CounterHistory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedHistoryRepositoryTest {
   @TempDir Path root;
   private final CounterHistory counter = new CounterHistory();

   @Test
   void registeredNonBlockEntrySurvivesStoragePagesCacheAndRedo() {
      var first = counter.entry(0, 1);
      var second = counter.entry(1, 2);
      UUID owner = UUID.randomUUID();
      try (var repository = repository()) {
         repository.publishNewBatch(owner, first, List.of(first.operationId()), List.of()).join();
         repository.publishNewBatch(owner, second, List.of(second.operationId(), first.operationId()), List.of()).join();
         var snapshot = repository.loadSnapshot(owner, 1, 64).join();
         var memory = new HistoryMemoryCache<HistoryEntry>();
         memory.merge(true, snapshot.undo());
         var older = repository.loadPage(owner, List.of(first.operationId()), 1, 64).join();
         memory.merge(true, older);
         assertEquals(List.of(second.operationId(), first.operationId()), memory.operationIds(true));
         assertInstanceOf(CounterHistory.CounterEntry.class, memory.commit(true));
         assertEquals(List.of(second.operationId()), memory.operationIds(false));
         assertEquals(second.operationId(), memory.commit(false).operationId());
         repository.publishSnapshot(owner, memory.snapshot(true), memory.snapshot(false)).join();
      }
      try (var reopened = repository()) {
         var restored = reopened.loadSnapshot(owner, 10, 1024).join();
         assertEquals(List.of(second.operationId(), first.operationId()), restored.undoOrder());
         assertEquals(2, assertInstanceOf(CounterHistory.CounterEntry.class, restored.undo().getFirst()).after());
      }
   }

   @Test
   void entryWhoseIdDoesNotMatchTheIndexedFileIsRejected() throws Exception {
      var entry = counter.entry(0, 1);
      UUID file = UUID.randomUUID();
      UUID owner = UUID.randomUUID();
      store().publishBatch(owner, file, format().encode(entry, 1024)).join();
      try (var repository = repository()) {
         var failure = assertThrows(CompletionException.class,
            () -> repository.loadPage(owner, List.of(file), 1, 1024).join());
         assertInstanceOf(IOException.class, failure.getCause());
      }
   }

   @Test
   void currentEntryFormatEnforcesBudgetsAndRejectsTrailingAndTruncatedBytes() throws Exception {
      var entry = counter.entry(0, 1);
      var format = format();
      byte[] payload = format.encode(entry, 1024);
      assertEquals(entry.operationId(), format.decode(RegistryAccess.EMPTY, payload, 4096).operationId());
      assertThrows(IOException.class, () -> format.encode(entry, payload.length - 1));
      assertThrows(IOException.class, () -> format.decode(RegistryAccess.EMPTY, payload, 1));
      assertThrows(IOException.class, () -> format.decode(RegistryAccess.EMPTY, Arrays.copyOf(payload, payload.length + 1), 4096));
      assertThrows(IOException.class, () -> format.decode(RegistryAccess.EMPTY, Arrays.copyOf(payload, payload.length - 1), 4096));
   }

   private HistoryEntryFormat<HistoryEntry> format() { return new HistoryEntryFormat<>(counter.codecs(), HistoryEntry.class); }
   private HistoryBatchStore store() {
      return new HistoryBatchStore(root, Runnable::run, new Envelope.Format(0x46464832, 1, 42), 1024 * 1024, 8 * 1024 * 1024, 1024 * 1024, 32, 100);
   }
   private DiskHistoryRepository<HistoryEntry> repository() {
      return new DiskHistoryRepository<>(store(), Runnable::run, RegistryAccess.EMPTY, format());
   }
}
