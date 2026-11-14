package io.github.fastformer.common.selection.session;

import io.github.fastformer.common.history.SessionHistoryLimits;
import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded local edit history; the session supplies and restores its own immutable snapshots. */
final class OperationEditHistory<S> {
   private final Deque<S> undo = new ArrayDeque<>();
   private final Deque<S> redo = new ArrayDeque<>();
   private S pending;
   private int limit;

   OperationEditHistory(int limit) { updateLimit(limit); }
   void updateLimit(int limit) {
      this.limit = Math.clamp(limit, 1, SessionHistoryLimits.MAX_UNDO_HISTORY_LIMIT);
      trim(undo);
      trim(redo);
   }
   void begin(S current) { if (pending == null) pending = current; }
   boolean commit(S current) {
      S before = pending;
      pending = null;
      if (before == null || before.equals(current)) return false;
      record(before);
      return true;
   }
   void record(S before) { push(undo, before); redo.clear(); }
   S undo(S current) {
      S previous = undo.pollLast();
      if (previous != null) push(redo, current);
      return previous;
   }
   S redo(S current) {
      S next = redo.pollLast();
      if (next != null) push(undo, current);
      return next;
   }
   boolean canUndo(S current) { return !undo.isEmpty() || pending != null && !pending.equals(current); }
   boolean canRedo() { return !redo.isEmpty(); }
   void discardPending() { pending = null; }
   void clearStacks() { undo.clear(); redo.clear(); }
   void clear() { clearStacks(); pending = null; }
   private void push(Deque<S> target, S value) { target.addLast(value); trim(target); }
   private void trim(Deque<S> target) { while (target.size() > limit) target.removeFirst(); }
}
