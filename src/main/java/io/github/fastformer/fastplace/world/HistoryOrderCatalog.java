package io.github.fastformer.fastplace.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Complete durable order, including batches that are not decoded in memory. */
final class HistoryOrderCatalog {
   private final ArrayDeque<UUID> undo;
   private final ArrayDeque<UUID> redo;

   private HistoryOrderCatalog(ArrayDeque<UUID> undo, ArrayDeque<UUID> redo) {
      this.undo = undo;
      this.redo = redo;
   }

   static HistoryOrderCatalog merge(
      List<UUID> memoryUndo,
      List<UUID> memoryRedo,
      List<UUID> durableUndo,
      List<UUID> durableRedo
   ) {
      Set<UUID> present = new HashSet<>();
      ArrayDeque<UUID> undo = mergeStack(memoryUndo, durableUndo, present);
      ArrayDeque<UUID> redo = mergeStack(memoryRedo, durableRedo, present);
      return new HistoryOrderCatalog(undo, redo);
   }

   HistoryOrderCatalog copy() {
      return new HistoryOrderCatalog(new ArrayDeque<>(this.undo), new ArrayDeque<>(this.redo));
   }

   void addNew(UUID operationId) {
      if (operationId == null) {
         throw new IllegalArgumentException("History operation ID is required");
      }
      this.undo.remove(operationId);
      this.redo.remove(operationId);
      this.undo.addFirst(operationId);
      this.redo.clear();
   }

   void commit(boolean undoDirection, UUID operationId) {
      ArrayDeque<UUID> source = undoDirection ? this.undo : this.redo;
      ArrayDeque<UUID> target = undoDirection ? this.redo : this.undo;
      UUID current = source.peekFirst();
      if (!operationId.equals(current)) {
         throw new IllegalStateException("Decoded history does not match its durable order");
      }
      source.removeFirst();
      target.addFirst(operationId);
   }

   void trim(int limit) {
      int bounded = Math.max(1, limit);
      while (this.undo.size() > bounded) this.undo.removeLast();
      while (this.redo.size() > bounded) this.redo.removeLast();
   }

   List<UUID> order(boolean undoDirection) {
      return List.copyOf(undoDirection ? this.undo : this.redo);
   }

   List<UUID> unloaded(boolean undoDirection, List<UUID> loaded, int limit) {
      if (limit <= 0) return List.of();
      Set<UUID> decoded = Set.copyOf(loaded);
      return (undoDirection ? this.undo : this.redo).stream()
         .filter(id -> !decoded.contains(id))
         .limit(limit)
         .toList();
   }

   private static ArrayDeque<UUID> mergeStack(
      List<UUID> memory,
      List<UUID> durable,
      Set<UUID> present
   ) {
      ArrayDeque<UUID> merged = new ArrayDeque<>();
      for (UUID id : memory) {
         if (id != null && present.add(id)) merged.addLast(id);
      }
      for (UUID id : durable) {
         if (id != null && present.add(id)) merged.addLast(id);
      }
      return merged;
   }
}
