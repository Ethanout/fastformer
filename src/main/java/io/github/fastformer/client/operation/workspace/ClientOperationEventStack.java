package io.github.fastformer.client.operation.workspace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Small client-only chain of reversible input events. */
public final class ClientOperationEventStack {
   private final Deque<Runnable> undo = new ArrayDeque<>();

   public void push(Runnable inverse) {
      if (inverse != null) this.undo.addLast(inverse);
   }

   /** Adds several inverses as one node; they run in reverse child order. */
   public void pushChain(List<Runnable> inverses) {
      if (inverses == null || inverses.isEmpty()) return;
      List<Runnable> chain = List.copyOf(inverses);
      this.push(() -> {
         for (int index = chain.size() - 1; index >= 0; index--) {
            Runnable inverse = chain.get(index);
            if (inverse != null) inverse.run();
         }
      });
   }

   public boolean undo() {
      Runnable inverse = this.undo.pollLast();
      if (inverse == null) return false;
      inverse.run();
      return true;
   }

   public void clear() { this.undo.clear(); }
   public int size() { return this.undo.size(); }
}
