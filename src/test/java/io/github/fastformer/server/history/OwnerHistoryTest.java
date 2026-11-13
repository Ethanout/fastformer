package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryOrderCatalog;
import io.github.fastformer.server.history.memory.HistoryPageLoadPlan;
import io.github.fastformer.server.history.rollback.RollbackRequest;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.server.task.WorldTaskContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class OwnerHistoryTest {
   @Test
   void failedSnapshotBlocksEditsButWaitsForExplicitRetry() {
      OwnerHistory owner = new OwnerHistory();
      owner.loading.startSnapshot(CompletableFuture.failedFuture(new IllegalStateException()));
      org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
         () -> owner.loading.attachSnapshot(owner::mergeSnapshot));
      owner.detached = true;
      assertTrue(owner.busy());
      assertFalse(owner.needsTick());
      assertFalse(owner.canEvict());

      owner.loading.startSnapshot(new CompletableFuture<>());
      assertTrue(owner.needsTick());
   }

   @Test
   void dirtyPersistenceTicksWithoutBlockingEditsOrLosingOwnership() {
      OwnerHistory owner = new OwnerHistory();
      owner.detached = true;
      owner.persistence.track(false, CompletableFuture.failedFuture(new IllegalStateException()), Runnable::run, ignored -> {});
      assertFalse(owner.busy());
      assertTrue(owner.needsTick());
      assertFalse(owner.canEvict());

      CompletableFuture<Void> pending = new CompletableFuture<>();
      owner.persistence.track(true, pending, Runnable::run, ignored -> {});
      assertFalse(owner.busy());
      assertFalse(owner.canEvict());
      pending.complete(null);
      assertTrue(owner.canEvict());
   }

   @Test
   void queuedRecoverySharesTheAdmissionAndTickPredicate() {
      OwnerHistory owner = new OwnerHistory();
      owner.detached = true;
      owner.recovery.enqueue(new RollbackRequest(new WorldTaskContext(null, UUID.randomUUID()), Level.OVERWORLD,
         new WorldRecoverySnapshot(new ArrayDeque<>(), Map.of(), new CompletableFuture<>()), null));
      assertTrue(owner.busy());
      assertTrue(owner.needsTick());
      assertFalse(owner.canEvict());
   }

   @Test
   void discardCancelsInitialAndPagedLoadsBeforeLateResultsCanAttach() {
      OwnerHistory owner = new OwnerHistory();
      CompletableFuture<HistoryRepository.Snapshot<HistoryEntry>> initial = new CompletableFuture<>();
      CompletableFuture<List<HistoryEntry>> page = new CompletableFuture<>();
      owner.loading.startSnapshot(initial);
      var plan = HistoryPageLoadPlan.create(true, 1, List.of(), 200,
         HistoryOrderCatalog.merge(List.of(), List.of(), List.of(UUID.randomUUID()), List.of())).orElseThrow();
      owner.loading.pages().startPageLoad(plan, page);

      owner.discard();
      owner.discard();

      assertTrue(initial.isCancelled());
      assertTrue(page.isCancelled());
      assertFalse(initial.complete(HistoryRepository.Snapshot.empty()));
      assertFalse(page.complete(List.of()));
      assertFalse(owner.loading.pages().hasPendingPageLoad());
      assertTrue(owner.loading.pages().takeCompletedPageLoad().isEmpty());
   }
}
