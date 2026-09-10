package io.github.fastformer.fastplace.world;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Builds the compact undo/redo record after the recovery journal is durable.
 * This object owns history publication only; it never decides whether world
 * writes are allowed or whether a failed write needs recovery.
 */
public final class WorldHistoryPublication {
   private final CompletableFuture<Optional<WorldChangeBatch>> future;
   private final AtomicBoolean cancelled;

   private WorldHistoryPublication(
      CompletableFuture<Optional<WorldChangeBatch>> future,
      AtomicBoolean cancelled
   ) {
      this.future = future;
      this.cancelled = cancelled;
   }

   public static WorldHistoryPublication afterJournal(
      CompletableFuture<Boolean> journalReady,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      BooleanSupplier cancelled
   ) {
      AtomicBoolean cancelledState = new AtomicBoolean();
      BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
      CompletableFuture<Optional<WorldChangeBatch>> future = journalReady.thenApplyAsync(
         ready -> {
            if (!ready || cancellation.getAsBoolean() || cancelledState.get()) {
               return Optional.empty();
            }
            Optional<WorldChangeBatch> batch = WorldChangeBatch.capturePairsByPos(dimension, before, after);
            return cancellation.getAsBoolean() || cancelledState.get() ? Optional.empty() : batch;
         },
         PersistentRecoveryJournal.executor()
      );
      return new WorldHistoryPublication(future, cancelledState);
   }

   public JournalPreparation poll() {
      if (!this.future.isDone()) {
         return JournalPreparation.PENDING;
      }
      try {
         return this.future.join().isPresent() ? JournalPreparation.READY : JournalPreparation.FAILED;
      } catch (RuntimeException exception) {
         return JournalPreparation.FAILED;
      }
   }

   public Optional<WorldChangeBatch> batch() {
      return this.future.join();
   }

   public void cancel() {
      this.cancelled.set(true);
   }

   public CompletableFuture<Void> stopForRecovery() {
      this.cancelled.set(true);
      return this.future.handle((ignored, exception) -> null);
   }

   static CompletableFuture<Optional<WorldChangeBatch>> prepare(
      CompletableFuture<Boolean> journalReady,
      Supplier<Optional<WorldChangeBatch>> batchSupplier,
      BooleanSupplier cancelled
   ) {
      BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
      return journalReady.thenApplyAsync(
         ready -> {
            if (!ready || cancellation.getAsBoolean()) {
               return Optional.empty();
            }
            Optional<WorldChangeBatch> batch = batchSupplier.get();
            return cancellation.getAsBoolean() ? Optional.empty() : batch;
         },
         PersistentRecoveryJournal.executor()
      );
   }
}
