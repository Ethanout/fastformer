package io.github.fastformer.server.history.memory;

import java.util.UUID;

/** Identity of a held edit. It cannot extend a record across another edit or undo/redo. */
public final class BlockEditHistoryGesture {
   private HistoryMemoryCache<?> memory;
   private UUID operationId;
   private long replayRevision;

   public boolean canExtend(HistoryMemoryCache<?> history) {
      var latest = history.peek(true);
      return memory == history && operationId != null && latest != null
         && operationId.equals(latest.operationId()) && replayRevision == history.replayRevision();
   }

   public void recorded(HistoryMemoryCache<?> history, boolean hasBatch) {
      memory = history;
      operationId = hasBatch ? history.peek(true).operationId() : null;
      replayRevision = history.replayRevision();
   }
}
