package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class HistoryTaskSchedulerTest {
   @Test
   void cancellationDropsEvenAnAlreadyCompletedPageBeforeItCanResume() {
      HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
      scheduler.startPageLoad(pagePlan(true, 3), CompletableFuture.completedFuture(List.of()));
      assertTrue(scheduler.cancelPageLoad());
      assertFalse(scheduler.hasPendingPageLoad());
      assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
      assertFalse(scheduler.cancelPageLoad());
   }

   @Test
   void mergeFailureKeepsTheRequestUntilAnExplicitRetryStarts() {
      HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
      HistoryPageLoadPlan plan = pagePlan(true, 5);
      scheduler.startPageLoad(plan, CompletableFuture.completedFuture(List.of()));
      var loaded = scheduler.takeCompletedPageLoad().orElseThrow();
      scheduler.pageMergeFailed(loaded.plan());
      assertFalse(scheduler.hasPendingPageLoad());
      assertEquals(plan, scheduler.failedPagePlan().orElseThrow());
      assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
      scheduler.startPageLoad(plan, new CompletableFuture<>());
      assertTrue(scheduler.failedPagePlan().isEmpty());
      assertTrue(scheduler.hasPendingPageLoad());
   }

   @Test
   void keepsTheOriginalUndoRequestUntilThePageLoadCompletes() {
      HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
      HistoryPageLoadPlan plan = pagePlan(true, 3);
      CompletableFuture<List<WorldChangeBatch>> page = new CompletableFuture<>();

      scheduler.startPageLoad(plan, page);

      assertTrue(scheduler.hasPendingPageLoad());
      assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
      assertThrows(IllegalStateException.class, () -> scheduler.startPageLoad(pagePlan(false, 1), page));

      page.complete(List.of());
      HistoryTaskScheduler.CompletedPageLoad completed = scheduler.takeCompletedPageLoad().orElseThrow();

      assertEquals(true, completed.plan().undoDirection());
      assertEquals(3, completed.plan().requestedCount());
      assertFalse(scheduler.hasPendingPageLoad());
   }

   @Test
   void clearsTheCompletedPageStateBeforeReportingALoadFailure() {
      HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
      CompletableFuture<List<WorldChangeBatch>> failedPage = new CompletableFuture<>();
      scheduler.startPageLoad(pagePlan(true, 1), failedPage);
      failedPage.completeExceptionally(new IllegalStateException("disk failure"));

      assertThrows(CompletionException.class, scheduler::takeCompletedPageLoad);
      assertFalse(scheduler.hasPendingPageLoad());

      scheduler.startPageLoad(pagePlan(false, 1), CompletableFuture.completedFuture(List.of()));
      assertTrue(scheduler.hasPendingPageLoad());
   }

   private static HistoryPageLoadPlan pagePlan(boolean undoDirection, int requestedCount) {
      UUID operationId = UUID.randomUUID();
      return HistoryPageLoadPlan.create(
         undoDirection,
         requestedCount,
         List.of(),
         200,
         HistoryOrderCatalog.merge(
            List.of(), List.of(),
            undoDirection ? List.of(operationId) : List.of(),
            undoDirection ? List.of() : List.of(operationId)
         )
      ).orElseThrow();
   }
}
