package io.github.fastformer.client.operation.selection;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;

/** User tool choice outlives selection sessions and is not part of undo snapshots. */
public final class SelectionToolPreference {
   private static OperationSelectionMode mode = OperationSelectionMode.CUBOID;
   private SelectionToolPreference() { }
   public static OperationSelectionMode get() { return mode; }
   public static void set(OperationSelectionMode value) {
      mode = value == OperationSelectionMode.SMART ? value : OperationSelectionMode.CUBOID;
   }
}
