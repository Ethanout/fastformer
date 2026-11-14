package io.github.fastformer.server.intent.selection;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationPointDragConstraint;
import net.minecraft.core.BlockPos;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import java.util.Objects;

/** Selection actions shared by command and input entry points. */
public final class SelectionIntents {
   private SelectionIntents() {}

   public record Apply(boolean copy) implements Intent {}
   public record CycleMode() implements Intent {}
   public record SelectPoint(int index) implements Intent {}
   public record RemovePoint(int index) implements Intent {}
   public record CloseBase() implements Intent {}
   public record Expand(BlockPos point) implements Intent {
      public Expand { point = Objects.requireNonNull(point, "point").immutable(); }
   }
   public record InsertPoint(int index, BlockPos point) implements Intent {
      public InsertPoint { point = Objects.requireNonNull(point, "point").immutable(); }
   }
   public record DragPoint(int index, BlockPos target, OperationPointDragConstraint constraint, boolean finish) implements Intent {
      public DragPoint {
         target = Objects.requireNonNull(target, "target").immutable();
         Objects.requireNonNull(constraint, "constraint");
      }
   }
   public record Extend(int axis, boolean positive, int steps, boolean finish) implements Intent {}
   public record SelectMode(OperationSelectionMode mode) implements Intent {
      public SelectMode { Objects.requireNonNull(mode, "mode"); }
   }
   public record ConflictMode(OperationConflictMode mode) implements Intent {
      public ConflictMode { Objects.requireNonNull(mode, "mode"); }
   }
}
