package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorldOperationCommitTest {
   @Test
   void batchCompressionWaitsForJournalCorrection() {
      CompletableFuture<Boolean> journal = new CompletableFuture<>();
      AtomicBoolean batchStarted = new AtomicBoolean();
      CompletableFuture<Optional<WorldChangeBatch>> batch = WorldOperationCommit.prepareBatchAfterJournal(
         journal,
         () -> {
            batchStarted.set(true);
            return Optional.empty();
         },
         () -> false
      );

      assertFalse(batchStarted.get());
      assertFalse(batch.isDone());

      journal.complete(true);
      batch.join();

      assertTrue(batchStarted.get());
   }

   @Test
   void failedJournalDoesNotStartBatchCompression() {
      AtomicBoolean batchStarted = new AtomicBoolean();
      CompletableFuture<Optional<WorldChangeBatch>> batch = WorldOperationCommit.prepareBatchAfterJournal(
         CompletableFuture.completedFuture(false),
         () -> {
            batchStarted.set(true);
            return Optional.empty();
         },
         () -> false
      );

      assertTrue(batch.join().isEmpty());
      assertFalse(batchStarted.get());
   }

   @Test
   void cancellationWithoutJournalStillSuppressesPublication() {
      WorldOperationCommit commit = WorldOperationCommit.begin(
         net.minecraft.world.level.Level.OVERWORLD,
         java.util.List.of(),
         java.util.Map.of(),
         null
      );

      commit.cancel();

      assertTrue(commit.batch().isEmpty());
   }

}
