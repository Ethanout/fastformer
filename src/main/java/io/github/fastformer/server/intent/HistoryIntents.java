package io.github.fastformer.server.intent;

import io.github.fastformer.core.command.Intent;
import java.util.Objects;

/** History and cancellation actions retain their own admission rules during world recovery. */
public final class HistoryIntents {
   private HistoryIntents() {}
   public enum UndoScope { SESSION_OR_WORLD, COMMITTED_WORLD }
   public record Undo(int count, UndoScope scope) implements Intent {
      public Undo { Objects.requireNonNull(scope, "undo scope"); }
   }
   public record Redo(int count) implements Intent {}
   public record Back() implements Intent {}
   public record Quit() implements Intent {}
}
