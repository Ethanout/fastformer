package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorldOperationCommitTest {
   @Test
   void unchangedFinalStateCompletesWithoutAnUndoEntry() {
      BlockPos position = new BlockPos(2, 3, 4);
      ReversibleBlockSnapshot state = snapshot(position);
      WorldOperationCommit commit = WorldOperationCommit.begin(
         net.minecraft.world.level.Level.OVERWORLD, List.of(state), Map.of(position, state), null
      );
      commit.completion().join();
      org.junit.jupiter.api.Assertions.assertEquals(JournalPreparation.READY, commit.poll());
      assertTrue(commit.batch().isEmpty());
   }

   @Test
   void cancelledEmptyCommitDoesNotBecomeSuccessful() {
      WorldOperationCommit commit = WorldOperationCommit.begin(
         net.minecraft.world.level.Level.OVERWORLD, List.of(), Map.of(), null
      );
      commit.completion().join();
      commit.cancel();
      org.junit.jupiter.api.Assertions.assertEquals(JournalPreparation.FAILED, commit.poll());
   }

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

   @Test
   void stopForRecoveryCancelsPendingHistoryPublication() {
      BlockPos position = new BlockPos(2, 3, 4);
      ReversibleBlockSnapshot before = snapshot(position);
      ReversibleBlockSnapshot after = snapshot(position);
      WorldOperationCommit commit = WorldOperationCommit.begin(
         net.minecraft.world.level.Level.OVERWORLD,
         List.of(before), Map.of(position, after), null
      );

      commit.stopForRecovery().join();

      assertTrue(commit.batch().isEmpty());
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position) {
      return new ReversibleBlockSnapshot(
         position, null, null, null
      );
   }

}
