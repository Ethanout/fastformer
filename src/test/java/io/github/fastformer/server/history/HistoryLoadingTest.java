package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.memory.HistoryOrderCatalog;
import io.github.fastformer.server.history.memory.HistoryPageLoadPlan;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HistoryLoadingTest {
   @Test
   void initialMergeFailureStaysBlockedUntilSuccessfulRetry() {
      var loading = new HistoryLoading<WorldChangeBatch>();
      loading.startSnapshot(CompletableFuture.completedFuture(HistoryRepository.Snapshot.empty()));
      assertThrows(IllegalStateException.class, () -> loading.attachSnapshot(ignored -> {
         throw new IllegalStateException("Merge failed");
      }));
      assertTrue(loading.snapshotFailed());
      assertFalse(loading.pending());
      var retry = new CompletableFuture<HistoryRepository.Snapshot<WorldChangeBatch>>();
      loading.startSnapshot(retry);
      assertTrue(loading.snapshotFailed());
      assertTrue(loading.pending());
      retry.complete(HistoryRepository.Snapshot.empty());
      loading.attachSnapshot(ignored -> {});
      assertFalse(loading.snapshotFailed());
      assertFalse(loading.pending());
   }

   @Test
   void pageMergeFailureRemembersOnlyThePageAndAllowsAnotherRequest() {
      var loading = new HistoryLoading<WorldChangeBatch>();
      var plan = plan();
      loading.pages().startPageLoad(plan, CompletableFuture.completedFuture(List.of()));
      assertThrows(IllegalStateException.class, () -> loading.attachPage(ignored -> {
         throw new IllegalStateException("Page merge failed");
      }));
      assertFalse(loading.snapshotFailed());
      assertFalse(loading.pending());
      assertEquals(plan, loading.pages().failedPagePlan().orElseThrow());
      loading.pages().startPageLoad(plan, CompletableFuture.completedFuture(List.of()));
      assertEquals(plan, loading.attachPage(ignored -> {}).orElseThrow());
      assertTrue(loading.pages().failedPagePlan().isEmpty());
   }

   @Test
   void closeCancelsBothLoadsAndRejectsLateInitialResults() {
      var loading = new HistoryLoading<WorldChangeBatch>();
      var snapshot = new CompletableFuture<HistoryRepository.Snapshot<WorldChangeBatch>>();
      var page = new CompletableFuture<List<WorldChangeBatch>>();
      loading.startSnapshot(snapshot);
      loading.pages().startPageLoad(plan(), page);
      loading.close();
      loading.close();
      assertTrue(snapshot.isCancelled());
      assertTrue(page.isCancelled());
      assertFalse(loading.pending());
      loading.attachSnapshot(ignored -> fail("Closed snapshot attached"));
      assertThrows(IllegalStateException.class, () -> loading.startSnapshot(CompletableFuture.completedFuture(HistoryRepository.Snapshot.empty())));
   }

   private static HistoryPageLoadPlan plan() {
      return HistoryPageLoadPlan.create(true, 1, List.of(), 200,
         HistoryOrderCatalog.merge(List.of(), List.of(), List.of(UUID.randomUUID()), List.of())).orElseThrow();
   }
}
