package io.github.fastformer.fastplace.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which durable history batches to load when memory cannot satisfy an
 * undo or redo request. The plan owns only load selection and resume metadata.
 */
final class HistoryPageLoadPlan {
   static final int MINIMUM_PAGE_ENTRIES = 32;

   private final boolean undoDirection;
   private final int requestedCount;
   private final List<UUID> operationIds;

   private HistoryPageLoadPlan(boolean undoDirection, int requestedCount, List<UUID> operationIds) {
      this.undoDirection = undoDirection;
      this.requestedCount = requestedCount;
      this.operationIds = List.copyOf(operationIds);
   }

   static Optional<HistoryPageLoadPlan> create(
      boolean undoDirection,
      int requestedCount,
      List<UUID> loadedOperationIds,
      int historyLimit,
      HistoryOrderCatalog order
   ) {
      Objects.requireNonNull(loadedOperationIds, "loadedOperationIds");
      Objects.requireNonNull(order, "order");
      int missingCount = requestedCount - loadedOperationIds.size();
      if (missingCount <= 0 || historyLimit <= 0) {
         return Optional.empty();
      }
      int pageSize = Math.min(historyLimit, Math.max(MINIMUM_PAGE_ENTRIES, missingCount));
      List<UUID> operationIds = order.unloaded(undoDirection, loadedOperationIds, pageSize);
      if (operationIds.isEmpty()) {
         return Optional.empty();
      }
      return Optional.of(new HistoryPageLoadPlan(undoDirection, requestedCount, operationIds));
   }

   boolean undoDirection() {
      return this.undoDirection;
   }

   int requestedCount() {
      return this.requestedCount;
   }

   List<UUID> operationIds() {
      return this.operationIds;
   }
}
