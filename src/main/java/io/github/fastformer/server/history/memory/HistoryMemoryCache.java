package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.entry.HistoryReplacement;
import io.github.fastformer.server.history.entry.HistoryEntry;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns decoded undo and redo entries retained in server memory. */
public final class HistoryMemoryCache<E extends HistoryEntry> {
   private ArrayDeque<E> undo = new ArrayDeque<>();
   private ArrayDeque<E> redo = new ArrayDeque<>();
   private long undoBytes;
   private long redoBytes;
   private long revision;
   private long replayRevision;

   public long revision() { return revision; }
   public long replayRevision() { return replayRevision; }

   public void addNew(E batch) {
      undo.addFirst(batch);
      undoBytes += batch.estimatedBytes();
      redo.clear();
      redoBytes = 0L;
      revision++;
   }

   public void merge(boolean undoDirection, List<E> loaded) {
      ArrayDeque<E> target = new ArrayDeque<>(stack(undoDirection));
      Set<UUID> present = new HashSet<>();
      for (E batch : target) present.add(batch.operationId());
      for (E batch : loaded) {
         if (present.add(batch.operationId())) target.addLast(batch);
      }
      long bytes = target.stream().mapToLong(HistoryEntry::estimatedBytes).sum();
      if (undoDirection) {
         undo = target;
         undoBytes = bytes;
      } else {
         redo = target;
         redoBytes = bytes;
      }
      revision++;
   }

   public E peek(boolean undoDirection) {
      return stack(undoDirection).peekFirst();
   }

   public E commit(boolean undoDirection) {
      E committed = stack(undoDirection).removeFirst();
      stack(!undoDirection).addFirst(committed);
      if (undoDirection) {
         undoBytes -= committed.estimatedBytes();
         redoBytes += committed.estimatedBytes();
      } else {
         redoBytes -= committed.estimatedBytes();
         undoBytes += committed.estimatedBytes();
      }
      revision++;
      replayRevision++;
      return committed;
   }

   public void removeNewestUndo() {
      undoBytes -= undo.removeFirst().estimatedBytes();
      revision++;
   }

   public void replaceUndo(HistoryReplacement<E> replacement) {
      var batches = new java.util.ArrayList<>(undo);
      int end = replacement.start() + replacement.removed().size();
      if (!batches.subList(replacement.start(), end).stream().map(HistoryEntry::operationId).toList()
         .equals(replacement.removed())) throw new IllegalStateException("History changed before compaction");
      batches.subList(replacement.start(), end).clear();
      replacement.batch().ifPresent(batch -> batches.add(replacement.start(), batch));
      undo = new ArrayDeque<>(batches);
      undoBytes = batches.stream().mapToLong(HistoryEntry::estimatedBytes).sum();
      revision++;
   }

   public List<E> snapshot(boolean undoDirection) {
      return List.copyOf(stack(undoDirection));
   }

   public List<UUID> operationIds(boolean undoDirection) {
      return stack(undoDirection).stream().map(HistoryEntry::operationId).toList();
   }

   public int size(boolean undoDirection) {
      return stack(undoDirection).size();
   }

   public boolean isEmpty() {
      return undo.isEmpty() && redo.isEmpty();
   }

   public long estimatedBytes() {
      return saturatedAdd(undoBytes, redoBytes);
   }

   public void trim(int limit, long byteLimit) {
      int previousSize = undo.size() + redo.size();
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
      if (previousSize != undo.size() + redo.size()) revision++;
   }

   private ArrayDeque<E> stack(boolean undoDirection) {
      return undoDirection ? undo : redo;
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }
}
