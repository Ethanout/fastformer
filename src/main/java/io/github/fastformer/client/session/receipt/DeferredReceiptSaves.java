package io.github.fastformer.client.session.receipt;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.session.storage.ClientStorageQueue;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Retains unsent receipt writes until admission and durable confirmation both succeed. */
public final class DeferredReceiptSaves {
   private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
   private final ClientStorageQueue storage;
   private final LongSupplier clock;
   private final BiConsumer<Path, Exception> reportFailure;
   private final Map<Path, PendingSave> pending = new LinkedHashMap<>();

   public DeferredReceiptSaves(ClientStorageQueue storage, LongSupplier clock, BiConsumer<Path, Exception> reportFailure) {
      this.storage = Objects.requireNonNull(storage, "storage");
      this.clock = Objects.requireNonNull(clock, "clock");
      this.reportFailure = Objects.requireNonNull(reportFailure, "reportFailure");
   }

   public void request(Path path, OperationSubmissionReceiptStore store) {
      Path file = path.toAbsolutePath().normalize();
      Objects.requireNonNull(store, "store");
      if (!store.dirty()) return;
      PendingSave existing = pending.get(file);
      if (existing == null || existing.store != store) pending.put(file, new PendingSave(store));
      tick();
   }

   public void tick() {
      for (var entry : java.util.List.copyOf(pending.entrySet())) {
         Path path = entry.getKey();
         PendingSave save = entry.getValue();
         if (save.inFlight || clock.getAsLong() < save.retryAt) continue;
         if (!save.store.dirty()) { pending.remove(path, save); continue; }
         boolean admitted = storage.submitPrepared(() -> {
            var receipts = save.store.all();
            Object ticket = OperationClipboardStore.reserve(path);
            save.inFlight = true;
            return new ClientStorageQueue.Task<>(() -> {
               if (save.store.readOnly()) throw new java.io.IOException("Receipt file is read-only: " + path);
               OperationClipboardStore.save(path, OperationSubmissionReceiptCodec.encode(receipts), ticket);
               return receipts;
            }, (saved, failure) -> {
               save.inFlight = false;
               if (pending.get(path) != save) return;
               if (failure == null && OperationClipboardStore.current(path, ticket)) {
                  save.store.confirmSaved(saved);
                  if (!save.store.dirty()) pending.remove(path, save);
               } else {
                  save.retryAt = clock.getAsLong() + RETRY_NANOS;
                  if (!save.reported) {
                     save.reported = true;
                     reportFailure.accept(path, failure);
                  }
               }
            });
         });
         if (!admitted) {
            save.inFlight = false;
            return;
         }
      }
   }

   public int pendingCount() { return pending.size(); }

   private static final class PendingSave {
      final OperationSubmissionReceiptStore store;
      boolean inFlight;
      boolean reported;
      long retryAt;
      PendingSave(OperationSubmissionReceiptStore store) { this.store = store; }
   }
}
