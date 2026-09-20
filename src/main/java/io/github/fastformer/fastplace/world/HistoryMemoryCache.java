package io.github.fastformer.fastplace.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns decoded undo and redo batches retained in server memory. */
final class HistoryMemoryCache {
   private ArrayDeque<WorldChangeBatch> undo = new ArrayDeque<>();
   private ArrayDeque<WorldChangeBatch> redo = new ArrayDeque<>();
   private long undoBytes;
   private long redoBytes;

   void addNew(WorldChangeBatch batch) {
      undo.addFirst(batch);
      undoBytes += batch.estimatedBytes();
      redo.clear();
      redoBytes = 0L;
   }

   void merge(boolean undoDirection, List<WorldChangeBatch> loaded) {
      ArrayDeque<WorldChangeBatch> target = new ArrayDeque<>(stack(undoDirection));
      Set<UUID> present = new HashSet<>();
      for (WorldChangeBatch batch : target) present.add(batch.operationId());
      for (WorldChangeBatch batch : loaded) {
         if (present.add(batch.operationId())) target.addLast(batch);
      }
      long bytes = target.stream().mapToLong(WorldChangeBatch::estimatedBytes).sum();
      if (undoDirection) {
         undo = target;
         undoBytes = bytes;
      } else {
         redo = target;
         redoBytes = bytes;
      }
   }

   WorldChangeBatch peek(boolean undoDirection) {
      return stack(undoDirection).peekFirst();
   }

   WorldChangeBatch commit(boolean undoDirection) {
      WorldChangeBatch committed = stack(undoDirection).removeFirst();
      stack(!undoDirection).addFirst(committed);
      if (undoDirection) {
         undoBytes -= committed.estimatedBytes();
         redoBytes += committed.estimatedBytes();
      } else {
         redoBytes -= committed.estimatedBytes();
         undoBytes += committed.estimatedBytes();
      }
      return committed;
   }

   List<WorldChangeBatch> snapshot(boolean undoDirection) {
      return List.copyOf(stack(undoDirection));
   }

   List<UUID> operationIds(boolean undoDirection) {
      return stack(undoDirection).stream().map(WorldChangeBatch::operationId).toList();
   }

   int size(boolean undoDirection) {
      return stack(undoDirection).size();
   }

   boolean isEmpty() {
      return undo.isEmpty() && redo.isEmpty();
   }

   long estimatedBytes() {
      return saturatedAdd(undoBytes, redoBytes);
   }

   void trim(int limit, long byteLimit) {
      int bounded = Math.max(1, limit);
      while (undo.size() > bounded) undoBytes -= undo.removeLast().estimatedBytes();
      while (redo.size() > bounded) redoBytes -= redo.removeLast().estimatedBytes();
      while (estimatedBytes() > byteLimit && (undo.size() > 1 || redo.size() > 1)) {
         if (redo.isEmpty() || (undoBytes >= redoBytes && undo.size() > 1)) {
            undoBytes -= undo.removeLast().estimatedBytes();
         } else {
            redoBytes -= redo.removeLast().estimatedBytes();
         }
      }
   }

   private ArrayDeque<WorldChangeBatch> stack(boolean undoDirection) {
      return undoDirection ? undo : redo;
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }
}
