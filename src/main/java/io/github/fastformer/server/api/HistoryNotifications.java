package io.github.fastformer.server.api;

import io.github.fastformer.common.api.HistoryRecorded;
import io.github.fastformer.common.api.ShapeCommitted;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.WorldTaskContext;
import java.util.Optional;

/** Captures values before writers release their transaction and compressed batch. */
public final class HistoryNotifications {
   private HistoryNotifications() {}

   public static void recorded(WorldTaskContext context, HistoryEntry batch, HistoryRecorded.Persistence persistence) {
      context.events().publish(new HistoryRecorded(context.owner(), batch.operationId(), batch.dimension().location(),
         batch.type(), batch.size(), persistence));
   }

   public static Optional<ShapeCommitted> captureShape(WorldTaskContext context, WorldTask task, ShapeCommitted.Kind kind) {
      return task.preparedBatch().filter(batch -> batch.size() > 0).map(batch ->
         new ShapeCommitted(context.owner(), task.operationId(), task.dimension().location(), batch.size(), kind));
   }
}
