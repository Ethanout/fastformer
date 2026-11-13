package io.github.fastformer.server.history.commit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class DurableCommitTest {
   @Test
   void batchMustBeDurableBeforeSealingAndPublishingTheIndex() {
      var actions = new Actions();
      var commit = new DurableCommit(actions, 2);
      assertEquals(List.of("batch"), actions.calls);
      assertEquals(DurableCommit.Phase.PUBLISH_BATCH, commit.poll());
      actions.batch.complete(null);
      assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
      assertEquals(List.of("batch", "seal", "index"), actions.calls);
      assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
      actions.index.complete(null);
      assertEquals(DurableCommit.Phase.DONE, commit.poll());
      assertEquals(DurableCommit.Phase.DONE, commit.poll());
      assertEquals(List.of("batch", "seal", "index"), actions.calls);
   }

   @Test
   void failedBatchCannotSealOrPublishAnIndex() {
      var actions = new Actions();
      var commit = new DurableCommit(actions, 0);
      IOException failure = new IOException("batch write failed");
      actions.batch.completeExceptionally(failure);
      assertEquals(DurableCommit.Phase.FAILED, commit.poll());
      assertSame(failure, commit.failure().orElseThrow());
      assertEquals(DurableCommit.Phase.FAILED, commit.poll());
      assertEquals(List.of("batch"), actions.calls);
   }

   @Test
   void refusedSealPreventsIndexPublication() {
      var actions = new Actions();
      actions.seal = false;
      var commit = new DurableCommit(actions, 0);
      actions.batch.complete(null);
      assertEquals(DurableCommit.Phase.FAILED, commit.poll());
      assertEquals(List.of("batch", "seal"), actions.calls);
   }

   @Test
   void failedIndexRetriesWithoutRepublishingTheBatchOrResealing() {
      var actions = new Actions();
      var commit = new DurableCommit(actions, 2);
      actions.batch.complete(null);
      commit.poll();
      actions.index.completeExceptionally(new IOException("disk full"));
      assertTrue(commit.indexFailed());
      for (int tick = 0; tick < 2; tick++) {
         assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
         assertEquals(3, actions.calls.size());
      }
      actions.index = new CompletableFuture<>();
      assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
      assertEquals(List.of("batch", "seal", "index", "index"), actions.calls);
      actions.index.complete(null);
      assertEquals(DurableCommit.Phase.DONE, commit.poll());
      assertTrue(commit.failure().isEmpty());
   }

   @Test
   void synchronouslyRejectedBatchIsAStableFailure() {
      var actions = new Actions() {
         @Override public CompletableFuture<Void> publishBatch() {
            throw new RejectedExecutionException("queue closed");
         }
      };
      var commit = new DurableCommit(actions, 0);
      assertEquals(DurableCommit.Phase.FAILED, commit.poll());
      assertInstanceOf(RejectedExecutionException.class, commit.failure().orElseThrow());
      assertTrue(actions.calls.isEmpty());
   }

   @Test
   void synchronouslyRejectedIndexRetainsTheSealedCommitForRetry() {
      var actions = new Actions() {
         @Override public CompletableFuture<Void> publishIndex() {
            if (calls.size() == 2) {
               calls.add("rejected index");
               throw new RejectedExecutionException("queue full");
            }
            return super.publishIndex();
         }
      };
      var commit = new DurableCommit(actions, 0);
      actions.batch.complete(null);
      assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
      assertTrue(commit.indexFailed());
      assertEquals(DurableCommit.Phase.PUBLISH_INDEX, commit.poll());
      actions.index.complete(null);
      assertEquals(DurableCommit.Phase.DONE, commit.poll());
      assertEquals(List.of("batch", "seal", "rejected index", "index"), actions.calls);
   }

   @Test
   void thrownSealFailureIsTerminalAndCancelledBatchNeverSeals() {
      var actions = new Actions() {
         @Override public boolean sealJournal() { throw new IllegalStateException("seal failed"); }
      };
      var commit = new DurableCommit(actions, 0);
      actions.batch.complete(null);
      assertEquals(DurableCommit.Phase.FAILED, commit.poll());
      assertEquals("seal failed", commit.failure().orElseThrow().getMessage());

      var cancelled = new Actions();
      var another = new DurableCommit(cancelled, 0);
      cancelled.batch.cancel(false);
      assertEquals(DurableCommit.Phase.FAILED, another.poll());
      assertEquals(List.of("batch"), cancelled.calls);
   }

   @Test
   void invalidRetryDelayDoesNotStartStorageWork() {
      var actions = new Actions();
      assertThrows(IllegalArgumentException.class, () -> new DurableCommit(actions, -1));
      assertTrue(actions.calls.isEmpty());
   }

   private static class Actions implements DurableCommit.Actions {
      final List<String> calls = new ArrayList<>();
      final CompletableFuture<Void> batch = new CompletableFuture<>();
      CompletableFuture<Void> index = new CompletableFuture<>();
      boolean seal = true;

      @Override public CompletableFuture<Void> publishBatch() { calls.add("batch"); return batch; }
      @Override public boolean sealJournal() { calls.add("seal"); return seal; }
      @Override public CompletableFuture<Void> publishIndex() { calls.add("index"); return index; }
   }
}
