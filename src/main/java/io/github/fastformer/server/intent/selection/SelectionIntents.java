package io.github.fastformer.server.intent.selection;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import java.util.Objects;

/** Selection actions shared by command and input entry points. */
public final class SelectionIntents {
   private SelectionIntents() {}

   public record Apply(boolean copy) implements Intent {}
   public record CycleMode() implements Intent {}
   public record SelectMode(OperationSelectionMode mode) implements Intent {
      public SelectMode { Objects.requireNonNull(mode, "mode"); }
   }
   public record ConflictMode(OperationConflictMode mode) implements Intent {
      public ConflictMode { Objects.requireNonNull(mode, "mode"); }
   }
}
